package groot.adb

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import framework.validation.Contracts
import fs2.Stream
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.util.Base64
import java.util.Locale
import scala.concurrent.duration.*
import scodec.bits.ByteVector

final class AdbService private (
    grootHome: Path,
    config: IO[AdbConfig],
    poolLock: Semaphore[IO],
    poolRef: Ref[IO, Map[String, AdbConnection]]
):
  private val inlinePullMaxBytes = 256 * 1024

  private def resolveEndpoint(rawTarget: String, cfg: AdbConfig): IO[(String, Option[String])] =
    val token = Option(rawTarget).map(_.trim).getOrElse("")
    if token.isEmpty then
      IO.raiseError(AdbFailure("invalid_adb", "target must be specified (configured alias or host[:port])"))
    else
      val resolved: Either[AdbFailure, (String, Option[String])] =
        cfg.targets.get(token) match
          case Some(mapped) =>
            AdbValidation.parseHostPort(mapped) match
              case Right((h, p)) => Right((s"$h:$p", Some(token)))
              case Left(err)     => Left(AdbFailure("invalid_adb", s"Invalid endpoint for alias '$token': $err"))
          case None =>
            AdbValidation.parseHostPort(token) match
              case Right((h, p)) =>
                val addr = s"$h:$p"
                val matchingAlias = cfg.targets.collectFirst {
                  case (alias, v) if AdbValidation.parseHostPort(v).contains((h, p)) => alias
                }
                Right((addr, matchingAlias))
              case Left(err) =>
                Left(AdbFailure("invalid_adb", s"Unknown ADB alias or invalid endpoint '$token': $err"))
      IO.fromEither(resolved)

  private def acquireConnection(
      rawTarget: String,
      waitForAuth: Boolean = false,
      forceReconnect: Boolean = false
  ): IO[(AdbConnection, Option[String], AdbConfig)] =
    for
      cfg <- config
      (address, alias) <- resolveEndpoint(rawTarget, cfg)
      (host, port) <- IO.fromEither(AdbValidation.parseHostPort(address).left.map(err => AdbFailure("invalid_adb", err)))
      key <- AdbCrypto.loadOrCreate(grootHome, cfg.keyFile)
      connTimeout = Contracts.parseDuration(cfg.connectTimeout)
      authTimeout = Contracts.parseDuration(cfg.authTimeout)
      conn <- poolLock.permit.use { _ =>
        for
          existingOpt <- poolRef.get.map(_.get(address))
          aliveExisting <- existingOpt match
            case Some(c) if !forceReconnect =>
              c.isAlive.flatMap(ok => if ok then IO.pure(Some(c)) else c.close.attempt.as(None))
            case Some(c) =>
              c.close.attempt *> poolRef.update(_ - address).as(None)
            case None =>
              IO.pure(None)
          active <- aliveExisting match
            case Some(c) => c.touch.as(c)
            case None =>
              AdbProtocol.connect(host, port, key, connTimeout, authTimeout, waitForAuth).flatTap { fresh =>
                poolRef.update(_.updated(address, fresh))
              }
        yield active
      }
    yield (conn, alias, cfg)

  private def withConnection[A](rawTarget: String)(op: (AdbConnection, Option[String], AdbConfig) => IO[A]): IO[A] =
    acquireConnection(rawTarget).flatMap { (conn, alias, cfg) =>
      op(conn, alias, cfg).recoverWith {
        case e: AdbFailure if e.code == "adb_disconnected" =>
          // Reconnect once transparently if an idle pooled socket was dropped by the device
          acquireConnection(rawTarget, forceReconnect = true).flatMap { (fresh, freshAlias, freshCfg) =>
            op(fresh, freshAlias, freshCfg)
          }
      }
    }

  private def runRawShell(
      conn: AdbConnection,
      command: String,
      cmdTimeout: FiniteDuration,
      openTimeout: FiniteDuration,
      maxBytes: Int
  ): IO[(ByteVector, Boolean, Long)] =
    val cleanCmd = Option(command).getOrElse("")
    if cleanCmd.trim.isEmpty || cleanCmd.contains('\u0000') then
      IO.raiseError(AdbFailure("invalid_adb", "ADB command must be non-empty and must not contain NUL bytes"))
    else
      for
        t0 <- IO.monotonic
        res <- conn
          .openStream(s"shell:$cleanCmd", openTimeout)
          .use(_.readAll(maxBytes))
          .timeoutTo(
            cmdTimeout,
            IO.raiseError(AdbFailure("adb_timeout", s"ADB command timed out after $cmdTimeout on ${conn.address}"))
          )
        t1 <- IO.monotonic
      yield (res._1, res._2, (t1 - t0).toMillis)

  def devices: IO[AdbDevicesResult] =
    for
      cfg <- config
      key <- AdbCrypto.loadOrCreate(grootHome, cfg.keyFile)
      pooled <- poolRef.get
      alivePairs <- pooled.toVector.traverse { (addr, conn) =>
        for
          alive <- conn.isAlive
          last <- conn.lastUsedAt
        yield (addr, conn, alive, last)
      }
      liveMap = alivePairs.collect { case (addr, conn, true, last) => addr -> (conn, last) }.toMap
      configuredByAddr = cfg.targets.toVector.flatMap { (alias, raw) =>
        AdbValidation.parseHostPort(raw).toOption.map { (h, p) => s"$h:$p" -> alias }
      }.toMap
      allAddresses = (configuredByAddr.keySet ++ liveMap.keySet).toVector.sorted
      items = allAddresses.map { addr =>
        val alias = configuredByAddr.get(addr)
        liveMap.get(addr) match
          case Some((conn, last)) =>
            AdbDeviceSession(
              address = addr,
              alias = alias,
              connected = true,
              banner = Some(conn.banner),
              maxPayload = Some(conn.maxPayload),
              connectedAt = Some(conn.connectedAt.toString),
              lastUsedAt = Some(last.toString)
            )
          case None =>
            AdbDeviceSession(
              address = addr,
              alias = alias,
              connected = false
            )
      }
    yield AdbDevicesResult(
      keyPath = key.keyPath.toString,
      publicKeyFingerprint = key.fingerprintSha256,
      devices = items
    )

  def connect(req: ConnectRequest): IO[ConnectResult] =
    for
      (conn, alias, cfg) <- acquireConnection(req.target, waitForAuth = req.waitForAuth, forceReconnect = true)
      connTimeout = Contracts.parseDuration(cfg.connectTimeout)
      propsRaw <- runRawShell(
        conn,
        "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk",
        connTimeout,
        connTimeout,
        4096
      ).map(r => new String(r._1.toArray, UTF_8)).attempt
      lines = propsRaw.toOption.map(_.linesIterator.map(_.trim).toVector).getOrElse(Vector.empty)
    yield ConnectResult(
      address = conn.address,
      alias = alias,
      banner = conn.banner,
      protocolVersion = conn.protocolVersion,
      maxPayload = conn.maxPayload,
      model = lines.lift(0).filter(_.nonEmpty),
      androidVersion = lines.lift(1).filter(_.nonEmpty),
      sdkInt = lines.lift(2).flatMap(_.toIntOption)
    )

  def disconnect(target: Option[String], all: Boolean): IO[DisconnectResult] =
    poolLock.permit.use { _ =>
      if all then
        for
          prev <- poolRef.getAndSet(Map.empty)
          _ <- prev.values.toVector.traverse_(_.close.attempt.void)
        yield DisconnectResult(disconnected = prev.keys.toVector.sorted, remainingConnected = 0)
      else
        target.map(_.trim).filter(_.nonEmpty) match
          case None =>
            IO.raiseError(AdbFailure("invalid_adb", "target must be specified unless all=true"))
          case Some(tok) =>
            for
              cfg <- config
              (addr, _) <- resolveEndpoint(tok, cfg)
              removedOpt <- poolRef.modify(m => (m - addr, m.get(addr)))
              _ <- removedOpt.traverse_(_.close.attempt.void)
              rem <- poolRef.get
            yield DisconnectResult(
              disconnected = removedOpt.map(_ => Vector(addr)).getOrElse(Vector.empty),
              remainingConnected = rem.size
            )
    }

  def info(target: String): IO[DeviceInfo] =
    withConnection(target) { (conn, alias, cfg) =>
      val openTimeout = Contracts.parseDuration(cfg.connectTimeout)
      val cmdTimeout = Contracts.parseDuration(cfg.commandTimeout)
      val probeScript =
        "echo __PROPS__; " +
          "for k in ro.product.manufacturer ro.product.brand ro.product.model ro.product.device ro.product.name ro.serialno ro.build.version.release ro.build.version.sdk; do " +
          "printf '%s=%s\\n' \"$k\" \"$(getprop $k)\"; done; " +
          "echo __POWER__; dumpsys power | grep -E 'mWakefulness=|Display Power: state=' | head -n 6; " +
          "echo __DISPLAY__; dumpsys display | grep -E 'mScreenState=|mGlobalDisplayState=' | head -n 6; " +
          "echo __UPTIME__; uptime"
      runRawShell(conn, probeScript, cmdTimeout, openTimeout, 64 * 1024).map { (bytes, _, _) =>
        val text = new String(bytes.toArray, UTF_8)
        val kvMap = text.linesIterator.collect {
          case line if line.contains('=') && line.startsWith("ro.") =>
            val idx = line.indexOf('=')
            line.substring(0, idx).trim -> line.substring(idx + 1).trim
        }.toMap
        val (screenState, wakefulness) = AdbService.parseScreenAndWakefulness(text)
        val uptimeLine = text.linesIterator.dropWhile(_ != "__UPTIME__").drop(1).map(_.trim).find(_.nonEmpty)
        DeviceInfo(
          address = conn.address,
          alias = alias,
          banner = conn.banner,
          maxPayload = conn.maxPayload,
          manufacturer = kvMap.get("ro.product.manufacturer").filter(_.nonEmpty),
          brand = kvMap.get("ro.product.brand").filter(_.nonEmpty),
          model = kvMap.get("ro.product.model").filter(_.nonEmpty),
          device = kvMap.get("ro.product.device").filter(_.nonEmpty),
          product = kvMap.get("ro.product.name").filter(_.nonEmpty),
          serial = kvMap.get("ro.serialno").filter(_.nonEmpty),
          androidVersion = kvMap.get("ro.build.version.release").filter(_.nonEmpty),
          sdkInt = kvMap.get("ro.build.version.sdk").flatMap(_.toIntOption),
          screenState = screenState,
          wakefulness = wakefulness,
          uptime = uptimeLine
        )
      }
    }

  def exec(req: ExecRequest): IO[ExecResult] =
    for
      overrideTimeout <- req.timeout.map(_.trim).filter(_.nonEmpty).traverse { raw =>
        if Contracts.isDuration(raw) then IO.pure(Contracts.parseDuration(raw))
        else IO.raiseError(AdbFailure("invalid_adb", s"Invalid timeout duration '$raw' (expected e.g. 5s, 30s)"))
      }
      out <- withConnection(req.target) { (conn, _, cfg) =>
        val openTimeout = Contracts.parseDuration(cfg.connectTimeout)
        val cmdTimeout = overrideTimeout.getOrElse(Contracts.parseDuration(cfg.commandTimeout))
        runRawShell(conn, req.command, cmdTimeout, openTimeout, cfg.maxOutputBytes).map { (bytes, truncated, ms) =>
          ExecResult(
            address = conn.address,
            command = req.command,
            output = new String(bytes.toArray, UTF_8),
            outputBytes = bytes.size.toInt,
            truncated = truncated,
            durationMs = ms
          )
        }
      }
    yield out

  def keyevent(req: KeyEventRequest): IO[KeyEventResult] =
    if req.keys.isEmpty || req.keys.size > 32 then
      IO.raiseError(AdbFailure("invalid_adb", "keys must contain 1 to 32 key names or numeric keycodes"))
    else if req.longPress && req.keys.size > 1 then
      IO.raiseError(AdbFailure("invalid_adb", "longPress=true supports a single key per call"))
    else
      for
        codes <- IO.fromEither(req.keys.traverse(AdbService.resolveKeyCode))
        _ <-
          if codes.contains(26) && !req.allowPowerOff then
            IO.raiseError(
              AdbFailure(
                "invalid_adb",
                "KEYCODE_POWER (26) can hard power-off TVs without Wake-on-LAN; use SLEEP (223) / WAKEUP (224), or pass allowPowerOff=true"
              )
            )
          else IO.unit
        res <- withConnection(req.target) { (conn, _, cfg) =>
          val openTimeout = Contracts.parseDuration(cfg.connectTimeout)
          val cmdTimeout = Contracts.parseDuration(cfg.commandTimeout)
          val flag = if req.longPress then " --longpress" else ""
          val cmd = s"input keyevent$flag ${codes.mkString(" ")}"
          runRawShell(conn, cmd, cmdTimeout, openTimeout, 16 * 1024).map { (_, _, ms) =>
            KeyEventResult(
              address = conn.address,
              resolvedKeyCodes = codes,
              longPress = req.longPress,
              durationMs = ms
            )
          }
        }
      yield res

  def input(req: InputRequest): IO[InputResult] =
    val builtCmd: Either[AdbFailure, String] = req.kind match
      case InputKind.tap =>
        (req.x, req.y) match
          case (Some(x), Some(y)) if x >= 0 && y >= 0 => Right(s"input tap $x $y")
          case _ => Left(AdbFailure("invalid_adb", "tap requires non-negative x and y coordinates"))
      case InputKind.swipe =>
        (req.x, req.y, req.x2, req.y2) match
          case (Some(x1), Some(y1), Some(x2), Some(y2)) if x1 >= 0 && y1 >= 0 && x2 >= 0 && y2 >= 0 =>
            req.durationMs match
              case Some(d) if d < 0 || d > 60000 =>
                Left(AdbFailure("invalid_adb", "swipe durationMs must be between 0 and 60000"))
              case Some(d) => Right(s"input swipe $x1 $y1 $x2 $y2 $d")
              case None    => Right(s"input swipe $x1 $y1 $x2 $y2")
          case _ =>
            Left(AdbFailure("invalid_adb", "swipe requires non-negative x, y, x2, and y2 coordinates"))
      case InputKind.text =>
        req.text match
          case Some(t) if t.nonEmpty && !t.contains('\u0000') && !t.contains('\n') && !t.contains('\r') =>
            Right(s"input text ${AdbService.escapeInputText(t)}")
          case _ =>
            Left(AdbFailure("invalid_adb", "text input requires non-empty single-line text without NUL/newline characters"))

    for
      cmd <- IO.fromEither(builtCmd)
      res <- withConnection(req.target) { (conn, _, cfg) =>
        val openTimeout = Contracts.parseDuration(cfg.connectTimeout)
        val cmdTimeout = Contracts.parseDuration(cfg.commandTimeout)
        runRawShell(conn, cmd, cmdTimeout, openTimeout, 16 * 1024).map { (_, _, ms) =>
          InputResult(address = conn.address, kind = req.kind, durationMs = ms)
        }
      }
    yield res

  def screen(req: ScreenRequest): IO[ScreenResult] =
    withConnection(req.target) { (conn, _, cfg) =>
      val openTimeout = Contracts.parseDuration(cfg.connectTimeout)
      val cmdTimeout = Contracts.parseDuration(cfg.commandTimeout)
      val stateProbe =
        "dumpsys power | grep -E 'mWakefulness=|Display Power: state=' | head -n 6; " +
          "dumpsys display | grep -E 'mScreenState=|mGlobalDisplayState=' | head -n 6"

      def queryState: IO[(String, Option[String])] =
        runRawShell(conn, stateProbe, cmdTimeout, openTimeout, 16 * 1024).map { (b, _, _) =>
          AdbService.parseScreenAndWakefulness(new String(b.toArray, UTF_8))
        }

      for
        (beforeState, beforeWake) <- queryState
        desiredCode: Option[Int] = req.mode match
          case ScreenAction.status => None
          case ScreenAction.on     => if beforeState == "ON" then None else Some(224) // KEYCODE_WAKEUP
          case ScreenAction.off    => if beforeState == "OFF" then None else Some(223) // KEYCODE_SLEEP
          case ScreenAction.toggle => if beforeState == "ON" then Some(223) else Some(224)
        finalPair <- desiredCode match
          case None => IO.pure((beforeState, beforeWake))
          case Some(code) =>
            runRawShell(conn, s"input keyevent $code", cmdTimeout, openTimeout, 4096) *>
              IO.sleep(350.millis) *>
              queryState
      yield ScreenResult(
        address = conn.address,
        previousState = beforeState,
        currentState = finalPair._1,
        changed = beforeState != finalPair._1,
        wakefulness = finalPair._2
      )
    }

  def screencap(req: ScreencapRequest): IO[ScreencapResult] =
    if req.outputPath.forall(_.trim.isEmpty) && !req.includeBase64 then
      IO.raiseError(AdbFailure("invalid_adb", "Provide outputPath and/or set includeBase64=true for screencap"))
    else
      withConnection(req.target) { (conn, _, cfg) =>
        val openTimeout = Contracts.parseDuration(cfg.connectTimeout)
        val cmdTimeout = Contracts.parseDuration(cfg.commandTimeout)
        val remoteTmp = s"/data/local/tmp/.groot_cap_${java.lang.Long.toUnsignedString(System.nanoTime())}.png"
        val captureAndPull =
          (for
            _ <- runRawShell(conn, s"screencap -p $remoteTmp", cmdTimeout, openTimeout, 16 * 1024)
            pngBytes <- AdbSync.recvToBytes(conn, remoteTmp, openTimeout, cfg.maxOutputBytes.toLong)
          yield pngBytes).guarantee(
            runRawShell(conn, s"rm -f $remoteTmp", openTimeout, openTimeout, 4096).attempt.void
          )

        for
          png <- captureAndPull
          _ <-
            if png.size < 24 || png.take(8) != AdbService.PngSignature then
              IO.raiseError(AdbFailure("adb_remote_error", s"screencap on ${conn.address} did not produce a valid PNG (${png.size} bytes)"))
            else IO.unit
          dims = AdbService.parsePngDimensions(png)
          savedOpt <- req.outputPath.map(_.trim).filter(_.nonEmpty).traverse { rawOut =>
            val dest = Path.of(rawOut).toAbsolutePath.normalize()
            IO.blocking {
              Option(dest.getParent).foreach(p => Files.createDirectories(p))
              val tmp = Files.createTempFile(dest.getParent, ".screencap-", ".tmp")
              try
                Files.write(tmp, png.toArray, StandardOpenOption.TRUNCATE_EXISTING)
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                dest.toString
              finally
                Files.deleteIfExists(tmp)
                ()
            }
          }
          b64Opt = if req.includeBase64 then Some(Base64.getEncoder.encodeToString(png.toArray)) else None
        yield ScreencapResult(
          address = conn.address,
          sizeBytes = png.size.toInt,
          savedPath = savedOpt,
          pngBase64 = b64Opt,
          width = dims.map(_._1),
          height = dims.map(_._2)
        )
      }

  def stat(target: String, remotePath: String): IO[SyncStatResult] =
    validateRemotePath(remotePath) *>
      withConnection(target) { (conn, _, cfg) =>
        AdbSync.stat(conn, remotePath.trim, Contracts.parseDuration(cfg.connectTimeout))
      }

  def ls(target: String, remotePath: String): IO[SyncListResult] =
    validateRemotePath(remotePath) *>
      withConnection(target) { (conn, _, cfg) =>
        AdbSync.list(conn, remotePath.trim, Contracts.parseDuration(cfg.connectTimeout))
      }

  def push(req: PushRequest): IO[PushResult] =
    val localTrimmed = Option(req.localPath).map(_.trim).getOrElse("")
    if localTrimmed.isEmpty || localTrimmed.contains('\u0000') then
      IO.raiseError(AdbFailure("invalid_adb", "localPath must be non-empty"))
    else
      validateRemotePath(req.remotePath) *>
        withConnection(req.target) { (conn, _, cfg) =>
          val src = Path.of(localTrimmed).toAbsolutePath.normalize()
          val mode = req.mode.getOrElse(420) // 0644
          for
            t0 <- IO.monotonic
            bytes <- AdbSync.sendFile(
              conn = conn,
              localFile = src,
              remotePath = req.remotePath.trim,
              mode = mode,
              openTimeout = Contracts.parseDuration(cfg.connectTimeout),
              maxBytes = cfg.maxTransferBytes
            )
            t1 <- IO.monotonic
          yield PushResult(
            address = conn.address,
            localPath = src.toString,
            remotePath = req.remotePath.trim,
            sizeBytes = bytes,
            durationMs = (t1 - t0).toMillis
          )
        }

  def pull(req: PullRequest): IO[PullResult] =
    validateRemotePath(req.remotePath) *>
      withConnection(req.target) { (conn, _, cfg) =>
        val openTimeout = Contracts.parseDuration(cfg.connectTimeout)
        val rpath = req.remotePath.trim
        req.localPath.map(_.trim).filter(_.nonEmpty) match
          case Some(localOut) =>
            val dest = Path.of(localOut).toAbsolutePath.normalize()
            for
              t0 <- IO.monotonic
              written <- AdbSync.recvToFile(conn, rpath, dest, openTimeout, cfg.maxTransferBytes)
              t1 <- IO.monotonic
            yield PullResult(
              address = conn.address,
              remotePath = rpath,
              savedPath = Some(dest.toString),
              sizeBytes = written,
              contentBase64 = None,
              durationMs = (t1 - t0).toMillis
            )
          case None =>
            for
              t0 <- IO.monotonic
              bytes <- AdbSync.recvToBytes(conn, rpath, openTimeout, inlinePullMaxBytes.toLong)
              t1 <- IO.monotonic
            yield PullResult(
              address = conn.address,
              remotePath = rpath,
              savedPath = None,
              sizeBytes = bytes.size,
              contentBase64 = Some(Base64.getEncoder.encodeToString(bytes.toArray)),
              durationMs = (t1 - t0).toMillis
            )
      }

  def install(req: InstallRequest): IO[InstallResult] =
    val apkTrimmed = Option(req.apkPath).map(_.trim).getOrElse("")
    if apkTrimmed.isEmpty || apkTrimmed.contains('\u0000') then
      IO.raiseError(AdbFailure("invalid_adb", "apkPath must be non-empty"))
    else
      withConnection(req.target) { (conn, _, cfg) =>
        val apk = Path.of(apkTrimmed).toAbsolutePath.normalize()
        val openTimeout = Contracts.parseDuration(cfg.connectTimeout)
        val cmdTimeout = Contracts.parseDuration(cfg.commandTimeout).max(60.seconds)
        val remoteTmp = s"/data/local/tmp/.groot_install_${java.lang.Long.toUnsignedString(System.nanoTime())}.apk"
        val flags = Vector(
          Option.when(req.replace)("-r"),
          Option.when(req.allowDowngrade)("-d"),
          Option.when(req.grantPermissions)("-g")
        ).flatten.mkString(" ")
        val pmCmd = if flags.nonEmpty then s"pm install $flags $remoteTmp" else s"pm install $remoteTmp"

        for
          t0 <- IO.monotonic
          res <- (for
            sz <- AdbSync.sendFile(conn, apk, remoteTmp, 420, openTimeout, cfg.maxTransferBytes)
            (outBytes, _, _) <- runRawShell(conn, pmCmd, cmdTimeout, openTimeout, 64 * 1024)
            outStr = new String(outBytes.toArray, UTF_8).trim
            _ <-
              if outStr.linesIterator.exists(_.trim.equalsIgnoreCase("Success")) then IO.unit
              else IO.raiseError(AdbFailure("adb_remote_error", s"APK install failed on ${conn.address}: $outStr"))
          yield (sz, outStr)).guarantee(
            runRawShell(conn, s"rm -f $remoteTmp", openTimeout, openTimeout, 4096).attempt.void
          )
          t1 <- IO.monotonic
        yield InstallResult(
          address = conn.address,
          apkPath = apk.toString,
          sizeBytes = res._1,
          output = res._2,
          durationMs = (t1 - t0).toMillis
        )
      }

  def uninstall(req: UninstallRequest): IO[UninstallResult] =
    val pkg = Option(req.packageName).map(_.trim).getOrElse("")
    if !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") then
      IO.raiseError(AdbFailure("invalid_adb", s"Invalid Android package name: '$pkg'"))
    else
      withConnection(req.target) { (conn, _, cfg) =>
        val openTimeout = Contracts.parseDuration(cfg.connectTimeout)
        val cmdTimeout = Contracts.parseDuration(cfg.commandTimeout)
        val cmd = if req.keepData then s"pm uninstall -k $pkg" else s"pm uninstall $pkg"
        for
          t0 <- IO.monotonic
          (outBytes, _, _) <- runRawShell(conn, cmd, cmdTimeout, openTimeout, 32 * 1024)
          t1 <- IO.monotonic
          outStr = new String(outBytes.toArray, UTF_8).trim
          _ <-
            if outStr.linesIterator.exists(_.trim.equalsIgnoreCase("Success")) then IO.unit
            else IO.raiseError(AdbFailure("adb_remote_error", s"Package uninstall failed on ${conn.address}: $outStr"))
        yield UninstallResult(
          address = conn.address,
          packageName = pkg,
          keepData = req.keepData,
          output = outStr,
          durationMs = (t1 - t0).toMillis
        )
      }

  def reboot(req: RebootRequest): IO[RebootResult] =
    if !req.confirm then
      IO.raiseError(AdbFailure("invalid_adb", "Pass confirm=true to execute device reboot"))
    else
      withConnection(req.target) { (conn, _, cfg) =>
        val openTimeout = Contracts.parseDuration(cfg.connectTimeout)
        val dest = req.mode match
          case RebootMode.system     => "reboot:"
          case RebootMode.recovery   => "reboot:recovery"
          case RebootMode.bootloader => "reboot:bootloader"
        conn.openStream(dest, openTimeout).use(_ => IO.unit).attempt *>
          disconnect(Some(conn.address), all = false).attempt *>
          IO.pure(RebootResult(address = conn.address, mode = req.mode, accepted = true))
      }

  private def validateRemotePath(remotePath: String): IO[Unit] =
    val p = Option(remotePath).map(_.trim).getOrElse("")
    if p.isEmpty || !p.startsWith("/") || p.contains('\u0000') then
      IO.raiseError(AdbFailure("invalid_adb", "remotePath must be an absolute device path starting with '/'"))
    else IO.unit

  private[adb] def evictIdleConnections: IO[Unit] =
    for
      cfg <- config
      maxIdle = Contracts.parseDuration(cfg.idleTimeout)
      now <- IO.realTimeInstant
      toClose <- poolLock.permit.use { _ =>
        poolRef.get.flatMap { cur =>
          cur.toVector.traverse { (addr, conn) =>
            for
              alive <- conn.isAlive
              last <- conn.lastUsedAt
              idleMillis = java.time.Duration.between(last, now).toMillis
            yield Option.when(!alive || idleMillis >= maxIdle.toMillis)((addr, conn))
          }.flatMap { expiredOpts =>
            val expired = expiredOpts.flatten
            if expired.isEmpty then IO.pure(Vector.empty)
            else poolRef.update(_ -- expired.map(_._1)).as(expired.map(_._2))
          }
        }
      }
      _ <- toClose.traverse_(_.close.attempt.void)
    yield ()

  private[adb] def shutdownAll: IO[Unit] =
    poolRef.getAndSet(Map.empty).flatMap(_.values.toVector.traverse_(_.close.attempt.void))

