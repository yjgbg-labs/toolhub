package groot.github

import cats.effect.IO
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

final case class RepoRef(owner: String, repo: String):
  val fullName: String = s"$owner/$repo"

object RepoResolver:
  private val ownerRepoRe = "^([A-Za-z0-9][A-Za-z0-9_.-]{0,99})/([A-Za-z0-9_.-]{1,100})$".r
  private val httpsRe = "^https?://(?:[^@/]+@)?github\\.com/([A-Za-z0-9][A-Za-z0-9_.-]{0,99})/([A-Za-z0-9_.-]{1,100}?)(?:\\.git)?/?$".r
  private val sshRe = "^(?:ssh://)?git@github\\.com[:/]([A-Za-z0-9][A-Za-z0-9_.-]{0,99})/([A-Za-z0-9_.-]{1,100}?)(?:\\.git)?/?$".r
  private val sectionRe = "^\\[remote\\s+\"([^\"]+)\"\\]$".r
  private val urlRe = "^url\\s*=\\s*(.+)$".r

  def resolve(explicitRepo: Option[String], cwd: Option[String]): IO[RepoRef] =
    explicitRepo.map(_.trim).filter(_.nonEmpty) match
      case Some(raw) => IO(parseExplicit(raw))
      case None => cwd.map(_.trim).filter(_.nonEmpty) match
        case Some(dir) => IO.blocking(inferFromCwd(Paths.get(dir)))
        case None => IO.raiseError(GitHubFailure("invalid_github", "Pass repo ('owner/repo') or run inside a GitHub git checkout"))

  def parseExplicit(raw: String): RepoRef =
    val clean = raw.trim.stripSuffix("/")
    val (o, r) = clean match
      case httpsRe(o, n) => o -> n.stripSuffix(".git")
      case sshRe(o, n) => o -> n.stripSuffix(".git")
      case ownerRepoRe(o, n) => o -> n.stripSuffix(".git")
      case _ => throw GitHubFailure("invalid_github", s"Invalid repository '$raw': expected 'owner/repo'")
    if r.isEmpty || r == "." || r == ".." || o == "." || o == ".." then
      throw GitHubFailure("invalid_github", s"Invalid repository '$raw': expected 'owner/repo'")
    RepoRef(o, r)

  private def inferFromCwd(startDir: Path): RepoRef =
    val norm = try startDir.toAbsolutePath.normalize() catch case _: Exception =>
      throw GitHubFailure("invalid_github", s"Invalid working directory: $startDir")
    val gitEntry = Iterator.iterate(if Files.isRegularFile(norm) then norm.getParent else norm)(_.getParent)
      .takeWhile(_ != null).map(_.resolve(".git")).find(Files.exists(_))
      .getOrElse(throw GitHubFailure("invalid_github", s"No git repository at or above '$norm'; pass repo explicitly"))
    val configPath = resolveGitConfig(gitEntry)
    if !Files.isRegularFile(configPath) then
      throw GitHubFailure("invalid_github", s"Git config missing at '$configPath'; pass repo explicitly")
    val remotes = parseRemotes(Files.readString(configPath, UTF_8))
    val urls = remotes.get("origin").toVector ++ remotes.get("upstream").toVector ++
      remotes.iterator.filterNot((k, _) => k == "origin" || k == "upstream").map(_._2).toVector
    urls.iterator.flatMap { u =>
      u.trim match
        case httpsRe(o, n) => Some(RepoRef(o, n.stripSuffix(".git")))
        case sshRe(o, n) => Some(RepoRef(o, n.stripSuffix(".git")))
        case _ => None
    }.nextOption().getOrElse(throw GitHubFailure("invalid_github", s"No GitHub remote in '$configPath'; pass repo explicitly"))

  private def resolveGitConfig(dotGit: Path): Path =
    if Files.isDirectory(dotGit) then dotGit.resolve("config")
    else
      val line = Files.readString(dotGit, UTF_8).linesIterator.map(_.trim).find(_.nonEmpty).getOrElse("")
      if !line.startsWith("gitdir:") then throw GitHubFailure("invalid_github", s"Malformed .git file at '$dotGit'")
      val raw = Paths.get(line.stripPrefix("gitdir:").trim)
      val gitDir = (if raw.isAbsolute then raw else dotGit.getParent.resolve(raw)).normalize()
      val commonFile = gitDir.resolve("commondir")
      val commonDir = if Files.isRegularFile(commonFile) then
        val rel = Paths.get(Files.readString(commonFile, UTF_8).trim)
        (if rel.isAbsolute then rel else gitDir.resolve(rel)).normalize()
      else gitDir
      val shared = commonDir.resolve("config")
      if Files.isRegularFile(shared) then shared else gitDir.resolve("config")

  private def parseRemotes(text: String): Map[String, String] =
    val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
    var current: Option[String] = None
    text.linesIterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#") && !l.startsWith(";")).foreach {
      case sectionRe(name) => current = Some(name)
      case l if l.startsWith("[") => current = None
      case urlRe(url) => current.foreach(r => if !out.contains(r) then out(r) = url.trim.stripPrefix("\"").stripSuffix("\""))
      case _ => ()
    }
    out.toMap
