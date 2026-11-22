package groot.github

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import framework.rpc.PlainText
import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import scala.concurrent.duration.*

final class GitHubAuth private (
    authFile: Path, state: Ref[IO, Option[StoredGitHubAuth]], mutex: Mutex[IO],
    client: GitHubClient, defaultClientId: String
):
  def requireToken: IO[String] = state.get.flatMap {
    case Some(s) if s.token.nonEmpty => IO.pure(s.token)
    case _ => IO.raiseError(GitHubFailure("github_unauthenticated", "Not logged in to GitHub; run 'groot-cli github login'"))
  }

  def logout: IO[LogoutResult] = mutex.lock.surround {
    state.getAndSet(None).flatTap(_ => IO.blocking(Files.deleteIfExists(authFile))).map { prev =>
      LogoutResult(loggedOut = prev.isDefined, login = prev.map(_.login))
    }
  }

  def login(token: Option[String], scopes: String, clientId: Option[String]): Stream[IO, PlainText] =
    token.map(_.trim).filter(_.nonEmpty) match
      case Some(raw) => Stream.eval(verifyAndPersist(raw).map(renderSuccess))
      case None =>
        val cid = clientId.map(_.trim).filter(_.nonEmpty).getOrElse(defaultClientId)
        val cleanScope = scopes.split("[,\\s]+").iterator.map(_.trim).filter(_.nonEmpty).mkString(" ")
        deviceFlowStream(cid, cleanScope)

  private def deviceFlowStream(clientId: String, scope: String): Stream[IO, PlainText] =
    Stream.eval(client.postOAuthForm("/login/device/code", Vector("client_id" -> clientId, "scope" -> scope))).flatMap { json =>
      val c = json.hcursor
      val verifyUri = c.get[String]("verification_uri").toOption.filter(_.nonEmpty).getOrElse("https://github.com/login/device")
      val expiresIn = math.max(10, c.get[Int]("expires_in").getOrElse(900))
      val interval = math.max(1, c.get[Int]("interval").getOrElse(5)).seconds
      (c.get[String]("device_code").toOption.filter(_.nonEmpty), c.get[String]("user_code").toOption.filter(_.nonEmpty)) match
        case (Some(dc), Some(uc)) =>
          val prompt: Stream[IO, String] = Stream.emits(List(
            s"! One-time code: $uc",
            s"Open $verifyUri in your browser to authorize",
            s"Waiting for authorization (expires in ${expiresIn}s, Ctrl+C to cancel)..."
          ))
          val finish: Stream[IO, String] = Stream.eval(
            IO.realTimeInstant.flatMap(t0 => pollToken(clientId, dc, interval, t0.plusSeconds(expiresIn.toLong)))
              .flatMap(verifyAndPersist).map(s => (renderSuccess(s): String))
          )
          (prompt ++ finish).map(PlainText(_))
        case _ =>
          val desc = c.get[String]("error_description").orElse(c.get[String]("error")).getOrElse(json.noSpaces.take(200))
          Stream.raiseError[IO](GitHubFailure("github_oauth", s"Failed to start GitHub device flow: $desc"))
    }

  private def pollToken(clientId: String, deviceCode: String, interval: FiniteDuration, deadline: Instant): IO[String] =
    IO.sleep(interval) *> IO.realTimeInstant.flatMap { now =>
      if !now.isBefore(deadline) then IO.raiseError(GitHubFailure("github_timeout", "GitHub device code expired"))
      else client.postOAuthForm("/login/oauth/access_token", Vector(
        "client_id" -> clientId, "device_code" -> deviceCode, "grant_type" -> "urn:ietf:params:oauth:grant-type:device_code"
      )).flatMap { json =>
        val c = json.hcursor
        c.get[String]("access_token").toOption.filter(_.nonEmpty) match
          case Some(tok) => IO.pure(tok)
          case None => c.get[String]("error").toOption.getOrElse("unknown_error") match
            case "authorization_pending" => pollToken(clientId, deviceCode, interval, deadline)
            case "slow_down" => pollToken(clientId, deviceCode, c.get[Int]("interval").toOption.map(_.seconds).getOrElse(interval + 5.seconds), deadline)
            case "access_denied" => IO.raiseError(GitHubFailure("github_forbidden", "Authorization was denied in browser"))
            case "expired_token" => IO.raiseError(GitHubFailure("github_timeout", "GitHub device code expired"))
            case other => IO.raiseError(GitHubFailure("github_oauth", s"Device authorization failed: ${c.get[String]("error_description").getOrElse(other)}"))
      }
    }

  private def verifyAndPersist(token: String): IO[StoredGitHubAuth] =
    for
      (headers, userJson) <- client.getJson(token, "/user")
      now <- IO.realTimeInstant
      login <- IO.fromEither(userJson.hcursor.get[String]("login")).adaptError(_ => GitHubFailure("github_upstream", "Missing login in /user"))
      name = userJson.hcursor.get[Option[String]]("name").toOption.flatten.map(_.trim).filter(_.nonEmpty)
      stored = StoredGitHubAuth(token, login, name, GitHubAuth.parseScopes(headers), now.toString)
      _ <- mutex.lock.surround(writeDisk(authFile, stored) *> state.set(Some(stored)))
    yield stored

  private def renderSuccess(s: StoredGitHubAuth): PlainText =
    PlainText(s"✓ Logged in to github.com as ${s.login}${if s.scopes.nonEmpty then s" (scopes: ${s.scopes.mkString(", ")})" else ""}")

  private def writeDisk(target: Path, auth: StoredGitHubAuth): IO[Unit] = IO.blocking {
    val dir = target.getParent
    if !Files.exists(dir) then { Files.createDirectories(dir); setPerms(dir, "rwx------") }
    val tmp = Files.createTempFile(dir, ".auth-", ".tmp")
    try
      setPerms(tmp, "rw-------")
      Files.writeString(tmp, auth.asJson.spaces2 + "\n", UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      setPerms(target, "rw-------")
    finally { Files.deleteIfExists(tmp); () }
  }

  private def setPerms(p: Path, perms: String): Unit =
    try Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms)) catch case _: UnsupportedOperationException => ()

object GitHubAuth:
  val defaultOAuthClientId = "178c6fc778ccc68e1d6a"
  val defaultScopes = "repo,workflow,gist,notifications,read:org"

  def parseScopes(headers: Map[String, String]): Vector[String] =
    headers.get("x-oauth-scopes").toVector.flatMap(_.split(",")).map(_.trim).filter(_.nonEmpty).distinct

  def resource(home: Path, client: GitHubClient,
      defaultClientId: String = sys.env.get("GITHUB_CLIENT_ID").filter(_.nonEmpty).getOrElse(defaultOAuthClientId)): Resource[IO, GitHubAuth] =
    Resource.eval {
      val authFile = home.resolve("github").resolve("auth.json")
      for
        init <- IO.blocking(if Files.isRegularFile(authFile) then decode[StoredGitHubAuth](Files.readString(authFile, UTF_8)).toOption.filter(_.token.trim.nonEmpty) else None)
        ref <- Ref.of[IO, Option[StoredGitHubAuth]](init)
        mutex <- Mutex[IO]
      yield new GitHubAuth(authFile, ref, mutex, client, defaultClientId)
    }