object AdbService:
  val PngSignature: ByteVector = ByteVector(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

  private val NamedKeyCodes: Map[String, Int] = Map(
    "HOME" -> 3,
    "BACK" -> 4,
    "DPAD_UP" -> 19,
    "UP" -> 19,
    "DPAD_DOWN" -> 20,
    "DOWN" -> 20,
    "DPAD_LEFT" -> 21,
    "LEFT" -> 21,
    "DPAD_RIGHT" -> 22,
    "RIGHT" -> 22,
    "DPAD_CENTER" -> 23,
    "CENTER" -> 23,
    "OK" -> 23,
    "VOLUME_UP" -> 24,
    "VOL_UP" -> 24,
    "VOLUME_DOWN" -> 25,
    "VOL_DOWN" -> 25,
    "POWER" -> 26,
    "CLEAR" -> 28,
    "TAB" -> 61,
    "SPACE" -> 62,
    "ENTER" -> 66,
    "DEL" -> 67,
    "BACKSPACE" -> 67,
    "MENU" -> 82,
    "SEARCH" -> 84,
    "MEDIA_PLAY_PAUSE" -> 85,
    "PLAY_PAUSE" -> 85,
    "MEDIA_STOP" -> 86,
    "MEDIA_NEXT" -> 87,
    "MEDIA_PREVIOUS" -> 88,
    "PAGE_UP" -> 92,
    "PAGE_DOWN" -> 93,
    "ESCAPE" -> 111,
    "ESC" -> 111,
    "MEDIA_PLAY" -> 126,
    "MEDIA_PAUSE" -> 127,
    "VOLUME_MUTE" -> 164,
    "MUTE" -> 164,
    "SETTINGS" -> 176,
    "TV_INPUT" -> 178,
    "APP_SWITCH" -> 187,
    "SLEEP" -> 223,
    "WAKEUP" -> 224
  )

  def resolveKeyCode(raw: String): Either[AdbFailure, Int] =
    val s = Option(raw).map(_.trim).getOrElse("")
    if s.isEmpty then Left(AdbFailure("invalid_adb", "Key name or code must not be empty"))
    else
      s.toIntOption match
        case Some(code) if code >= 0 && code <= 1000 => Right(code)
        case Some(code) => Left(AdbFailure("invalid_adb", s"Keycode out of range 0..1000: $code"))
        case None =>
          val normalized = s.toUpperCase(Locale.ROOT).stripPrefix("KEYCODE_")
          NamedKeyCodes.get(normalized).toRight(
            AdbFailure("invalid_adb", s"Unknown key '$raw' (pass numeric keycode or standard name like SLEEP, WAKEUP, HOME, BACK, DPAD_UP)")
          )

  /** Escape single-line ASCII text for Android's `input text` shell command. */
  def escapeInputText(raw: String): String =
    val sb = new StringBuilder(raw.length + 16)
    sb.append('\'')
    raw.foreach {
      case ' '  => sb.append("%s")
      case '\'' => sb.append("'\\''")
      case ch   => sb.append(ch)
    }
    sb.append('\'')
    sb.toString

  def parseScreenAndWakefulness(dumpsysText: String): (String, Option[String]) =
    val lines = dumpsysText.linesIterator.map(_.trim).toVector
    val wake = lines.collectFirst {
      case l if l.contains("mWakefulness=") =>
        l.substring(l.indexOf("mWakefulness=") + "mWakefulness=".length).takeWhile(c => c.isLetterOrDigit || c == '_')
    }
    val explicitScreen = lines.collectFirst {
      case l if l.contains("Display Power: state=") =>
        l.substring(l.indexOf("Display Power: state=") + "Display Power: state=".length).takeWhile(c => c.isLetterOrDigit || c == '_').toUpperCase(Locale.ROOT)
      case l if l.contains("mScreenState=") =>
        l.substring(l.indexOf("mScreenState=") + "mScreenState=".length).takeWhile(c => c.isLetterOrDigit || c == '_').toUpperCase(Locale.ROOT)
      case l if l.contains("mGlobalDisplayState=") =>
        l.substring(l.indexOf("mGlobalDisplayState=") + "mGlobalDisplayState=".length).takeWhile(c => c.isLetterOrDigit || c == '_').toUpperCase(Locale.ROOT)
    }
    val derivedState = explicitScreen.orElse {
      wake.map(_.toLowerCase(Locale.ROOT)).map {
        case "awake" | "dreaming" => "ON"
        case "asleep" | "dozing"  => "OFF"
        case other                => other.toUpperCase(Locale.ROOT)
      }
    }.getOrElse("UNKNOWN")
    (derivedState, wake)

  def parsePngDimensions(png: ByteVector): Option[(Int, Int)] =
    // PNG layout: 8-byte signature + 4-byte length + 4-byte "IHDR" + 4-byte width BE + 4-byte height BE
    if png.size >= 24 && png.slice(12, 16) == ByteVector.view("IHDR".getBytes(UTF_8)) then
      val bb = png.slice(16, 24).toByteBuffer.order(ByteOrder.BIG_ENDIAN)
      val w = bb.getInt()
      val h = bb.getInt()
      Option.when(w > 0 && h > 0)((w, h))
    else None

  def resource(
      grootHome: Path = groot.schedule.Store.defaultHome,
      config: IO[AdbConfig] = IO.pure(AdbConfig()),
      reaperInterval: FiniteDuration = 15.seconds
  ): Resource[IO, AdbService] =
    for
      lock <- Resource.eval(Semaphore[IO](1))
      poolRef <- Resource.eval(Ref.of[IO, Map[String, AdbConnection]](Map.empty))
      svc <- Resource.make(
        IO.pure(new AdbService(grootHome.toAbsolutePath.normalize(), config, lock, poolRef))
      )(_.shutdownAll)
      _ <- Stream.awakeEvery[IO](reaperInterval).evalMap(_ => svc.evictIdleConnections.attempt.void).compile.drain.background
    yield svc
