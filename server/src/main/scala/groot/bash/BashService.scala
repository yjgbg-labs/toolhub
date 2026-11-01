package groot.bash

import cats.effect.{IO, Resource}
import cats.effect.kernel.Resource.ExitCase
import cats.syntax.all.*
import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, InvalidPathException, Path}
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Linux host processes belong to the SDK call; abnormal completion cleans up before releasing the task. */
final class BashService:
  import BashService.*

  def apply(request: BashRequest): IO[BashResult] = validate(request).flatMap { (directory, deadline) =>
    Resource.makeCase(IO.blocking {
      val builder = new ProcessBuilder("/usr/bin/setsid", "--", "/bin/bash", "--noprofile", "--norc", "-c", request.script)
        .directory(directory.map(_.toFile).orNull)
      // Noninteractive startup must not execute an inherited BASH_ENV script ahead of the supplied script.
      builder.environment().remove("BASH_ENV")
      builder.start()
    }) { (child, exit) =>
      (if exit == ExitCase.Succeeded then IO.unit else stop(child)).guarantee(IO.blocking {
        List(child.getOutputStream, child.getInputStream, child.getErrorStream).foreach(stream =>
          try stream.close() catch case NonFatal(_) => ())
      })
    }.use { child =>
      val execute = for
        _ <- IO.blocking(child.getOutputStream.close())
        stdout <- capture(child.getInputStream)
        stderr <- capture(child.getErrorStream)
        result <- (await(child.onExit()).map(_.exitValue()), await(stdout), await(stderr)).parMapN(BashResult.apply)
      yield result
      execute.timeoutTo(deadline, IO.raiseError(BashFailure("bash_timeout", s"Bash exceeded ${request.timeout}")))
    }
  }

  private def validate(request: BashRequest): IO[(Option[Path], FiniteDuration)] = IO.blocking {
    if request.script.trim.isEmpty || request.script.contains('\u0000') then throw BashFailure("invalid_bash", "script must be nonblank and contain no NUL")
    if request.script.getBytes(UTF_8).length > maxBytes then throw BashFailure("bash_limit", "Bash script exceeds 64 KiB")
    val directory = request.cwd.map { value =>
      val path = try Path.of(value) catch case _: InvalidPathException => throw BashFailure("invalid_bash", "Invalid cwd")
      if !path.isAbsolute || !Files.isDirectory(path) then throw BashFailure("invalid_bash", "cwd must be an existing absolute directory")
      path
    }
    val millis = request.timeout match
      case duration(value, unit) => BigInt(value) * units(unit)
      case _ => throw BashFailure("invalid_bash", "timeout must be a positive integer followed by ms, s, m, h or d")
    if millis <= 0 || millis > Long.MaxValue / 1000000L then throw BashFailure("invalid_bash", "timeout is out of range")
    (directory, millis.toLong.millis)
  }

  // Blocking pipe reads do not reliably respond to thread interruption. Waiting on their completion
  // is cancellable; the Resource finalizer kills the group, which unblocks both readers.
  private def capture(input: InputStream): IO[CompletableFuture[String]] = IO {
    val result = new CompletableFuture[String]()
    Thread.ofPlatform().daemon().name("bash-output").start(() => {
      try
        val output = new ByteArrayOutputStream()
        val buffer = new Array[Byte](8192)
        var count = input.read(buffer)
        while count >= 0 do
          if output.size() + count > maxBytes then throw BashFailure("bash_limit", "Bash output exceeds 64 KiB per stream")
          output.write(buffer, 0, count)
          count = input.read(buffer)
        result.complete(output.toString(UTF_8))
      catch case NonFatal(error) => result.completeExceptionally(error)
      ()
    })
    result
  }

  private def await[A](future: CompletableFuture[A]): IO[A] = IO.async { callback => IO {
    future.whenComplete { (value, error) => callback(if error == null then Right(value) else Left(error)) }
    Some(IO.unit) // Detach the observer on cancellation; Resource cleanup owns the actual process.
  }
  }

  private def signal(pid: Long, name: String): IO[Unit] = IO.blocking {
    val command = new ProcessBuilder("/bin/kill", s"-$name", "--", s"-$pid")
      .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    command.getOutputStream.close()
    if !command.waitFor(1, TimeUnit.SECONDS) then command.destroyForcibly()
    () // ESRCH is normal when the process group has already exited.
  }

  private def stop(child: Process): IO[Unit] =
    for
      descendants <- IO.blocking {
        val stream = child.descendants()
        try stream.iterator().asScala.toVector.reverse finally stream.close()
      }
      _ <- signal(child.pid(), "TERM")
      _ <- IO.blocking { descendants.foreach(_.destroy()); child.toHandle.destroy(); () }
      _ <- IO.sleep(200.millis)
      // Signal even after the leader exits: grandchildren may still own its pipes/group.
      _ <- signal(child.pid(), "KILL")
      _ <- IO.blocking {
        descendants.filter(_.isAlive).foreach(_.destroyForcibly())
        if child.isAlive then child.toHandle.destroyForcibly()
        child.waitFor()
        ()
      }
    yield ()

object BashService:
  private[bash] val maxBytes = 64 * 1024
  private val duration = "^(\\d+)(ms|s|m|h|d)$".r
  private val units = Map("ms" -> 1L, "s" -> 1000L, "m" -> 60000L, "h" -> 3600000L, "d" -> 86400000L)
