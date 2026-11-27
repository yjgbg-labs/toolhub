package groot.adb

import cats.effect.IO
import java.io.{InputStream, OutputStream}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets.{US_ASCII, UTF_8}
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration
import scala.util.Using
import scodec.bits.ByteVector

object AdbSync:
  val ID_STAT: Int = fourCC("STAT")
  val ID_LIST: Int = fourCC("LIST")
  val ID_DENT: Int = fourCC("DENT")
  val ID_SEND: Int = fourCC("SEND")
  val ID_RECV: Int = fourCC("RECV")
  val ID_DATA: Int = fourCC("DATA")
  val ID_DONE: Int = fourCC("DONE")
  val ID_OKAY: Int = fourCC("OKAY")
  val ID_FAIL: Int = fourCC("FAIL")
  val ID_QUIT: Int = fourCC("QUIT")

  val SYNC_DATA_MAX: Int = 64 * 1024

  private def fourCC(s: String): Int =
    ByteBuffer.wrap(s.getBytes(US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).getInt()

  private def syncReq(id: Int, arg: String): ByteVector =
    val pathBytes = arg.getBytes(UTF_8)
    val hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    hdr.putInt(id)
    hdr.putInt(pathBytes.length)
    ByteVector.view(hdr.array()) ++ ByteVector.view(pathBytes)

  private def dataHeader(id: Int, lenOrArg: Int): ByteVector =
    val hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    hdr.putInt(id)
    hdr.putInt(lenOrArg)
    ByteVector.view(hdr.array())

  final private class SyncReader(stream: AdbStream):
    private var buf: ByteVector = ByteVector.empty

    def readExact(n: Int): IO[ByteVector] =
      if n == 0 then IO.pure(ByteVector.empty)
      else if buf.size >= n then
        val (out, rest) = buf.splitAt(n.toLong)
        buf = rest
        IO.pure(out)
      else
        stream.readChunk.flatMap {
          case Some(chunk) =>
            buf = buf ++ chunk
            readExact(n)
          case None =>
            IO.raiseError(AdbFailure("adb_protocol_error", s"Unexpected EOF in ADB sync stream (wanted $n bytes, had ${buf.size})"))
        }

    def readIntLE: IO[Int] =
      readExact(4).map(_.toByteBuffer.order(ByteOrder.LITTLE_ENDIAN).getInt())

  private def withSync[A](conn: AdbConnection, openTimeout: FiniteDuration)(use: (AdbStream, SyncReader) => IO[A]): IO[A] =
    conn.openStream("sync:", openTimeout).use { stream =>
      val reader = new SyncReader(stream)
      use(stream, reader).guarantee(
        stream.write(dataHeader(ID_QUIT, 0)).timeout(scala.concurrent.duration.DurationInt(2).seconds).attempt.void
      )
    }

  def stat(conn: AdbConnection, remotePath: String, openTimeout: FiniteDuration): IO[SyncStatResult] =
    withSync(conn, openTimeout) { (stream, reader) =>
      for
        _ <- stream.write(syncReq(ID_STAT, remotePath))
        raw <- reader.readExact(16)
        buf = raw.toByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        id = buf.getInt()
        _ <-
          if id != ID_STAT then
            IO.raiseError(AdbFailure("adb_protocol_error", f"Expected STAT response, got 0x$id%08x"))
          else IO.unit
        mode = buf.getInt()
        size = Integer.toUnsignedLong(buf.getInt())
        mtime = Integer.toUnsignedLong(buf.getInt())
      yield SyncStatResult(
        address = conn.address,
        remotePath = remotePath,
        exists = mode != 0,
        mode = mode,
        sizeBytes = size,
        mtimeEpochSeconds = mtime,
        isDirectory = (mode & 0xf000) == 0x4000,
        isRegularFile = (mode & 0xf000) == 0x8000,
        isSymlink = (mode & 0xf000) == 0xa000
      )
    }

  def list(conn: AdbConnection, remotePath: String, openTimeout: FiniteDuration, maxEntries: Int = 10000): IO[SyncListResult] =
    withSync(conn, openTimeout) { (stream, reader) =>
      val acc = ArrayBuffer.empty[SyncDirEntry]
      def loop: IO[Vector[SyncDirEntry]] =
        reader.readIntLE.flatMap {
          case id if id == ID_DENT =>
            for
              meta <- reader.readExact(16)
              mb = meta.toByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
              mode = mb.getInt()
              size = Integer.toUnsignedLong(mb.getInt())
              mtime = Integer.toUnsignedLong(mb.getInt())
              nameLen = mb.getInt()
              _ <-
                if nameLen < 0 || nameLen > 4096 then
                  IO.raiseError(AdbFailure("adb_protocol_error", s"Invalid DENT filename length: $nameLen"))
                else IO.unit
              nameBytes <- reader.readExact(nameLen)
              name = new String(nameBytes.toArray, UTF_8)
              _ <-
                if name != "." && name != ".." then
                  if acc.size >= maxEntries then
                    IO.raiseError(AdbFailure("adb_limit", s"Directory listing exceeded $maxEntries entries for $remotePath"))
                  else
                    IO {
                      acc += SyncDirEntry(
                        name = name,
                        mode = mode,
                        sizeBytes = size,
                        mtimeEpochSeconds = mtime,
                        isDirectory = (mode & 0xf000) == 0x4000,
                        isRegularFile = (mode & 0xf000) == 0x8000,
                        isSymlink = (mode & 0xf000) == 0xa000
                      )
                    }
                else IO.unit
              out <- loop
            yield out
          case id if id == ID_DONE =>
            reader.readExact(16).as(acc.sortBy(_.name).toVector)
          case id if id == ID_FAIL =>
            for
              msgLen <- reader.readIntLE
              msgBytes <- reader.readExact(msgLen.max(0).min(8192))
              msg = new String(msgBytes.toArray, UTF_8)
              res <- IO.raiseError[Vector[SyncDirEntry]](classifySyncFail(remotePath, msg))
            yield res
          case other =>
            IO.raiseError(AdbFailure("adb_protocol_error", f"Unexpected sync LIST frame 0x$other%08x"))
        }

      stream.write(syncReq(ID_LIST, remotePath)) *> loop.map(entries => SyncListResult(conn.address, remotePath, entries))
    }

  def recvToBytes(
      conn: AdbConnection,
      remotePath: String,
      openTimeout: FiniteDuration,
      maxBytes: Long
  ): IO[ByteVector] =
    withSync(conn, openTimeout) { (stream, reader) =>
      def loop(acc: ByteVector): IO[ByteVector] =
        reader.readExact(8).flatMap { hdr =>
          val hb = hdr.toByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
          val id = hb.getInt()
          val len = hb.getInt()
          if id == ID_DATA then
            if len < 0 || len > 1024 * 1024 then
              IO.raiseError(AdbFailure("adb_protocol_error", s"Invalid sync DATA chunk length: $len"))
            else if acc.size + len > maxBytes then
              IO.raiseError(AdbFailure("adb_limit", s"Remote file '$remotePath' exceeds transfer limit ($maxBytes bytes)"))
            else
              reader.readExact(len).flatMap(chunk => loop(acc ++ chunk))
          else if id == ID_DONE then
            IO.pure(acc)
          else if id == ID_FAIL then
            reader.readExact(len.max(0).min(8192)).flatMap { msgBytes =>
              IO.raiseError(classifySyncFail(remotePath, new String(msgBytes.toArray, UTF_8)))
            }
          else
            IO.raiseError(AdbFailure("adb_protocol_error", f"Unexpected sync RECV frame 0x$id%08x"))
        }

      stream.write(syncReq(ID_RECV, remotePath)) *> loop(ByteVector.empty)
    }

  def recvToFile(
      conn: AdbConnection,
      remotePath: String,
      destination: Path,
      openTimeout: FiniteDuration,
      maxBytes: Long
  ): IO[Long] =
    val target = destination.toAbsolutePath.normalize()
    val parent = Option(target.getParent).getOrElse(throw AdbFailure("invalid_adb", s"Destination has no parent directory: $target"))
    IO.blocking {
      Files.createDirectories(parent)
      Files.createTempFile(parent, ".adb-pull-", ".tmp")
    }.bracketCase { tmp =>
      IO.blocking(Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)).bracket { out =>
        withSync(conn, openTimeout) { (stream, reader) =>
          def loop(written: Long): IO[Long] =
            reader.readExact(8).flatMap { hdr =>
              val hb = hdr.toByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
              val id = hb.getInt()
              val len = hb.getInt()
              if id == ID_DATA then
                if len < 0 || len > 1024 * 1024 then
                  IO.raiseError(AdbFailure("adb_protocol_error", s"Invalid sync DATA chunk length: $len"))
                else if written + len > maxBytes then
                  IO.raiseError(AdbFailure("adb_limit", s"Remote file '$remotePath' exceeds transfer limit ($maxBytes bytes)"))
                else
                  reader.readExact(len).flatMap { chunk =>
                    IO.blocking(out.write(chunk.toArray)) *> loop(written + len)
                  }
              else if id == ID_DONE then
                IO.blocking(out.flush()).as(written)
              else if id == ID_FAIL then
                reader.readExact(len.max(0).min(8192)).flatMap { msgBytes =>
                  IO.raiseError(classifySyncFail(remotePath, new String(msgBytes.toArray, UTF_8)))
                }
              else
                IO.raiseError(AdbFailure("adb_protocol_error", f"Unexpected sync RECV frame 0x$id%08x"))
            }

          stream.write(syncReq(ID_RECV, remotePath)) *> loop(0L)
        }
      }(out => IO.blocking(out.close()).attempt.void).flatTap { _ =>
        IO.blocking {
          Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          ()
        }
      }
    } { (tmp, _) =>
      IO.blocking(Files.deleteIfExists(tmp)).attempt.void
    }

  def sendFile(
      conn: AdbConnection,
      localFile: Path,
      remotePath: String,
      mode: Int,
      openTimeout: FiniteDuration,
      maxBytes: Long
  ): IO[Long] =
    val source = localFile.toAbsolutePath.normalize()
    for
      (fileSize, mtimeSec) <- IO.blocking {
        if !Files.exists(source) then throw AdbFailure("adb_not_found", s"Local file does not exist: $source")
        if !Files.isRegularFile(source) then throw AdbFailure("invalid_adb", s"Local path is not a regular file: $source")
        val sz = Files.size(source)
        val mt = (Files.getLastModifiedTime(source).toMillis / 1000L).toInt
        (sz, mt)
      }
      _ <-
        if fileSize > maxBytes then
          IO.raiseError(AdbFailure("adb_limit", s"Local file '$source' ($fileSize bytes) exceeds transfer limit ($maxBytes bytes)"))
        else IO.unit
      posixMode = 0x8000 | (mode & 0x1ff)
      spec = s"$remotePath,$posixMode"
      _ <- withSync(conn, openTimeout) { (stream, reader) =>
        IO.blocking(Files.newInputStream(source)).bracket { in =>
          val buf = new Array[Byte](SYNC_DATA_MAX)
          def pump(sent: Long): IO[Long] =
            IO.blocking(in.read(buf)).flatMap { n =>
              if n < 0 then IO.pure(sent)
              else if n == 0 then pump(sent)
              else
                val frame = dataHeader(ID_DATA, n) ++ ByteVector.view(java.util.Arrays.copyOf(buf, n))
                stream.write(frame) *> pump(sent + n)
            }

          for
            _ <- stream.write(syncReq(ID_SEND, spec))
            total <- pump(0L)
            _ <- stream.write(dataHeader(ID_DONE, mtimeSec))
            resp <- reader.readExact(8)
            rb = resp.toByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            id = rb.getInt()
            msgLen = rb.getInt()
            _ <-
              if id == ID_OKAY then
                if msgLen > 0 then reader.readExact(msgLen.min(4096)).void else IO.unit
              else if id == ID_FAIL then
                reader.readExact(msgLen.max(0).min(8192)).flatMap { msgBytes =>
                  IO.raiseError(classifySyncFail(remotePath, new String(msgBytes.toArray, UTF_8)))
                }
              else
                IO.raiseError(AdbFailure("adb_protocol_error", f"Unexpected sync SEND response 0x$id%08x"))
          yield total
        }(in => IO.blocking(in.close()).attempt.void)
      }
    yield fileSize

  private def classifySyncFail(remotePath: String, msg: String): AdbFailure =
    val lower = msg.toLowerCase
    if lower.contains("no such file") || lower.contains("not found") then
      AdbFailure("adb_not_found", s"Remote path '$remotePath': $msg")
    else if lower.contains("permission denied") || lower.contains("read-only") then
      AdbFailure("adb_forbidden", s"Remote path '$remotePath': $msg")
    else
      AdbFailure("adb_remote_error", s"Remote sync failed for '$remotePath': $msg")
