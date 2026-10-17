package frameworktests

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.{Channels, SocketChannel}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import munit.CatsEffectSuite
import framework.rpcDsl.*

class SocketSuite extends CatsEffectSuite:
  private def temporary = Resource.make(IO.blocking {
    Files.createTempDirectory(Path.of("/tmp/opencode"), "sprout-socket-")
  })(dir => IO.blocking {
    Files.deleteIfExists(dir.resolve("http.sock"))
    Files.deleteIfExists(dir.resolve("http.sock.lock"))
    Files.delete(dir)
  })

  private def request(path: Path, route: String): IO[String] = IO.blocking {
    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    try
      channel.connect(UnixDomainSocketAddress.of(path))
      Channels.newOutputStream(channel).write(s"GET $route HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".getBytes(UTF_8))
      new String(Channels.newInputStream(channel).readAllBytes(), UTF_8)
    finally channel.close()
  }

  test("Ember serves a private Unix socket, rejects a second listener and cleans up for rebinding") {
    temporary.use { dir =>
      val path = dir.resolve("http.sock")
      val app = httpApp {
        server { socket(path.toString) }
        command[String]("health") { handle { IO.pure("healthy") } }
      }
      val serve = app.resource.use { _ =>
        for
          permissions <- IO.blocking(Files.getPosixFilePermissions(path))
          _ = assertEquals(permissions, PosixFilePermissions.fromString("rw-------"))
          duplicate <- app.resource.use(_ => IO.unit).attempt
          _ = assert(duplicate.isLeft)
          response <- request(path, "/health")
        yield
          assert(response.startsWith("HTTP/1.1 200"))
          assert(response.contains("healthy"))
      }
      serve *> IO.blocking(assert(!Files.exists(path))) *> serve *> IO.blocking(assert(!Files.exists(path)))
    }
  }

  test("binding does not replace or delete an existing file") {
    temporary.use { dir =>
      val path = dir.resolve("http.sock")
      val app = httpApp { server { socket(path.toString) }; command[String]("health") { handle { IO.pure("ok") } } }
      for
        _ <- IO.blocking(Files.writeString(path, "existing"))
        result <- app.resource.use(_ => IO.unit).attempt
        content <- IO.blocking(Files.readString(path))
      yield
        assert(result.isLeft)
        assertEquals(content, "existing")
    }
  }
