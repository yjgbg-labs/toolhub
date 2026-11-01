package groot.kube

import cats.effect.{IO, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import framework.model.JsonModel
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions

/** All mutations and lease counts are serialized. Retired pools stay alive until their last user leaves. */
private[kube] final class Contexts private (home: Path, mutex: Mutex[IO]):
  private class Entry(val client: KubeClient):
    var users = 0
    var retired = false
  private var entries = Map.empty[String, Entry]
  private var closed = false
  private def serial[A](action: => IO[A]): IO[A] = mutex.lock.surround(IO.uncancelable(_ => IO.defer(action)))
  private def summary(name: String, config: ContextConfig): ContextSummary =
    ContextSummary(name, config.server, config.namespace, config.caCertificate.nonEmpty, config.clientCertificate.nonEmpty, config.token.nonEmpty)
  private def nameCheck(name: String): Unit = KubeFailure.require(name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"), "Invalid context name")

  def list: IO[Vector[ContextSummary]] = serial(IO(entries.toVector.sortBy(_._1).map((name, entry) => summary(name, entry.client.config))))

  private def persist(next: Map[String, Entry]): IO[Unit] = IO.blocking {
    val temporary = Files.createTempFile(home, ".contexts-", ".json", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    try
      val json = JsonModel[Map[String, ContextConfig]].encoder(next.view.mapValues(_.client.config).toMap).noSpaces
      Files.writeString(temporary, json)
      val channel = FileChannel.open(temporary, StandardOpenOption.WRITE)
      try channel.force(true) finally channel.close()
      Files.move(temporary, home.resolve("contexts.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      val directory = FileChannel.open(home, StandardOpenOption.READ)
      try directory.force(true) finally directory.close()
    finally Files.deleteIfExists(temporary)
  }.void

  def put(name: String, config: ContextConfig): IO[ContextSummary] = serial {
    IO(nameCheck(name)) *> KubeClient.create(config).flatMap { client =>
      val entry = new Entry(client)
      val next = entries.updated(name, entry)
      persist(next).onError(_ => client.close) *> IO { val previous = entries.get(name); entries = next; previous }
        .flatMap(_.traverse_(retire)).as(summary(name, config))
    }
  }

  def delete(name: String): IO[Boolean] = serial {
    IO(nameCheck(name)) *> (entries.get(name) match
      case None => IO.pure(false)
      case Some(entry) => persist(entries - name) *> IO { entries -= name } *> retire(entry).as(true))
  }

  private def retire(entry: Entry): IO[Unit] = IO { entry.retired = true } *> (if entry.users == 0 then entry.client.close else IO.unit)

  def lease(name: String): Resource[IO, KubeClient] = Resource.make(serial {
    IO {
      nameCheck(name)
      if closed then throw KubeFailure.local(503, "Closing", "Kubernetes service is closing")
      val entry = entries.getOrElse(name, throw KubeFailure.local(404, "ContextNotFound", s"Unknown context: $name"))
      entry.users += 1
      entry
    }
  }) { entry => serial {
    IO { entry.users -= 1 } *> IO.defer(if entry.retired && entry.users == 0 then entry.client.close else IO.unit)
  } }.map(_.client)

  private def load: IO[Unit] = IO.blocking {
    val path = home.resolve("contexts.json")
    if !Files.exists(path) then Map.empty[String, ContextConfig]
    else io.circe.parser.parse(Files.readString(path)).flatMap(JsonModel[Map[String, ContextConfig]].decoder.decodeJson).fold(throw _, identity)
  }.flatMap(_.toVector.traverse_ { (name, config) => IO(nameCheck(name)) *> KubeClient.create(config).flatMap(client => IO { entries += name -> new Entry(client) }) })

  private def close: IO[Unit] = serial { IO { closed = true } *> entries.values.toVector.traverse_(retire) }

private[kube] object Contexts:
  def resource(home: Path): Resource[IO, Contexts] = for
    _ <- Resource.make(IO.blocking {
      Files.createDirectories(home, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
      val channel = FileChannel.open(home.resolve("serve.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try
        if channel.tryLock() == null then throw new IllegalStateException("Kubernetes contexts directory already in use")
        channel
      catch
        case error: Throwable =>
          channel.close()
          throw error
    })(channel => IO.blocking(channel.close()))
    mutex <- Resource.eval(Mutex[IO])
    contexts <- Resource.make(IO(new Contexts(home, mutex)))(_.close)
    _ <- Resource.eval(contexts.load)
  yield contexts
