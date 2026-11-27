package groot.adb

import cats.effect.{Deferred, FiberIO, IO, Ref, Resource}
import cats.effect.std.{Queue, Semaphore}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import fs2.Chunk
import fs2.io.net.{Network, Socket}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets.{US_ASCII, UTF_8}
import java.time.Instant
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import scodec.bits.ByteVector

object AdbProtocol:
  val A_SYNC: Int = 0x434e5953 // "SYNC"
  val A_CNXN: Int = 0x4e584e43 // "CNXN"
  val A_OPEN: Int = 0x4e45504f // "OPEN"
  val A_OKAY: Int = 0x59414b4f // "OKAY"
  val A_CLSE: Int = 0x45534c43 // "CLSE"
  val A_WRTE: Int = 0x45545257 // "WRTE"
  val A_AUTH: Int = 0x48545541 // "AUTH"

  val A_VERSION: Int = 0x01000000
  val HOST_MAX_PAYLOAD: Int = 256 * 1024
  val HARD_MAX_FRAME_BYTES: Int = 4 * 1024 * 1024
  val HEADER_BYTES: Int = 24

  val AUTH_TOKEN: Int = 1
  val AUTH_SIGNATURE: Int = 2
  val AUTH_RSAPUBLICKEY: Int = 3

  final case class Packet(command: Int, arg0: Int, arg1: Int, payload: ByteVector = ByteVector.empty):
    def encode: ByteVector =
      val arr = payload.toArray
      var sum = 0
      var i = 0
      while i < arr.length do
        sum += (arr(i) & 0xff)
        i += 1
      val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
      header.putInt(command)
      header.putInt(arg0)
      header.putInt(arg1)
      header.putInt(arr.length)
      header.putInt(sum)
      header.putInt(command ^ 0xffffffff)
      ByteVector.view(header.array()) ++ payload

  def decodeHeader(bytes: ByteVector): Either[AdbFailure, (Int, Int, Int, Int, Int)] =
    if bytes.size != HEADER_BYTES then
      Left(AdbFailure("adb_protocol_error", s"Expected 24-byte ADB header, got ${bytes.size}"))
    else
      val buf = bytes.toByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
      val cmd = buf.getInt()
      val arg0 = buf.getInt()
      val arg1 = buf.getInt()
      val len = buf.getInt()
      val check = buf.getInt()
      val magic = buf.getInt()
      if magic != (cmd ^ 0xffffffff) then
        Left(AdbFailure("adb_protocol_error", f"Invalid ADB packet magic 0x$magic%08x for command 0x$cmd%08x"))
      else if len < 0 || len > HARD_MAX_FRAME_BYTES then
        Left(AdbFailure("adb_protocol_error", s"Invalid ADB payload length: $len"))
      else Right((cmd, arg0, arg1, len, check))

  def commandName(cmd: Int): String =
    val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd).array()
    if buf.forall(b => b >= 0x20 && b <= 0x7e) then new String(buf, US_ASCII)
    else f"0x$cmd%08x"

  private def readExactSocket(socket: Socket[IO], length: Int): IO[ByteVector] =
    if length == 0 then IO.pure(ByteVector.empty)
    else
      def loop(acc: ByteVector, remaining: Int): IO[ByteVector] =
        if remaining <= 0 then IO.pure(acc)
        else
          socket.read(remaining).flatMap {
            case Some(chunk) if chunk.nonEmpty =>
              loop(acc ++ chunk.toByteVector, remaining - chunk.size)
            case _ =>
              IO.raiseError(AdbFailure("adb_disconnected", "ADB TCP socket closed by peer"))
          }
      loop(ByteVector.empty, length)

  def readPacket(socket: Socket[IO]): IO[Packet] =
    for
      hdrBytes <- readExactSocket(socket, HEADER_BYTES)
      (cmd, arg0, arg1, len, _) <- IO.fromEither(decodeHeader(hdrBytes))
      payload <- readExactSocket(socket, len)
    yield Packet(cmd, arg0, arg1, payload)

  def writePacket(socket: Socket[IO], packet: Packet): IO[Unit] =
    socket.write(Chunk.byteVector(packet.encode))

  private def resolveSocketAddress(host: String, port: Int): IO[SocketAddress[Host]] =
    val parsedPort = Port.fromInt(port).getOrElse(throw AdbFailure("invalid_adb", s"Invalid port: $port"))
    IpAddress.fromString(host) match
      case Some(ip) => IO.pure(SocketAddress(ip, parsedPort))
      case None =>
        Host.fromString(host) match
          case Some(h) => IO.pure(SocketAddress(h, parsedPort))
          case None    => IO.raiseError(AdbFailure("invalid_adb", s"Invalid host: $host"))

  /** Establish a raw TCP connection to `<host>:<port>`, perform `CNXN` + RSA `AUTH`, and start the multiplexer loop. */
  def connect(
      host: String,
      port: Int,
      key: AdbKeyMaterial,
      connectTimeout: FiniteDuration,
      authTimeout: FiniteDuration
  ): IO[AdbConnection] =
    for
      addr <- resolveSocketAddress(host, port)
      allocated <- Network[IO].connect(addr).allocated.timeoutTo(
        connectTimeout,
        IO.raiseError(AdbFailure("adb_timeout", s"Timed out connecting to ADB daemon at $host:$port after $connectTimeout"))
      ).adaptError {
        case e: AdbFailure => e
        case NonFatal(e) =>
          AdbFailure("adb_unreachable", s"Cannot connect to ADB daemon at $host:$port: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
      }
      (socket, closeSocket) = allocated
      conn <- handshakeAndStart(s"$host:$port", socket, closeSocket, key, connectTimeout, authTimeout).onError { _ =>
        closeSocket.attempt.void
      }
    yield conn

  private def handshakeAndStart(
      address: String,
      socket: Socket[IO],
      closeSocket: IO[Unit],
      key: AdbKeyMaterial,
      connectTimeout: FiniteDuration,
      authTimeout: FiniteDuration
  ): IO[AdbConnection] =
    val hostBanner = ByteVector.view("host::groot\u0000".getBytes(US_ASCII))
    val cnxnOut = Packet(A_CNXN, A_VERSION, HOST_MAX_PAYLOAD, hostBanner)

    val negotiate: IO[(Int, Int, String)] =
      for
        _ <- writePacket(socket, cnxnOut).timeoutTo(
          connectTimeout,
          IO.raiseError(AdbFailure("adb_timeout", s"Timed out sending CNXN to $address"))
        )
        first <- readPacket(socket).timeoutTo(
          connectTimeout,
          IO.raiseError(AdbFailure("adb_timeout", s"Timed out waiting for CNXN/AUTH from $address"))
        )
        result <- first.command match
          case A_CNXN =>
            IO.pure(parseCnxn(first))
          case A_AUTH if first.arg0 == AUTH_TOKEN =>
            val sig = ByteVector.view(key.signToken(first.payload.toArray))
            for
              _ <- writePacket(socket, Packet(A_AUTH, AUTH_SIGNATURE, 0, sig))
              second <- readPacket(socket).timeoutTo(
                connectTimeout,
                IO.raiseError(AdbFailure("adb_timeout", s"Timed out after AUTH_SIGNATURE on $address"))
              )
              authed <- second.command match
                case A_CNXN =>
                  IO.pure(parseCnxn(second))
                case A_AUTH if second.arg0 == AUTH_TOKEN =>
                  val pubKeyBytes = ByteVector.view(key.publicKeyPayload)
                  writePacket(socket, Packet(A_AUTH, AUTH_RSAPUBLICKEY, 0, pubKeyBytes)) *>
                    readPacket(socket)
                      .adaptError {
                        case e: AdbFailure if e.code == "adb_disconnected" =>
                          AdbFailure(
                            "adb_unauthorized",
                            s"Device $address has not authorized this ADB key yet; accept the debugging prompt on screen and retry"
                          )
                      }
                      .timeoutTo(
                        authTimeout,
                        IO.raiseError(
                          AdbFailure(
                            "adb_unauthorized",
                            s"Timed out after $authTimeout waiting for on-screen ADB authorization on $address"
                          )
                        )
                      )
                      .flatMap {
                        case pkt if pkt.command == A_CNXN => IO.pure(parseCnxn(pkt))
                        case other =>
                          IO.raiseError(
                            AdbFailure("adb_unauthorized", s"ADB authorization rejected on $address (got ${commandName(other.command)})")
                          )
                      }
                case _ =>
                  IO.raiseError(
                    AdbFailure("adb_protocol_error", s"Unexpected packet ${commandName(second.command)} during ADB AUTH on $address")
                  )
            yield authed
          case other =>
            IO.raiseError(AdbFailure("adb_protocol_error", s"Unexpected initial ADB packet ${commandName(other)} from $address"))
      yield result

    for
      (protoVer, peerMaxData, banner) <- negotiate
      now <- IO.realTimeInstant
      writeLock <- Semaphore[IO](1)
      nextLocalId <- Ref.of[IO, Int](1)
      streamsRef <- Ref.of[IO, Map[Int, StreamSlot]](Map.empty)
      aliveRef <- Ref.of[IO, Boolean](true)
      lastUsedRef <- Ref.of[IO, Instant](now)
      readerFiberRef <- Deferred[IO, FiberIO[Unit]]
      conn = new AdbConnection(
        address = address,
        protocolVersion = protoVer,
        maxPayload = peerMaxData.max(1024).min(HOST_MAX_PAYLOAD),
        banner = banner,
        connectedAt = now,
        socket = socket,
        closeSocket = closeSocket,
        writeLock = writeLock,
        nextLocalId = nextLocalId,
        streamsRef = streamsRef,
        aliveRef = aliveRef,
        lastUsedRef = lastUsedRef,
        readerFiber = readerFiberRef
      )
      fiber <- conn.runReaderLoop.start
      _ <- readerFiberRef.complete(fiber)
    yield conn

  private def parseCnxn(pkt: Packet): (Int, Int, String) =
    val rawBanner = new String(pkt.payload.toArray, UTF_8).takeWhile(_ != '\u0000').trim
    val negotiatedMax = if pkt.arg1 > 0 then pkt.arg1 else 4096
    (pkt.arg0, negotiatedMax, rawBanner)

  final private[adb] class StreamSlot(
      val localId: Int,
      val openAck: Deferred[IO, Either[AdbFailure, Int]],
      val writeAckRef: Ref[IO, Option[Deferred[IO, Either[AdbFailure, Unit]]]],
      val remoteIdRef: Ref[IO, Int],
      val inbound: Queue[IO, Option[Either[AdbFailure, ByteVector]]],
      val closedRef: Ref[IO, Boolean]
  )

final class AdbStream private[adb] (
    val localId: Int,
    val remoteId: Int,
    val maxPayload: Int,
    conn: AdbConnection,
    slot: AdbProtocol.StreamSlot
):
  def readChunk: IO[Option[ByteVector]] =
    slot.inbound.take.flatMap {
      case None              => IO.pure(None)
      case Some(Right(data)) => IO.pure(Some(data))
      case Some(Left(err))   => IO.raiseError(err)
    }

  def readAll(maxBytes: Int): IO[(ByteVector, Boolean)] =
    def loop(acc: ByteVector, truncated: Boolean): IO[(ByteVector, Boolean)] =
      readChunk.flatMap {
        case None => IO.pure((acc, truncated))
        case Some(chunk) =>
          if truncated then loop(acc, true)
          else if acc.size + chunk.size <= maxBytes then loop(acc ++ chunk, false)
          else
            val keep = (maxBytes - acc.size.toInt).max(0)
            loop(acc ++ chunk.take(keep.toLong), true)
      }
    loop(ByteVector.empty, false)

  def write(bytes: ByteVector): IO[Unit] =
    if bytes.isEmpty then IO.unit
    else
      def sendFrames(remaining: ByteVector): IO[Unit] =
        if remaining.isEmpty then IO.unit
        else
          val (frame, rest) = remaining.splitAt(maxPayload.toLong)
          for
            closed <- slot.closedRef.get
            _ <- if closed then IO.raiseError(AdbFailure("adb_stream_closed", "ADB stream was closed by remote peer")) else IO.unit
            ack <- Deferred[IO, Either[AdbFailure, Unit]]
            _ <- slot.writeAckRef.set(Some(ack))
            _ <- conn.sendPacket(AdbProtocol.Packet(AdbProtocol.A_WRTE, localId, remoteId, frame))
            res <- ack.get
            _ <- IO.fromEither(res)
            _ <- sendFrames(rest)
          yield ()
      sendFrames(bytes)

final class AdbConnection private[adb] (
    val address: String,
    val protocolVersion: Int,
    val maxPayload: Int,
    val banner: String,
    val connectedAt: Instant,
    socket: Socket[IO],
    closeSocket: IO[Unit],
    writeLock: Semaphore[IO],
    nextLocalId: Ref[IO, Int],
    streamsRef: Ref[IO, Map[Int, AdbProtocol.StreamSlot]],
    aliveRef: Ref[IO, Boolean],
    lastUsedRef: Ref[IO, Instant],
    readerFiber: Deferred[IO, FiberIO[Unit]]
):
  import AdbProtocol.*

  def isAlive: IO[Boolean] = aliveRef.get
  def lastUsedAt: IO[Instant] = lastUsedRef.get
  def touch: IO[Unit] = IO.realTimeInstant.flatMap(lastUsedRef.set)

  private[adb] def sendPacket(pkt: Packet): IO[Unit] =
    touch *> writeLock.permit.use(_ => writePacket(socket, pkt)).adaptError {
      case e: AdbFailure => e
      case NonFatal(e) =>
        AdbFailure("adb_disconnected", s"Failed writing ADB packet to $address: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
    }

  def openStream(destination: String, openTimeout: FiniteDuration): Resource[IO, AdbStream] =
    Resource.makeFull[IO, (StreamSlot, Int)] { poll =>
      for
        alive <- aliveRef.get
        _ <- if !alive then IO.raiseError(AdbFailure("adb_disconnected", s"ADB connection to $address is closed")) else IO.unit
        localId <- nextLocalId.getAndUpdate(id => if id >= Int.MaxValue - 10 then 1 else id + 1)
        openAck <- Deferred[IO, Either[AdbFailure, Int]]
        writeAckRef <- Ref.of[IO, Option[Deferred[IO, Either[AdbFailure, Unit]]]](None)
        remoteIdRef <- Ref.of[IO, Int](0)
        inbound <- Queue.unbounded[IO, Option[Either[AdbFailure, ByteVector]]]
        closedRef <- Ref.of[IO, Boolean](false)
        slot = new StreamSlot(localId, openAck, writeAckRef, remoteIdRef, inbound, closedRef)
        _ <- streamsRef.update(_.updated(localId, slot))
        destBytes = ByteVector.view(destination.getBytes(UTF_8)) ++ ByteVector.low(1)
        remoteId <- poll(
          sendPacket(Packet(A_OPEN, localId, 0, destBytes)) *>
            openAck.get.timeoutTo(
              openTimeout,
              IO.raiseError(AdbFailure("adb_timeout", s"Timed out opening ADB stream '$destination' on $address"))
            ).flatMap(IO.fromEither)
        ).onError { _ =>
          cleanupLocalStream(slot)
        }
      yield (slot, remoteId)
    } { case (slot, _) =>
      cleanupLocalStream(slot)
    }.map { case (slot, remoteId) =>
      new AdbStream(slot.localId, remoteId, maxPayload, this, slot)
    }

  private def cleanupLocalStream(slot: StreamSlot): IO[Unit] =
    for
      removed <- streamsRef.modify(m => (m - slot.localId, m.contains(slot.localId)))
      wasClosed <- slot.closedRef.getAndSet(true)
      remoteId <- slot.remoteIdRef.get
      _ <-
        if removed && !wasClosed && remoteId != 0 then
          sendPacket(Packet(A_CLSE, slot.localId, remoteId)).attempt.void
        else IO.unit
    yield ()

  def close: IO[Unit] =
    for
      wasAlive <- aliveRef.getAndSet(false)
      _ <- if wasAlive then
        closeSocket.attempt *>
          readerFiber.tryGet.flatMap(_.traverse_(_.cancel)) *>
          failAllStreams(AdbFailure("adb_disconnected", s"ADB connection to $address closed"))
      else IO.unit
    yield ()

  private def failAllStreams(err: AdbFailure): IO[Unit] =
    streamsRef.getAndSet(Map.empty).flatMap { active =>
      active.values.toVector.traverse_ { slot =>
        slot.closedRef.set(true) *>
          slot.openAck.complete(Left(err)).void *>
          slot.writeAckRef.getAndSet(None).flatMap(_.traverse_(_.complete(Left(err)).void)) *>
          slot.inbound.offer(Some(Left(err))) *>
          slot.inbound.offer(None)
      }
    }

  private[adb] def runReaderLoop: IO[Unit] =
    def step: IO[Boolean] =
      readPacket(socket).attempt.flatMap {
        case Left(err) =>
          val failure = err match
            case f: AdbFailure => f
            case other =>
              AdbFailure("adb_disconnected", s"ADB connection $address lost: ${Option(other.getMessage).getOrElse(other.getClass.getSimpleName)}")
          aliveRef.set(false) *> failAllStreams(failure) *> closeSocket.attempt.as(false)
        case Right(pkt) =>
          touch *> dispatch(pkt).as(true)
      }

    def loop: IO[Unit] = step.flatMap(cont => if cont then loop else IO.unit)
    loop

  private def dispatch(pkt: Packet): IO[Unit] =
    pkt.command match
      case A_OKAY =>
        val remoteId = pkt.arg0
        val localId = pkt.arg1
        streamsRef.get.map(_.get(localId)).flatMap {
          case None => IO.unit
          case Some(slot) =>
            for
              prevRemote <- slot.remoteIdRef.modify(cur => if cur == 0 then (remoteId, 0) else (cur, cur))
              _ <- if prevRemote == 0 then slot.openAck.complete(Right(remoteId)).void else IO.unit
              pendingWrite <- slot.writeAckRef.getAndSet(None)
              _ <- pendingWrite.traverse_(_.complete(Right(())).void)
            yield ()
        }

      case A_WRTE =>
        val remoteId = pkt.arg0
        val localId = pkt.arg1
        streamsRef.get.map(_.get(localId)).flatMap {
          case None =>
            sendPacket(Packet(A_CLSE, localId, remoteId)).attempt.void
          case Some(slot) =>
            slot.remoteIdRef.update(cur => if cur == 0 then remoteId else cur) *>
              slot.openAck.complete(Right(remoteId)).void *>
              slot.inbound.offer(Some(Right(pkt.payload))) *>
              sendPacket(Packet(A_OKAY, localId, remoteId)).attempt.void
        }

      case A_CLSE =>
        val remoteId = pkt.arg0
        val localId = pkt.arg1
        streamsRef.modify(m => (m - localId, m.get(localId))).flatMap {
          case None => IO.unit
          case Some(slot) =>
            for
              alreadyClosed <- slot.closedRef.getAndSet(true)
              _ <- slot.openAck.complete(Left(AdbFailure("adb_stream_closed", s"Remote ADB service rejected or closed stream $localId"))).void
              pendingWrite <- slot.writeAckRef.getAndSet(None)
              _ <- pendingWrite.traverse_(_.complete(Left(AdbFailure("adb_stream_closed", "ADB stream closed during write"))).void)
              _ <- slot.inbound.offer(None)
              _ <- if !alreadyClosed && remoteId != 0 then sendPacket(Packet(A_CLSE, localId, remoteId)).attempt.void else IO.unit
            yield ()
        }

      case _ =>
        IO.unit
