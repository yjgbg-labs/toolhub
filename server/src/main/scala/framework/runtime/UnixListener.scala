package framework.runtime

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import java.io.IOException
import java.net.{BindException, StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.{FileChannel, OverlappingFileLockException, SocketChannel}
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import scala.concurrent.duration.*

private[framework] object UnixListener:
  def resource(path: Path, server: Resource[IO, org.http4s.server.Server]): Resource[IO, org.http4s.server.Server] =
    val lock = Resource.make(IO.blocking {
      Files.createDirectories(path.getParent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
      FileChannel.open(path.resolveSibling(s"${path.getFileName}.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    })(channel => IO.blocking(channel.close())).evalTap(channel => IO.blocking {
      val acquired = try channel.tryLock() catch { case _: OverlappingFileLockException => null }
      if acquired == null then throw new BindException(s"Socket is already in use: $path")
    })
    val ownership = lock *> Resource.make(IO.blocking {
      if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then throw new BindException(s"Socket path already exists: $path")
    })(_ => IO.blocking(Files.deleteIfExists(path)).void)
    ownership *> server.evalTap(_ => awaitReady(path).timeout(5.seconds) *> IO.blocking {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    })

  private def awaitReady(path: Path): IO[Unit] =
    IO.blocking {
      val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
      try
        channel.configureBlocking(false)
        channel.connect(UnixDomainSocketAddress.of(path))
      catch { case _: IOException => false }
      finally channel.close()
    }.flatMap(ready => if ready then IO.unit else IO.sleep(10.millis) *> awaitReady(path))
