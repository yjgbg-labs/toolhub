package groot.graaljs

import cats.effect.{IO, Resource}
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

private[graaljs] object GraalTestSupport:
  def temporary: Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory(Path.of("/tmp/opencode"), "graaljs-test-"))
  )(path => IO.blocking {
    val files = Files.walk(path)
    try files.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
    finally files.close()
  })

  def awaitUntil[A](io: IO[A])(check: A => Boolean): IO[A] =
    def loop: IO[A] = io.flatMap(value => if check(value) then IO.pure(value) else IO.sleep(10.millis) *> IO.defer(loop))
    loop.timeout(10.seconds)

  def script(home: Path, code: String, name: String = "script.graal.js"): IO[Path] =
    IO.blocking(Files.writeString(home.resolve(name), code))
