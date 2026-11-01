package groot.graaljs

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, WatchKey, WatchService, NoSuchFileException, NotDirectoryException}
import java.nio.file.StandardWatchEventKinds.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** One event-driven watcher per scheduler, shared by jobs referencing the same normalized path.
  * All mutations and snapshots are serialized; running/queued tasks retain their own immutable Source.
  */
private[groot] final class ScriptSources private (watcher: WatchService):
  private case class Entry(dependencies: Set[Path], source: Either[TaskError, GraalSource])
  private var entries = Map.empty[Path, Entry]
  private var directories = Map.empty[Path, WatchKey]

  private def ancestors(path: Path): Set[Path] =
    Iterator.iterate(path.getParent)(_.getParent).takeWhile(_ != null).toSet

  // Resolve even dangling symlinks, so creating their missing target is also observable.
  private def target(path: Path, depth: Int = 0): (Path, Set[Path]) =
    if depth > 40 then throw new java.io.IOException(s"Too many symbolic links: $path")
    var current = path.getRoot
    var seen = Set(path)
    path.iterator().asScala.foreach { part =>
      current = current.resolve(part)
      if Files.isSymbolicLink(current) then
        val link = Files.readSymbolicLink(current)
        seen += current
        val (resolved, dependencies) = target((if link.isAbsolute then link else current.getParent.resolve(link)).normalize(), depth + 1)
        current = resolved
        seen ++= dependencies
    }
    (current, seen + current)

  private def dependencies(path: Path): Set[Path] =
    try target(path)._2
    catch case NonFatal(_) => entries.get(path).fold(Set(path))(_.dependencies + path)

  private def watchDirectories(paths: Set[Path]): Unit =
    // Watch ancestors as well: atomic directory replacement and missing parents need no polling.
    var wanted = Set.empty[Path]
    // Subscribe top-down before inspecting children: creating a missing hierarchy during registration
    // must either find an installed parent watch or be seen by the subsequent child inspection.
    paths.flatMap(ancestors).toList.sortBy(_.getNameCount).foreach { candidate =>
      try
        if Files.isDirectory(candidate) then
          val path = candidate.toRealPath()
          wanted += path
          if directories.get(path).forall(!_.isValid) then
            directories += path -> path.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
      catch
        case _: NoSuchFileException => () // Parent watches observe recreation.
        case _: NotDirectoryException => ()
    }
    directories.filter((path, key) => !wanted(path) || !key.isValid).foreach { (path, key) =>
      key.cancel()
      directories -= path
    }

  private def load(path: Path): Either[TaskError, GraalSource] =
    try
      if !Files.isRegularFile(path) then throw new java.io.IOException(s"Not a regular script file: $path")
      val input = Files.newInputStream(path)
      val bytes = try input.readNBytes(Limits.maxSourceBytes + 1) finally input.close()
      if bytes.length > Limits.maxSourceBytes then throw InvalidGraalTask("JavaScript source exceeds 1 MiB")
      val text = UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString
      // Duplicate modify notifications and metadata-only changes preserve identity and compiled code.
      entries.get(path).flatMap(_.source.toOption).filter(_.text == text) match
        case Some(previous) => Right(previous)
        case None => Right(GraalSource.file(text, path))
    catch case NonFatal(error) => Left(TaskError("source_error", s"$path: ${error.getMessage}".take(8192)))

  private def refresh(paths: Set[Path]): Unit =
    var deps = paths.map(path => path -> dependencies(path)).toMap
    val retained = entries.filterNot((path, _) => paths(path)).values.flatMap(_.dependencies).toSet
    // Subscribe before reading, so a concurrent edit cannot disappear between the initial load and watch.
    try
      watchDirectories(retained ++ deps.values.flatten)
      // A symlink could have changed before its parent was subscribed. Resolve it again once armed.
      deps = paths.map(path => path -> dependencies(path)).toMap
      watchDirectories(retained ++ deps.values.flatten)
      paths.foreach { path => entries += path -> Entry(deps(path), load(path)) }
    catch case NonFatal(error) =>
      paths.foreach { path => entries += path -> Entry(deps(path), Left(TaskError("source_error", s"Cannot watch $path: ${error.getMessage}".take(8192)))) }
      scribe.error("Cannot refresh watched scripts", error)

  def retain(paths: Set[Path]): IO[Unit] = IO.blocking { synchronized {
    val normalized = paths.map(_.toAbsolutePath.normalize())
    entries = entries.filter((path, _) => normalized(path))
    refresh(normalized -- entries.keySet)
  } }

  def snapshot(path: Path): IO[Either[TaskError, GraalSource]] = IO.blocking { synchronized {
    entries.get(path.toAbsolutePath.normalize()).map(_.source)
      .getOrElse(Left(TaskError("source_error", s"Script is not watched: $path")))
  } }

  private def handle(key: WatchKey): Unit = synchronized {
    val events = key.pollEvents().asScala.toList
    val directory = directories.collectFirst { case (path, value) if value == key => path }
    val valid = key.reset()
    directory.foreach { parent =>
      val overflow = !valid || events.exists(_.kind() == OVERFLOW)
      val changed = events.filterNot(_.kind() == OVERFLOW).map(event => parent.resolve(event.context().asInstanceOf[Path]))
      val replaced = events.filter(event => event.kind() == ENTRY_CREATE || event.kind() == ENTRY_DELETE)
        .map(event => parent.resolve(event.context().asInstanceOf[Path]))
      // A moved-away directory's key may still be valid; rebind its path to the new inode.
      directories.filter((path, _) => replaced.exists(path.startsWith(_))).foreach { (path, value) =>
        value.cancel()
        directories -= path
      }
      val affected = entries.collect { case (path, entry) if overflow ||
        entry.dependencies.exists(dependency => changed.exists(dependency.startsWith(_))) => path }.toSet
      if affected.nonEmpty then refresh(affected)
    }
  }

  private def loop: IO[Unit] =
    (IO.interruptibleMany(watcher.take()).flatMap(key => IO.blocking(handle(key)))).foreverM

  private[graaljs] def watchedDirectories: IO[Set[Path]] = IO.blocking { synchronized { directories.keySet } }

  private def clear: IO[Unit] = IO.blocking { synchronized {
    directories.values.foreach(_.cancel())
    directories = Map.empty
    entries = Map.empty
  } }

private[groot] object ScriptSources:
  def resource: Resource[IO, ScriptSources] = for
    watcher <- Resource.make(IO.blocking(java.nio.file.FileSystems.getDefault.newWatchService()))(value => IO.blocking(value.close()))
    sources = new ScriptSources(watcher)
    _ <- Resource.onFinalize(sources.clear)
    _ <- sources.loop.background
  yield sources
