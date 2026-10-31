package groot.fs

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.*
import java.nio.file.attribute.{BasicFileAttributes, PosixFileAttributeView}
import java.util.Base64

/** Server-local files. Each operation owns its handles; cancellation waits for their release. */
final class FsService:
  import FsService.*
  import LinkOption.NOFOLLOW_LINKS
  import StandardOpenOption.*

  def read(path: String, encoding: FileEncoding = FileEncoding.utf8): IO[FileContent] = for
    file <- absolute(path)
    _ <- regular(file, followLinks = true)
    bytes <- readChunks(file).flatMap(Stream.chunk).take(maxBytes.toLong + 1).compile.to(Array)
    result <- IO {
      if bytes.length > maxBytes then throw FsLimit("File content exceeds 1 MiB")
      val content = encoding match
        case FileEncoding.utf8 => UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString
        case FileEncoding.base64 => Base64.getEncoder.encodeToString(bytes)
      FileContent(content, encoding, bytes.length)
    }
  yield result

  def write(path: String, body: String, encoding: FileEncoding = FileEncoding.utf8,
      mode: WriteMode = WriteMode.replace): IO[WriteResult] = for
    file <- absolute(path)
    bytes <- decode(body, encoding)
    _ <- writable(file)
    _ <- mode match
      case WriteMode.replace => replace(file)(temporary => writeChunks(temporary, Stream.chunk(Chunk.array(bytes)).chunkLimit(chunkBytes), WRITE))
      case WriteMode.create => writeChunks(file, Stream.chunk(Chunk.array(bytes)).chunkLimit(chunkBytes), WRITE, CREATE_NEW)
      case WriteMode.append => writeChunks(file, Stream.chunk(Chunk.array(bytes)).chunkLimit(chunkBytes), WRITE, CREATE, APPEND)
  yield WriteResult(bytes.length)

  def stat(path: String): IO[FileStat] = for
    file <- absolute(path)
    info <- attributes(file)
    link <- if info.isSymbolicLink then IO.blocking(Files.readSymbolicLink(file).toString).map(Some(_)) else IO.pure(None)
  yield FileStat(kind(info), info.size(), info.lastModifiedTime().toInstant.toString, link)

  def list(path: String): IO[Vector[DirectoryEntry]] = for
    directory <- absolute(path)
    info <- attributes(directory)
    _ <- IO.raiseUnless(info.isDirectory)(FsConflict("not_directory", s"Not a directory: $directory"))
    entries <- Resource.fromAutoCloseable(IO.blocking(Files.newDirectoryStream(directory))).use { stream =>
      IO.blocking(stream.iterator()).flatMap { iterator =>
        Stream.repeatEval(IO.interruptible {
          if iterator.hasNext then Some(iterator.next()) else None
        }).unNoneTerminate.take(maxEntries.toLong + 1).compile.toVector
      }
    }.adaptError { case error: DirectoryIteratorException => error.getCause }
    _ <- IO.raiseWhen(entries.size > maxEntries)(FsLimit(s"Directory exceeds $maxEntries entries"))
    result <- entries.sortBy(_.getFileName.toString).traverse { entry =>
      attributes(entry).map(info => DirectoryEntry(entry.getFileName.toString, kind(info)))
    }
  yield result

  def mkdir(path: String, recursive: Boolean = false): IO[Unit] = absolute(path).flatMap { directory =>
    IO.blocking {
      if recursive then Files.createDirectories(directory) else Files.createDirectory(directory)
    } *> attributes(directory).flatMap { info =>
      IO.raiseUnless(info.isDirectory)(FsConflict("not_directory", s"Not a directory: $directory"))
    }
  }

  def copy(from: String, to: String, overwrite: Boolean = false): IO[Unit] = for
    source <- absolute(from)
    target <- absolute(to)
    _ <- regular(source, followLinks = true)
    _ <- writable(target)
    _ <- if overwrite then replace(target)(temporary => writeChunks(temporary, readChunks(source), WRITE))
         else writeChunks(target, readChunks(source), WRITE, CREATE_NEW)
  yield ()

  def move(from: String, to: String, overwrite: Boolean = false): IO[Unit] = for
    source <- absolute(from)
    target <- absolute(to)
    _ <- attributes(source)
    _ <- IO.blocking {
      if source.getParent == null || target.getParent == null then
        throw InvalidFs("Cannot move a filesystem root")
      // Files.move may otherwise silently fall back to copy-and-delete across file stores.
      if Files.getFileStore(source.getParent) != Files.getFileStore(target.getParent) then
        throw FsConflict("cross_device", "Move requires source and destination on the same filesystem")
      if overwrite then Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
      else Files.move(source, target)
    }
  yield ()

  def remove(path: String, recursive: Boolean = false): IO[Unit] = absolute(path).flatMap { file =>
    if !recursive then IO.blocking(Files.delete(file))
    else IO.interruptible {
      def checkCancelled(): Unit = if Thread.currentThread().isInterrupted then throw new InterruptedException("File removal cancelled")
      // No FOLLOW_LINKS: symlinks, including a symlink supplied as the root, are deleted as entries.
      Files.walkFileTree(file, new SimpleFileVisitor[Path] {
        override def preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult =
          checkCancelled()
          FileVisitResult.CONTINUE
        override def visitFile(entry: Path, attributes: BasicFileAttributes): FileVisitResult =
          checkCancelled()
          Files.delete(entry)
          FileVisitResult.CONTINUE
        override def postVisitDirectory(directory: Path, error: java.io.IOException): FileVisitResult =
          checkCancelled()
          if error != null then throw error
          Files.delete(directory)
          FileVisitResult.CONTINUE
      })
    }.void
  }

  /** Shared by replacement writes and copies; publish only after the writer has closed its handles. */
  private[fs] def replace(target: Path)(write: Path => IO[Unit]): IO[Unit] =
    writable(target) *> Resource.make(
      IO.blocking(Files.createTempFile(target.getParent, ".sprout-fs-", ".tmp"))
    )(temporary => IO.blocking(Files.deleteIfExists(temporary)).void).use { temporary =>
      val permissions = IO.blocking {
        val view = Files.getFileAttributeView(target, classOf[PosixFileAttributeView], NOFOLLOW_LINKS)
        if view != null then
          try Files.setPosixFilePermissions(temporary, view.readAttributes().permissions())
          catch case _: NoSuchFileException => ()
      }
      write(temporary) *> writable(target) *> permissions *> IO.blocking {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      }.void
    }

  private def absolute(text: String): IO[Path] = IO {
    val path = Path.of(text)
    if !path.isAbsolute then throw InvalidFs("File paths must be absolute server paths")
    // Lexical normalization would change the meaning of paths containing a symlink followed by '..'.
    path
  }

  private def attributes(path: Path): IO[BasicFileAttributes] =
    IO.blocking(Files.readAttributes(path, classOf[BasicFileAttributes], NOFOLLOW_LINKS))

  private def regular(path: Path, followLinks: Boolean): IO[Unit] =
    IO.blocking(Files.readAttributes(path, classOf[BasicFileAttributes],
      (if followLinks then Array.empty[LinkOption] else Array(NOFOLLOW_LINKS))*))
      .flatMap(info => IO.raiseUnless(info.isRegularFile)(FsConflict(
        if info.isDirectory then "is_directory" else "not_regular_file", s"Not a regular file: $path")))

  private def writable(path: Path): IO[Unit] = regular(path, followLinks = false).recover { case _: NoSuchFileException => () }

  private def decode(body: String, encoding: FileEncoding): IO[Array[Byte]] = IO {
    val maxChars = if encoding == FileEncoding.base64 then ((maxBytes + 2) / 3) * 4 else maxBytes
    if body.length > maxChars then throw FsLimit("File write exceeds 1 MiB")
    val bytes = encoding match
      case FileEncoding.utf8 =>
        val encoded = UTF_8.newEncoder().encode(CharBuffer.wrap(body))
        val bytes = new Array[Byte](encoded.remaining())
        encoded.get(bytes)
        bytes
      case FileEncoding.base64 =>
        try Base64.getDecoder.decode(body)
        catch case _: IllegalArgumentException => throw InvalidFs("Invalid base64 file content")
    if bytes.length > maxBytes then throw FsLimit("File write exceeds 1 MiB")
    bytes
  }

  private def readChunks(path: Path): Stream[IO, Chunk[Byte]] =
    Stream.resource(Resource.fromAutoCloseable(IO.blocking(FileChannel.open(path, READ)))).flatMap { channel =>
      Stream.repeatEval(IO.interruptible {
        val buffer = ByteBuffer.allocate(chunkBytes)
        if channel.read(buffer) < 0 then None
        else Some(Chunk.byteBuffer(buffer.flip()))
      }).unNoneTerminate
    }

  private def writeChunks(path: Path, chunks: Stream[IO, Chunk[Byte]], options: OpenOption*): IO[Unit] =
    Resource.fromAutoCloseable(IO.blocking(FileChannel.open(path, (options :+ NOFOLLOW_LINKS) *))).use { channel =>
      chunks.evalMap { chunk =>
        val buffer = chunk.toByteBuffer
        def write: IO[Unit] = IO.defer {
          if buffer.hasRemaining then IO.interruptible(channel.write(buffer)) *> write else IO.unit
        }
        write
      }.compile.drain *> IO.interruptible(channel.force(true))
    }

object FsService:
  val maxBytes = 1024 * 1024
  val maxEntries = 1000
  private val chunkBytes = 64 * 1024

  private def kind(info: BasicFileAttributes): FileKind =
    if info.isSymbolicLink then FileKind.symlink
    else if info.isDirectory then FileKind.directory
    else if info.isRegularFile then FileKind.file
    else FileKind.other
