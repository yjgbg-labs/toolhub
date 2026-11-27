package groot.adb

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port, SocketAddress}
import framework.rpcDsl.*
import fs2.Stream
import fs2.io.net.{Network, Socket}
import groot.config.{ApplicationConfig, ConfigLoader}
import java.io.ByteArrayOutputStream
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets.{US_ASCII, UTF_8}
import java.nio.file.{Files, Path}
import java.security.{MessageDigest, Signature}
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Uri}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scodec.bits.ByteVector

class AdbSuite extends CatsEffectSuite:

  private val tempDir: Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory("groot-adb-test-"))
  )(dir =>
    IO.blocking {
      if Files.exists(dir) then
        Using.resource(Files.walk(dir))(_.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists))
    }
  )

  private def samplePng(width: Int, height: Int): Array[Byte] =
    val out = new ByteArrayOutputStream()
    out.write(Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
    val ihdr = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN)
    ihdr.putInt(13)
    ihdr.put("IHDR".getBytes(US_ASCII))
    ihdr.putInt(width)
    ihdr.putInt(height)
    ihdr.put(Array[Byte](8, 6, 0, 0, 0))
    ihdr.putInt(0) // crc placeholder
    out.write(ihdr.array())
    out.toByteArray

  /** Minimal real binary ADB TCP server (`maxPayload = 4096`, matching Android 5.1 `adbd`) for deterministic wire testing. */
  private def mockAdbdServer(
      trustedPubKey: Option[RSAPublicKey],
      autoApproveUntrusted: Boolean = false,
      fsStoreRef: Ref[IO, Map[String, Array[Byte]]],
      commandsLogRef: Ref[IO, Vector[String]],
      screenOnRef: Ref[IO, Boolean],
      seenPubKeyRef: Ref[IO, Option[String]],
      serverSocketsRef: Ref[IO, Vector[Socket[IO]]]
  ): Resource[IO, Int] =
    val host = Host.fromString("127.0.0.1").get

    def handleClient(socket: Socket[IO]): IO[Unit] =
      val rawMessage = "groot-adb-auth-challenge-message".getBytes(UTF_8)
      val sha1Token = MessageDigest.getInstance("SHA-1").digest(rawMessage)
      val cnxnReply = AdbProtocol.Packet(
        AdbProtocol.A_CNXN,
        AdbProtocol.A_VERSION,
        4096, // Force 4 KiB maxPayload fragmentation like Android 5.1!
        ByteVector.view("device::ro.product.name=starwars;ro.product.model=MiTV-ANTL0;\u0000".getBytes(US_ASCII))
      )

      def verifySig(sigBytes: Array[Byte]): Boolean =
        trustedPubKey.exists { pub =>
          val verifier = Signature.getInstance("SHA1withRSA")
          verifier.initVerify(pub)
          verifier.update(rawMessage)
          verifier.verify(sigBytes)
        }

      def sendStreamOutput(localStreamId: Int, remoteClientStreamId: Int, data: ByteVector): IO[Unit] =
        def loop(rem: ByteVector): IO[Unit] =
          if rem.isEmpty then IO.unit
          else
            val (chunk, rest) = rem.splitAt(4096L)
            for
              _ <- AdbProtocol.writePacket(socket, AdbProtocol.Packet(AdbProtocol.A_WRTE, localStreamId, remoteClientStreamId, chunk))
              ack <- AdbProtocol.readPacket(socket)
              _ <-
                if ack.command == AdbProtocol.A_OKAY then loop(rest)
                else IO.unit
            yield ()
        loop(data) *>
          AdbProtocol.writePacket(socket, AdbProtocol.Packet(AdbProtocol.A_CLSE, localStreamId, remoteClientStreamId)) *>
          AdbProtocol.readPacket(socket).attempt.void

      def handleShellCommand(cmd: String): IO[ByteVector] =
        commandsLogRef.update(_ :+ cmd) *> {
          if cmd.startsWith("getprop ro.product.model") then
            IO.pure(ByteVector.view("MiTV-ANTL0\n5.1.1\n22\n".getBytes(UTF_8)))
          else if cmd.contains("__PROPS__") then
            screenOnRef.get.map { isOn =>
              val st = if isOn then "ON" else "OFF"
              val wk = if isOn then "Awake" else "Asleep"
              ByteVector.view(
                s"""__PROPS__
                   |ro.product.manufacturer=Xiaomi
                   |ro.product.brand=Xiaomi
                   |ro.product.model=MiTV-ANTL0
                   |ro.product.device=starwars
                   |ro.product.name=starwars
                   |ro.serialno=TV123456
                   |ro.build.version.release=5.1.1
                   |ro.build.version.sdk=22
                   |__POWER__
                   |  mWakefulness=$wk
                   |  Display Power: state=$st
                   |__DISPLAY__
                   |  mScreenState=$st
                   |__UPTIME__
                   |up time: 01:23:45
                   |""".stripMargin.getBytes(UTF_8)
              )
            }
          else if cmd.startsWith("dumpsys power") then
            screenOnRef.get.map { isOn =>
              val st = if isOn then "ON" else "OFF"
              val wk = if isOn then "Awake" else "Asleep"
              ByteVector.view(s"mWakefulness=$wk\nDisplay Power: state=$st\nmScreenState=$st\n".getBytes(UTF_8))
            }
          else if cmd == "input keyevent 223" then
            screenOnRef.set(false).as(ByteVector.empty)
          else if cmd == "input keyevent 224" then
            screenOnRef.set(true).as(ByteVector.empty)
          else if cmd.startsWith("screencap -p ") then
            val targetPath = cmd.stripPrefix("screencap -p ").trim
            fsStoreRef.update(_.updated(targetPath, samplePng(1920, 1080))).as(ByteVector.empty)
          else if cmd.startsWith("rm -f ") then
            val targetPath = cmd.stripPrefix("rm -f ").trim
            fsStoreRef.update(_ - targetPath).as(ByteVector.empty)
          else if cmd.startsWith("pm install ") then
            IO.pure(ByteVector.view("pkg: /data/local/tmp/test.apk\nSuccess\n".getBytes(UTF_8)))
          else if cmd.startsWith("pm uninstall ") then
            IO.pure(ByteVector.view("Success\n".getBytes(UTF_8)))
          else
            IO.pure(ByteVector.view(s"ran:$cmd".getBytes(UTF_8)))
        }

      def handleSyncSession(serverStreamId: Int, clientStreamId: Int): IO[Unit] =
        var buf = ByteVector.empty
        def readSyncBytes(n: Int): IO[Option[ByteVector]] =
          if buf.size >= n then
            val (out, rest) = buf.splitAt(n.toLong)
            buf = rest
            IO.pure(Some(out))
          else
            AdbProtocol.readPacket(socket).flatMap {
              case pkt if pkt.command == AdbProtocol.A_WRTE =>
                buf = buf ++ pkt.payload
                AdbProtocol.writePacket(socket, AdbProtocol.Packet(AdbProtocol.A_OKAY, serverStreamId, clientStreamId)) *>
                  readSyncBytes(n)
              case pkt if pkt.command == AdbProtocol.A_CLSE =>
                AdbProtocol.writePacket(socket, AdbProtocol.Packet(AdbProtocol.A_CLSE, serverStreamId, clientStreamId)).as(None)
              case _ =>
                IO.pure(None)
            }

        def sendSyncBytes(data: ByteVector): IO[Unit] =
          def loop(rem: ByteVector): IO[Unit] =
            if rem.isEmpty then IO.unit
            else
              val (chunk, rest) = rem.splitAt(4096L)
              AdbProtocol.writePacket(socket, AdbProtocol.Packet(AdbProtocol.A_WRTE, serverStreamId, clientStreamId, chunk)) *>
                AdbProtocol.readPacket(socket).flatMap { ack =>
                  if ack.command == AdbProtocol.A_OKAY then loop(rest) else IO.unit
                }
          loop(data)

        def syncLoop: IO[Unit] =
          readSyncBytes(8).flatMap {
            case None => IO.unit
            case Some(hdr) =>
              val hb = hdr.toByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
              val id = hb.getInt()
              val argLen = hb.getInt()
              if id == AdbSync.ID_QUIT then
                AdbProtocol.writePacket(socket, AdbProtocol.Packet(AdbProtocol.A_CLSE, serverStreamId, clientStreamId)) *>
                  AdbProtocol.readPacket(socket).attempt.void
              else
                readSyncBytes(argLen).flatMap {
                  case None => IO.unit
                  case Some(pathBytes) =>
                    val pathStr = new String(pathBytes.toArray, UTF_8)
                    if id == AdbSync.ID_STAT then
                      fsStoreRef.get.flatMap { store =>
                        val resp = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                        resp.putInt(AdbSync.ID_STAT)
                        store.get(pathStr) match
                          case Some(bytes) =>
                            resp.putInt(0x81a4) // regular file 0644
                            resp.putInt(bytes.length)
                            resp.putInt(1700000000)
                          case None =>
                            val isDir = pathStr == "/data/local/tmp" || store.keys.exists(_.startsWith(pathStr.stripSuffix("/") + "/"))
                            if isDir then
                              resp.putInt(0x41ed) // dir 0755
                              resp.putInt(4096)
                              resp.putInt(1700000000)
                            else
                              resp.putInt(0)
                              resp.putInt(0)
                              resp.putInt(0)
                        sendSyncBytes(ByteVector.view(resp.array())) *> syncLoop
                      }
                    else if id == AdbSync.ID_LIST then
                      fsStoreRef.get.flatMap { store =>
                        val prefix = pathStr.stripSuffix("/") + "/"
                        val children = store.collect {
                          case (k, v) if k.startsWith(prefix) && !k.substring(prefix.length).contains('/') =>
                            (k.substring(prefix.length), v.length)
                        }.toVector
                        val out = new ByteArrayOutputStream()
                        children.foreach { (name, sz) =>
                          val nb = name.getBytes(UTF_8)
                          val dent = ByteBuffer.allocate(20 + nb.length).order(ByteOrder.LITTLE_ENDIAN)
                          dent.putInt(AdbSync.ID_DENT)
                          dent.putInt(0x81a4)
                          dent.putInt(sz)
                          dent.putInt(1700000000)
                          dent.putInt(nb.length)
                          dent.put(nb)
                          out.write(dent.array())
                        }
                        val done = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                        done.putInt(AdbSync.ID_DONE)
                        out.write(done.array())
                        sendSyncBytes(ByteVector.view(out.toByteArray)) *> syncLoop
                      }
                    else if id == AdbSync.ID_RECV then
                      fsStoreRef.get.flatMap { store =>
                        store.get(pathStr) match
                          case Some(bytes) =>
                            val out = new ByteArrayOutputStream()
                            var off = 0
                            while off < bytes.length do
                              val n = (bytes.length - off).min(5000)
                              val dh = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                              dh.putInt(AdbSync.ID_DATA)
                              dh.putInt(n)
                              out.write(dh.array())
                              out.write(bytes, off, n)
                              off += n
                            val done = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                            done.putInt(AdbSync.ID_DONE)
                            done.putInt(0)
                            out.write(done.array())
                            sendSyncBytes(ByteVector.view(out.toByteArray)) *> syncLoop
                          case None =>
                            val msg = "No such file or directory".getBytes(UTF_8)
                            val fail = ByteBuffer.allocate(8 + msg.length).order(ByteOrder.LITTLE_ENDIAN)
                            fail.putInt(AdbSync.ID_FAIL)
                            fail.putInt(msg.length)
                            fail.put(msg)
                            sendSyncBytes(ByteVector.view(fail.array())) *> syncLoop
                      }
                    else if id == AdbSync.ID_SEND then
                      val remoteTarget = pathStr.takeWhile(_ != ',')
                      val fileAcc = new ByteArrayOutputStream()
                      def recvChunks: IO[Unit] =
                        readSyncBytes(8).flatMap {
                          case None => IO.unit
                          case Some(chHdr) =>
                            val cb = chHdr.toByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            val cid = cb.getInt()
                            val clen = cb.getInt()
                            if cid == AdbSync.ID_DATA then
                              readSyncBytes(clen).flatMap {
                                case Some(d) => IO(fileAcc.write(d.toArray)) *> recvChunks
                                case None    => IO.unit
                              }
                            else if cid == AdbSync.ID_DONE then
                              fsStoreRef.update(_.updated(remoteTarget, fileAcc.toByteArray)) *> {
                                val ok = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                                ok.putInt(AdbSync.ID_OKAY)
                                ok.putInt(0)
                                sendSyncBytes(ByteVector.view(ok.array()))
                              }
                            else IO.unit
                        }
                      recvChunks *> syncLoop
                    else IO.unit
                }
          }

        syncLoop

      def sessionLoop(nextServerStreamId: Int): IO[Unit] =
        AdbProtocol.readPacket(socket).attempt.flatMap {
          case Left(_) => IO.unit
          case Right(pkt) if pkt.command == AdbProtocol.A_OPEN =>
            val clientStreamId = pkt.arg0
            val dest = new String(pkt.payload.toArray, UTF_8).takeWhile(_ != '\u0000')
            val sid = nextServerStreamId
            for
              _ <- AdbProtocol.writePacket(socket, AdbProtocol.Packet(AdbProtocol.A_OKAY, sid, clientStreamId))
              _ <-
                if dest.startsWith("shell:") then
                  handleShellCommand(dest.stripPrefix("shell:")).flatMap(out => sendStreamOutput(sid, clientStreamId, out))
                else if dest == "sync:" then
                  handleSyncSession(sid, clientStreamId)
                else if dest.startsWith("reboot:") then
                  AdbProtocol.writePacket(socket, AdbProtocol.Packet(AdbProtocol.A_CLSE, sid, clientStreamId))
                else
                  AdbProtocol.writePacket(socket, AdbProtocol.Packet(AdbProtocol.A_CLSE, 0, clientStreamId))
              _ <- sessionLoop(sid + 1)
            yield ()
          case Right(_) =>
            sessionLoop(nextServerStreamId)
        }

      serverSocketsRef.update(_ :+ socket) *> (for
        cnxn <- AdbProtocol.readPacket(socket)
        _ = assertEquals(cnxn.command, AdbProtocol.A_CNXN)
        _ <- AdbProtocol.writePacket(socket, AdbProtocol.Packet(AdbProtocol.A_AUTH, AdbProtocol.AUTH_TOKEN, 0, ByteVector.view(sha1Token)))
        authResp <- AdbProtocol.readPacket(socket)
        _ = assertEquals(authResp.command, AdbProtocol.A_AUTH)
        _ = assertEquals(authResp.arg0, AdbProtocol.AUTH_SIGNATURE)
        _ <-
          if verifySig(authResp.payload.toArray) then
            AdbProtocol.writePacket(socket, cnxnReply) *> sessionLoop(100)
          else
            for
              _ <- AdbProtocol.writePacket(socket, AdbProtocol.Packet(AdbProtocol.A_AUTH, AdbProtocol.AUTH_TOKEN, 0, ByteVector.view(sha1Token)))
              pubPktOpt <- AdbProtocol.readPacket(socket).attempt
              _ <- pubPktOpt match
                case Right(pubPkt) if pubPkt.command == AdbProtocol.A_AUTH && pubPkt.arg0 == AdbProtocol.AUTH_RSAPUBLICKEY =>
                  val arr = pubPkt.payload.toArray
                  assert(arr.nonEmpty && arr.last == 0.toByte, "AUTH_RSAPUBLICKEY must be NUL-terminated")
                  val str = new String(arr.dropRight(1), US_ASCII)
                  seenPubKeyRef.set(Some(str)) *> (
                    if autoApproveUntrusted then AdbProtocol.writePacket(socket, cnxnReply) *> sessionLoop(100)
                    else IO.sleep(200.millis)
                  )
                case _ => IO.unit
            yield ()
      yield ())

    for
      serverSocketGroup <- Network[IO].bind(SocketAddress(host, Port.fromInt(0).getOrElse(sys.error("port 0"))))
      _ <- serverSocketGroup.accept
        .map(sock => Stream.exec(handleClient(sock).attempt.void))
        .parJoinUnbounded
        .compile
        .drain
        .background
    yield serverSocketGroup.address.asIpUnsafe.port.value

  test("AdbCrypto generates 2048-bit PKCS#8 keys, verifies SHA-1 DigestInfo signatures, and encodes 524-byte Android RSAPublicKey structs") {
    tempDir.use { dir =>
      val keyPath = dir.resolve("adb/adbkey")
      for
        generated <- AdbCrypto.loadOrCreate(dir, keyPath.toString)
        reloaded <- AdbCrypto.loadOrCreate(dir, keyPath.toString)
        _ = assertEquals(generated.fingerprintSha256, reloaded.fingerprintSha256)
        _ = assert(Files.isRegularFile(keyPath))
        _ = assert(Files.isRegularFile(Path.of(keyPath.toString + ".pub")))

        // Verify SHA-1 DigestInfo PKCS#1 v1.5 signature matches standard JCA SHA1withRSA
        msg = "adb-token-verification-payload".getBytes(UTF_8)
        sha1 = MessageDigest.getInstance("SHA-1").digest(msg)
        sigBytes = generated.signToken(sha1)
        _ = assertEquals(sigBytes.length, 256)
        verifier = {
          val v = Signature.getInstance("SHA1withRSA")
          v.initVerify(generated.publicKey)
          v.update(msg)
          v
        }
        _ = assert(verifier.verify(sigBytes), "Signature produced by AdbKeyMaterial.signToken must verify under SHA1withRSA")

        // Verify 524-byte Android RSAPublicKey struct mathematical invariants
        parts = generated.androidPublicKeyLine.split(" ")
        _ = assertEquals(parts.length, 2)
        rawStruct = Base64.getDecoder.decode(parts(0))
        _ = assertEquals(rawStruct.length, 524)
        bb = ByteBuffer.wrap(rawStruct).order(ByteOrder.LITTLE_ENDIAN)
        words = bb.getInt()
        n0inv = Integer.toUnsignedLong(bb.getInt())
        modLE = { val a = new Array[Byte](256); bb.get(a); a }
        rrLE = { val a = new Array[Byte](256); bb.get(a); a }
        exp = bb.getInt()
        _ = assertEquals(words, 64)
        _ = assertEquals(exp, 65537)
        modDecoded = BigInt(1, modLE.reverse)
        rrDecoded = BigInt(1, rrLE.reverse)
        _ = assertEquals(modDecoded, BigInt(1, generated.publicKey.getModulus.toByteArray))
        r32 = BigInt(1) << 32
        _ = assertEquals(((modDecoded.mod(r32) * BigInt(n0inv)) + 1).mod(r32), BigInt(0))
        expectedRr = (BigInt(1) << 2048).modPow(BigInt(2), modDecoded)
        _ = assertEquals(rrDecoded, expectedRr)
      yield ()
    }
  }

  test("AdbConfig parses HOCON aliases and rejects invalid endpoints") {
    val valid = ConfigLoader.parseHocon[ApplicationConfig](
      """adb {
        |  targets {
        |    xiaomitv = "10.0.0.209:5555"
        |    box = "10.0.0.210"
        |  }
        |  connectTimeout = 3s
        |  maxOutputBytes = 8MiB
        |}
        |""".stripMargin,
      ApplicationConfig()
    )
    assert(valid.isRight, s"Expected Right, got $valid")
    val cfg = valid.toOption.get.adb
    assertEquals(cfg.targets("xiaomitv"), "10.0.0.209:5555")
    assertEquals(cfg.maxOutputBytes, 8 * 1024 * 1024)

    val invalidPort = ConfigLoader.parseHocon[ApplicationConfig](
      """adb.targets.xiaomitv = "10.0.0.209:99999" """,
      ApplicationConfig()
    )
    assert(invalidPort.isLeft)
  }

  test("End-to-end native ADB wire protocol: explicit target enforcement, auth, shell, screen, keyevent safety, sync push/pull/ls/stat (>4KiB fragmentation), screencap, install & reconnect") {
    tempDir.use { dir =>
      val keyFile = dir.resolve("adb/adbkey")
      for
        keyMat <- AdbCrypto.loadOrCreate(dir, keyFile.toString)
        fsStoreRef <- Ref.of[IO, Map[String, Array[Byte]]](Map("/data/local/tmp/hello.txt" -> "Hello ADB".getBytes(UTF_8)))
        commandsRef <- Ref.of[IO, Vector[String]](Vector.empty)
        screenOnRef <- Ref.of[IO, Boolean](false)
        seenPubKeyRef <- Ref.of[IO, Option[String]](None)
        socketsRef <- Ref.of[IO, Vector[Socket[IO]]](Vector.empty)
        _ <- mockAdbdServer(
          trustedPubKey = Some(keyMat.publicKey),
          fsStoreRef = fsStoreRef,
          commandsLogRef = commandsRef,
          screenOnRef = screenOnRef,
          seenPubKeyRef = seenPubKeyRef,
          serverSocketsRef = socketsRef
        ).use { port =>
          val adbCfg = AdbConfig(
            targets = Map("xiaomitv" -> s"127.0.0.1:$port"),
            keyFile = keyFile.toString,
            connectTimeout = "3s",
            commandTimeout = "5s"
          )
          AdbService.resource(dir, IO.pure(adbCfg)).use { svc =>
            val localPayload = dir.resolve("big.bin")
            val pulledFile = dir.resolve("pulled.bin")
            val capFile = dir.resolve("tv.png")
            val sampleApk = dir.resolve("sample.apk")
            // 19,999 bytes crosses multiple 4096-byte Android 5.1 WRTE frames!
            val bigBytes = Array.tabulate[Byte](19999)(i => ((i * 31 + 7) & 0xff).toByte)
            for
              _ <- IO.blocking {
                Files.write(localPayload, bigBytes)
                Files.write(sampleApk, "fake-apk-bytes".getBytes(UTF_8))
              }
              // Even with a single configured target alias, blank target must be rejected
              missingTargetErr <- svc.info("   ").attempt
              _ = assert(missingTargetErr.left.exists(_.asInstanceOf[AdbFailure].code == "invalid_adb"))

              info0 <- svc.info("xiaomitv")
              _ = assertEquals(info0.alias, Some("xiaomitv"))
              _ = assertEquals(info0.maxPayload, 4096)
              _ = assertEquals(info0.model, Some("MiTV-ANTL0"))
              _ = assertEquals(info0.sdkInt, Some(22))
              _ = assertEquals(info0.screenState, "OFF")
              _ = assertEquals(info0.device, Some("starwars"))

              devs <- svc.devices
              _ = assertEquals(devs.devices.size, 1)
              _ = assertEquals(devs.devices.head.connected, true)

              // Screen toggle OFF -> ON (keyevent 224)
              scrOn <- svc.screen(ScreenRequest(target = "xiaomitv", mode = ScreenAction.on))
              _ = assertEquals(scrOn.previousState, "OFF")
              _ = assertEquals(scrOn.currentState, "ON")
              _ = assertEquals(scrOn.changed, true)

              // Safety guard blocks KEYCODE_POWER (26) by default
              blockedPower <- svc.keyevent(KeyEventRequest(target = "xiaomitv", keys = Vector("POWER"))).attempt
              _ = assert(blockedPower.left.exists(_.asInstanceOf[AdbFailure].code == "invalid_adb"))

              // Named keyevents work cleanly
              keyOk <- svc.keyevent(KeyEventRequest(target = "xiaomitv", keys = Vector("HOME", "DPAD_DOWN")))
              _ = assertEquals(keyOk.resolvedKeyCodes, Vector(3, 20))

              // Input text escaping
              _ <- svc.input(InputRequest(target = "xiaomitv", kind = InputKind.text, text = Some("hello world's")))
              loggedAfterInput <- commandsRef.get
              _ = assert(loggedAfterInput.last.contains("hello%sworld'\\''s"), s"Unexpected escaped cmd: ${loggedAfterInput.last}")

              // Binary sync PUSH (>4 KiB) + STAT + LS + PULL (to file & inline Base64)
              pushed <- svc.push(PushRequest(target = "xiaomitv", localPath = localPayload.toString, remotePath = "/data/local/tmp/big.bin"))
              _ = assertEquals(pushed.sizeBytes, 19999L)

              st <- svc.stat("xiaomitv", "/data/local/tmp/big.bin")
              _ = assertEquals(st.exists, true)
              _ = assertEquals(st.sizeBytes, 19999L)
              _ = assertEquals(st.isRegularFile, true)

              listing <- svc.ls("xiaomitv", "/data/local/tmp")
              _ = assertEquals(listing.entries.map(_.name), Vector("big.bin", "hello.txt"))

              pulledDisk <- svc.pull(PullRequest(target = "xiaomitv", remotePath = "/data/local/tmp/big.bin", localPath = Some(pulledFile.toString)))
              _ = assertEquals(pulledDisk.sizeBytes, 19999L)
              pulledBytes <- IO.blocking(Files.readAllBytes(pulledFile))
              _ = assert(java.util.Arrays.equals(bigBytes, pulledBytes), "Pulled binary file must match pushed bytes")

              pulledInline <- svc.pull(PullRequest(target = "xiaomitv", remotePath = "/data/local/tmp/hello.txt"))
              _ = assertEquals(new String(Base64.getDecoder.decode(pulledInline.contentBase64.get), UTF_8), "Hello ADB")

              // Screencap binary PNG + dimension extraction
              cap <- svc.screencap(ScreencapRequest(target = "xiaomitv", outputPath = Some(capFile.toString), includeBase64 = true))
              _ = assertEquals(cap.width, Some(1920))
              _ = assertEquals(cap.height, Some(1080))
              _ = assert(Files.isRegularFile(capFile))

              // APK install & uninstall
              inst <- svc.install(InstallRequest(target = "xiaomitv", apkPath = sampleApk.toString))
              _ = assert(inst.output.contains("Success"))
              uninst <- svc.uninstall(UninstallRequest(target = "xiaomitv", packageName = "lab.yjgbg.sample"))
              _ = assertEquals(uninst.output, "Success")

              // Simulate remote socket drop and verify transparent reconnect on next call!
              socks <- socketsRef.get
              _ <- socks.traverse_(_.endOfOutput.attempt.void)
              _ <- IO.sleep(100.millis)
              afterDrop <- svc.exec(ExecRequest(target = "xiaomitv", command = "echo alive"))
              _ = assertEquals(afterDrop.output, "ran:echo alive")

              // HTTP Routes + OpenAPI contract check
              app = httpApp {
                openapi {
                  endpoint("/openapi.json")
                  info {
                    title("AdbTest")
                    version("1.0.0")
                  }
                }
                AdbRoutes.register(svc)
              }
              httpResp <- app.httpApp.run(
                Request[IO](Method.GET, Uri.unsafeFromString("/adb/devices"))
              )
              _ = assertEquals(httpResp.status.code, 200)
              scrHttpResp <- app.httpApp.run(
                Request[IO](Method.GET, Uri.unsafeFromString("/adb/screen?target=xiaomitv&mode=status"))
              )
              _ = assertEquals(scrHttpResp.status.code, 200)
              openApiResp <- app.httpApp.run(
                Request[IO](Method.GET, Uri.unsafeFromString("/openapi.json"))
              )
              _ = assertEquals(openApiResp.status.code, 200)
              openApiBody <- openApiResp.as[String]
              _ = assert(openApiBody.contains("\"adb.info\""))
              _ = assert(openApiBody.contains("\"adb.screencap\""))
              _ = assert(!openApiBody.contains("\"adb.connect\""))
              _ = assert(!openApiBody.contains("\"adb.disconnect\""))
              _ = assert(!openApiBody.contains("defaultTarget"))
              _ = assert(!openApiBody.contains("setDefault"))
            yield ()
          }
        }
      yield ()
    }
  }

  test("Live Android TV / ADB device verification (runs when ADB_LIVE_TARGET is set)") {
    sys.env.get("ADB_LIVE_TARGET").map(_.trim).filter(_.nonEmpty) match
      case None => IO.unit
      case Some(liveTarget) =>
        tempDir.use { dir =>
          val cfg = AdbConfig(targets = Map("xiaomitv" -> liveTarget), connectTimeout = "5s", commandTimeout = "15s")
          AdbService.resource(dir, IO.pure(cfg)).use { svc =>
            val localTmp = dir.resolve("live-probe.txt")
            val remoteTmp = "/data/local/tmp/.groot_adb_live_test.txt"
            val payload = s"groot-native-adb-${System.currentTimeMillis()}"
            for
              devInfo <- svc.info("xiaomitv")
              _ = assert(devInfo.maxPayload >= 4096, s"Unexpected maxPayload: ${devInfo.maxPayload}")
              _ = assert(devInfo.model.exists(_.nonEmpty), s"Expected non-empty model in $devInfo")
              _ <- IO.blocking(Files.writeString(localTmp, payload, UTF_8))
              _ <- (for
                pushed <- svc.push(PushRequest(target = "xiaomitv", localPath = localTmp.toString, remotePath = remoteTmp))
                _ = assertEquals(pushed.sizeBytes, payload.getBytes(UTF_8).length.toLong)
                st <- svc.stat("xiaomitv", remoteTmp)
                _ = assertEquals(st.exists, true)
                pulled <- svc.pull(PullRequest(target = "xiaomitv", remotePath = remoteTmp))
                decoded = new String(Base64.getDecoder.decode(pulled.contentBase64.get), UTF_8)
                _ = assertEquals(decoded, payload)
              yield ()).guarantee(
                svc.exec(ExecRequest(target = "xiaomitv", command = s"rm -f $remoteTmp")).attempt.void
              )
              cap <- svc.screencap(ScreencapRequest(target = "xiaomitv", includeBase64 = true))
              _ = assert(cap.width.exists(_ > 0) && cap.height.exists(_ > 0), s"Invalid screencap dimensions: $cap")
              _ = println(s"[Live ADB Verified] ${devInfo.address} model=${devInfo.model.getOrElse("?")} sdk=${devInfo.sdkInt.getOrElse(0)} maxPayload=${devInfo.maxPayload} screen=${devInfo.screenState} screencap=${cap.width.getOrElse(0)}x${cap.height.getOrElse(0)} (${cap.sizeBytes} bytes)")
            yield ()
          }
        }
  }

  test("Untrusted key sends AUTH_RSAPUBLICKEY and supports both rejection (401) and on-screen approval") {
    tempDir.use { dir =>
      val keyFile = dir.resolve("adb/adbkey")
      for
        _ <- AdbCrypto.loadOrCreate(dir, keyFile.toString)
        fsStoreRef <- Ref.of[IO, Map[String, Array[Byte]]](Map.empty)
        commandsRef <- Ref.of[IO, Vector[String]](Vector.empty)
        screenOnRef <- Ref.of[IO, Boolean](true)
        seenPubKeyRef <- Ref.of[IO, Option[String]](None)
        socketsRef <- Ref.of[IO, Vector[Socket[IO]]](Vector.empty)
        // Server 1: does not approve -> client gets 401 adb_unauthorized after sending public key
        _ <- mockAdbdServer(
          trustedPubKey = None,
          autoApproveUntrusted = false,
          fsStoreRef = fsStoreRef,
          commandsLogRef = commandsRef,
          screenOnRef = screenOnRef,
          seenPubKeyRef = seenPubKeyRef,
          serverSocketsRef = socketsRef
        ).use { port =>
          val cfg = AdbConfig(keyFile = keyFile.toString, connectTimeout = "2s", authTimeout = "1s")
          AdbService.resource(dir, IO.pure(cfg)).use { svc =>
            for
              err <- svc.info(s"127.0.0.1:$port").attempt
              _ = assert(err.left.exists(_.asInstanceOf[AdbFailure].code == "adb_unauthorized"))
              sentKey <- seenPubKeyRef.get
              _ = assert(sentKey.exists(_.endsWith("groot@localhost")), s"Expected public key upload, got $sentKey")
            yield ()
          }
        }
        // Server 2: approves on screen after receiving AUTH_RSAPUBLICKEY
        _ <- mockAdbdServer(
          trustedPubKey = None,
          autoApproveUntrusted = true,
          fsStoreRef = fsStoreRef,
          commandsLogRef = commandsRef,
          screenOnRef = screenOnRef,
          seenPubKeyRef = seenPubKeyRef,
          serverSocketsRef = socketsRef
        ).use { port =>
          val cfg = AdbConfig(keyFile = keyFile.toString, connectTimeout = "2s", authTimeout = "3s")
          AdbService.resource(dir, IO.pure(cfg)).use { svc =>
            for
              ok <- svc.info(s"127.0.0.1:$port")
              _ = assertEquals(ok.model, Some("MiTV-ANTL0"))
            yield ()
          }
        }
      yield ()
    }
  }
