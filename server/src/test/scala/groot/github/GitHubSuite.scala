package groot.github

import cats.effect.{Deferred, IO, Ref, Resource}
import com.comcast.ip4s.*
import framework.rpcDsl.*
import groot.graaljs.{GraalCommandSdk, GraalJsRoutes, GraalManager, JavaScript}
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.Base64
import munit.CatsEffectSuite
import org.http4s.{Header, Headers, HttpApp, Method, Response, Status, Uri}
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class GitHubSuite extends CatsEffectSuite:
  private def temporary: Resource[IO, Path] = Resource.make(
    IO.blocking { Files.createDirectories(Path.of("/tmp/opencode")); Files.createTempDirectory(Path.of("/tmp/opencode"), "github-test-") }
  )(p => IO.blocking { val fs = Files.walk(p); try fs.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists) finally fs.close() })

  private def serve(app: HttpApp[IO]): Resource[IO, Uri] = EmberServerBuilder.default[IO]
    .withHost(ipv4"127.0.0.1").withPort(port"0").withHttpApp(app).build.map(s => Uri.unsafeFromString(s"http://127.0.0.1:${s.address.getPort}"))

  private def j(raw: String): Json = parse(raw).fold(throw _, identity)
  private def jsonResp(status: Status, body: String, extra: (String, String)*): Response[IO] =
    val hdrs = Headers((Header.Raw(CIString("Content-Type"), "application/json") +: extra.map((k, v) => Header.Raw(CIString(k), v))).toList)
    Response[IO](status = status, headers = hdrs).withEntity(j(body).noSpaces)

  private def awaitUntil[A](action: IO[A])(check: A => Boolean): IO[A] =
    def loop: IO[A] = action.flatMap(v => if check(v) then IO.pure(v) else IO.sleep(15.millis) *> IO.defer(loop))
    loop.timeout(15.seconds)

  test("RepoResolver infers owner/repo from standard git checkout, nested subdir, and git worktree commondir") {
    temporary.use { root =>
      for
        dirs <- IO.blocking {
          val mainRepo = root.resolve("groot-cli")
          val wtGitDir = mainRepo.resolve(".git/worktrees/feat-github")
          Files.createDirectories(wtGitDir)
          Files.writeString(mainRepo.resolve(".git/config"), "[remote \"origin\"]\n\turl = git@github.com:yjgbg-labs/toolhub.git\n", UTF_8)
          Files.writeString(wtGitDir.resolve("commondir"), "../..\n", UTF_8)
          val wtDir = root.resolve("worktrees/feat-github")
          val nested = wtDir.resolve("server/src")
          Files.createDirectories(nested)
          Files.writeString(wtDir.resolve(".git"), s"gitdir: ${wtGitDir.toAbsolutePath}\n", UTF_8)
          (mainRepo, nested)
        }
        r1 <- RepoResolver.resolve(None, Some(dirs._1.toString))
        r2 <- RepoResolver.resolve(None, Some(dirs._2.toString))
        r3 <- RepoResolver.resolve(Some("https://github.com/MetaCubeX/mihomo.git"), Some(dirs._2.toString))
      yield
        assertEquals(r1, RepoRef("yjgbg-labs", "toolhub"))
        assertEquals(r2, RepoRef("yjgbg-labs", "toolhub"))
        assertEquals(r3, RepoRef("MetaCubeX", "mihomo"))
    }
  }

  test("CLI-only OAuth Device Flow login, persistence across restarts, and clean logout") {
    temporary.use { home =>
      Ref.of[IO, Int](0).flatMap { polls =>
        val mock = HttpApp[IO] { req =>
          (req.method, req.uri.path.renderString) match
            case (Method.POST, "/login/device/code") =>
              IO.pure(jsonResp(Status.Ok, """{"device_code":"dev_1","user_code":"ABCD-9999","verification_uri":"https://github.com/login/device","expires_in":600,"interval":1}"""))
            case (Method.POST, "/login/oauth/access_token") =>
              polls.updateAndGet(_ + 1).map(n => jsonResp(Status.Ok, if n == 1 then """{"error":"authorization_pending"}""" else """{"access_token":"gho_dev"}"""))
            case (Method.GET, "/user") if req.headers.get(CIString("Authorization")).exists(_.head.value == "Bearer gho_dev") =>
              IO.pure(jsonResp(Status.Ok, """{"login":"yjgbg","id":42,"name":"Yjgbg"}""",
                "X-OAuth-Scopes" -> "repo, workflow, notifications", "X-RateLimit-Limit" -> "5000", "X-RateLimit-Remaining" -> "4991", "X-RateLimit-Reset" -> "1800000000"))
            case _ => IO.pure(jsonResp(Status.Unauthorized, """{"message":"Bad credentials"}"""))
        }
        serve(mock).use { uri =>
          for
            _ <- GitHubService.resource(home, apiBase = uri, oauthBase = uri).use { svc =>
              for
                unauth <- svc.whoami.attempt
                _ = assert(unauth.left.exists(_.asInstanceOf[GitHubFailure].code == "github_unauthenticated"))
                sse <- svc.login(None, GitHubAuth.defaultScopes, Some("cid"))
                events <- sse.events.compile.toVector.map(_.map(_.toString))
                _ = assert(events.exists(_.contains("ABCD-9999")) && events.last.contains("Logged in to github.com as yjgbg"))
                who <- svc.whoami
                _ = assertEquals((who.login, who.scopes, who.rateLimit.map(_.remaining)), ("yjgbg", Vector("repo", "workflow", "notifications"), Some(4991)))
              yield ()
            }
            _ <- GitHubService.resource(home, apiBase = uri, oauthBase = uri).use { svc2 =>
              for
                who2 <- svc2.whoami
                _ = assertEquals(who2.login, "yjgbg")
                out <- svc2.logout
                _ = assertEquals(out, LogoutResult(loggedOut = true, login = Some("yjgbg")))
                after <- svc2.whoami.attempt
                _ = assert(after.left.exists(_.asInstanceOf[GitHubFailure].code == "github_unauthenticated"))
              yield ()
            }
          yield ()
        }
      }
    }
  }

  test("GraalJS tools.github.* end-to-end: hides login/logout, infers repo from cwd, strips auth on log redirects, and works with or without cwd context") {
    temporary.use { root =>
      val blobApp = HttpApp[IO] { req =>
        if req.headers.get(CIString("Authorization")).isDefined then IO.pure(Response[IO](Status.BadRequest).withEntity("Leaked Authorization!"))
        else IO.pure(Response[IO](Status.Ok).withEntity((1 to 8).map(i => s"line $i").mkString("\n") + "\nERROR: assertion failed in FooSuite"))
      }
      serve(blobApp).use { blobUri =>
        Ref.of[IO, Vector[String]](Vector.empty).flatMap { deletedRefs =>
          mockGitHubApi(blobUri, deletedRefs).use { apiUri =>
            val repoDir = root.resolve("checkout")
            for
              _ <- IO.blocking {
                Files.createDirectories(repoDir.resolve(".git"))
                Files.writeString(repoDir.resolve(".git/config"), "[remote \"origin\"]\n\turl = https://github.com/yjgbg-labs/toolhub.git\n", UTF_8)
              }
              commands <- Deferred[IO, GraalCommandSdk]
              _ <- GitHubService.resource(root.resolve("groot-home"), apiBase = apiUri, oauthBase = apiUri).use { ghSvc =>
                GraalManager.resource(root.resolve("graal"), commands.get).use { manager =>
                  val app = httpApp { GraalJsRoutes.register(manager); GitHubRoutes.register(ghSvc) }
                  for
                    _ <- commands.complete(GraalCommandSdk(app))
                    sse <- ghSvc.login(Some("ghp_valid"), GitHubAuth.defaultScopes, None)
                    _ <- sse.events.compile.drain
                    sub1 <- manager.submit(JavaScript.source("""
                      if (tools.github.login !== undefined || tools.github.logout !== undefined) throw Error("leaked cli auth");
                      const repo = (await tools.github.repo.get()).body;
                      const readme = (await tools.github.repo.read({ path: "README.md" })).body;
                      const pr = (await tools.github.pr.get({ number: 55 })).body;
                      const createdPr = (await tools.github.pr.create({ title: "feat: github", head: "feat/github", body: "PR body" })).body;
                      const merged = (await tools.github.pr.merge({ number: 55, method: "squash", deleteBranch: true })).body;
                      const issues = (await tools.github.issue.list()).body;
                      const failLogs = (await tools.github.run.logs({ runId: 9001, tailLines: 3 })).body;
                      const tags = (await tools.github.api({ path: "/repos/{owner}/{repo}/tags", paginate: true })).body;
                      console.log(JSON.stringify({
                        repo: repo.fullName, readme: readme.content,
                        checks: [pr.checks.total, pr.checks.passed, pr.checks.failed, pr.checks.pending],
                        unresolved: pr.unresolvedThreads, createdNum: createdPr.number,
                        merged: merged.merged && merged.branchDeleted, issueCount: issues.length,
                        hasFail: failLogs.includes("ERROR: assertion failed in FooSuite"), tagCount: tags.body.length
                      }));
                    """), "5s", "15s", Map("cwd" -> repoDir.toString, "sessionId" -> "ses_test"))
                    done1 <- awaitUntil(manager.status(sub1.id))(_.terminal)
                    log1 <- manager.logs(sub1.id).map(_.toString)
                    _ = assertEquals(done1.state, "succeeded", log1)
                    s = j(log1.linesIterator.find(_.startsWith("{")).getOrElse(fail(log1))).hcursor
                    _ = assertEquals((s.get[String]("repo"), s.get[String]("readme"), s.get[Vector[Int]]("checks")),
                      (Right("yjgbg-labs/toolhub"), Right("# Toolhub\n"), Right(Vector(3, 1, 1, 1))))
                    _ = assertEquals((s.get[Int]("unresolved"), s.get[Int]("createdNum"), s.get[Boolean]("merged"), s.get[Int]("issueCount"), s.get[Boolean]("hasFail"), s.get[Int]("tagCount")),
                      (Right(1), Right(56), Right(true), Right(1), Right(true), Right(2)))
                    deleted <- deletedRefs.get
                    _ = assertEquals(deleted, Vector("heads/feat/github"))

                    sub2 <- manager.submit(JavaScript.source("""
                      const rel = (await tools.github.release.get({ repo: "MetaCubeX/mihomo" })).body;
                      const created = (await tools.github.issue.create({ repo: "yjgbg-labs/toolhub", title: "Scheduled", body: "Details" })).body;
                      console.log(rel.tagName + "|" + created.number);
                    """), "5s", "15s")
                    done2 <- awaitUntil(manager.status(sub2.id))(_.terminal)
                    log2 <- manager.logs(sub2.id).map(_.toString)
                    _ = assertEquals(done2.state, "succeeded", log2)
                    _ = assert(log2.contains("v1.19.0|101"), log2)
                  yield ()
                }
              }
            yield ()
          }
        }
      }
    }
  }

  private def mockGitHubApi(blobUri: Uri, deletedRefs: Ref[IO, Vector[String]]): Resource[IO, Uri] =
    Deferred[IO, String].toResource.flatMap { selfDef =>
      val b64Readme = Base64.getEncoder.encodeToString("# Toolhub\n".getBytes(UTF_8))
      val app = HttpApp[IO] { req =>
        if !req.headers.get(CIString("Authorization")).exists(_.head.value == "Bearer ghp_valid") then
          IO.pure(jsonResp(Status.Unauthorized, """{"message":"Unauthorized"}"""))
        else (req.method, req.uri.path.renderString) match
          case (Method.GET, "/user") => IO.pure(jsonResp(Status.Ok, """{"login":"yjgbg","id":1}"""))
          case (Method.GET, "/repos/yjgbg-labs/toolhub") =>
            IO.pure(jsonResp(Status.Ok, """{"full_name":"yjgbg-labs/toolhub","private":false,"fork":false,"archived":false,"default_branch":"main","stargazers_count":12,"forks_count":2,"open_issues_count":3,"html_url":"https://github.com/yjgbg-labs/toolhub"}"""))
          case (Method.GET, "/repos/yjgbg-labs/toolhub/contents/README.md") =>
            IO.pure(jsonResp(Status.Ok, s"""{"type":"file","path":"README.md","size":10,"sha":"abc","content":"$b64Readme","html_url":"https://github.com/yjgbg-labs/toolhub/blob/main/README.md"}"""))
          case (Method.POST, "/graphql") =>
            IO.pure(jsonResp(Status.Ok, """{"data":{"repository":{"pullRequest":{"number":55,"title":"feat: github","state":"OPEN","isDraft":false,"headRefName":"feat/github","headRefOid":"deadbeef","baseRefName":"main","mergeable":"MERGEABLE","mergeStateStatus":"BLOCKED","reviewDecision":"APPROVED","additions":100,"deletions":5,"changedFiles":4,"body":"Summary","createdAt":"2026-11-22T15:00:00Z","updatedAt":"2026-11-22T16:00:00Z","url":"https://github.com/yjgbg-labs/toolhub/pull/55","author":{"login":"yjgbg"},"labels":{"nodes":[{"name":"enhancement"}]},"comments":{"totalCount":2},"reviewThreads":{"nodes":[{"isResolved":true},{"isResolved":false}]},"commits":{"nodes":[{"commit":{"statusCheckRollup":{"state":"FAILURE","contexts":{"nodes":[{"__typename":"CheckRun","name":"server-test","status":"COMPLETED","conclusion":"SUCCESS"},{"__typename":"CheckRun","name":"cli-test","status":"COMPLETED","conclusion":"FAILURE"},{"__typename":"StatusContext","context":"deploy/preview","state":"PENDING"}]}}}}]}}}}}"""))
          case (Method.POST, "/repos/yjgbg-labs/toolhub/pulls") =>
            req.as[String].map { raw => val c = j(raw).hcursor
              jsonResp(Status.Created, s"""{"number":56,"title":"${c.get[String]("title").getOrElse("")}","state":"open","draft":false,"user":{"login":"yjgbg"},"head":{"ref":"feat/github"},"base":{"ref":"main"},"updated_at":"2026-11-22T16:00:00Z","html_url":"https://github.com/yjgbg-labs/toolhub/pull/56"}""")
            }
          case (Method.GET, "/repos/yjgbg-labs/toolhub/pulls/55") =>
            IO.pure(jsonResp(Status.Ok, """{"number":55,"head":{"ref":"feat/github","repo":{"full_name":"yjgbg-labs/toolhub"}},"base":{"ref":"main"}}"""))
          case (Method.PUT, "/repos/yjgbg-labs/toolhub/pulls/55/merge") =>
            IO.pure(jsonResp(Status.Ok, """{"merged":true,"sha":"mergedsha","message":"Pull Request successfully merged"}"""))
          case (Method.DELETE, "/repos/yjgbg-labs/toolhub/git/refs/heads/feat/github") =>
            deletedRefs.update(_ :+ "heads/feat/github").as(Response[IO](Status.NoContent))
          case (Method.GET, "/repos/yjgbg-labs/toolhub/issues") =>
            IO.pure(jsonResp(Status.Ok, """[{"number":10,"title":"Real bug","state":"open","user":{"login":"yjgbg"},"created_at":"2026-11-22T10:00:00Z","updated_at":"2026-11-22T11:00:00Z","html_url":"https://github.com/yjgbg-labs/toolhub/issues/10"},{"number":55,"title":"PR","state":"open","pull_request":{"url":"x"},"created_at":"2026-11-22T10:00:00Z","updated_at":"2026-11-22T11:00:00Z","html_url":"https://github.com/yjgbg-labs/toolhub/pull/55"}]"""))
          case (Method.POST, "/repos/yjgbg-labs/toolhub/issues") =>
            IO.pure(jsonResp(Status.Created, """{"number":101,"title":"Scheduled","state":"open","created_at":"2026-11-22T16:00:00Z","updated_at":"2026-11-22T16:00:00Z","html_url":"https://github.com/yjgbg-labs/toolhub/issues/101"}"""))
          case (Method.GET, "/repos/yjgbg-labs/toolhub/actions/runs/9001") =>
            IO.pure(jsonResp(Status.Ok, """{"id":9001,"name":"CI","workflow_id":1,"head_branch":"feat/github","head_sha":"deadbeef","event":"pull_request","status":"completed","conclusion":"failure","run_number":77,"created_at":"2026-11-22T16:00:00Z","updated_at":"2026-11-22T16:05:00Z","html_url":"https://github.com/yjgbg-labs/toolhub/actions/runs/9001"}"""))
          case (Method.GET, "/repos/yjgbg-labs/toolhub/actions/runs/9001/jobs") =>
            IO.pure(jsonResp(Status.Ok, """{"jobs":[{"id":11,"name":"unit-tests","status":"completed","conclusion":"failure","html_url":"u1","steps":[{"number":1,"name":"Run tests","status":"completed","conclusion":"failure"}]},{"id":12,"name":"lint","status":"completed","conclusion":"success","html_url":"u2","steps":[]}]}"""))
          case (Method.GET, "/repos/yjgbg-labs/toolhub/actions/jobs/11/logs") =>
            IO.pure(Response[IO](Status.Found, headers = Headers(Header.Raw(CIString("Location"), s"${blobUri.renderString}/logs/11.txt?sig=secret"))))
          case (Method.GET, "/repos/MetaCubeX/mihomo/releases/latest") =>
            IO.pure(jsonResp(Status.Ok, """{"id":500,"tag_name":"v1.19.0","name":"v1.19.0","target_commitish":"Alpha","draft":false,"prerelease":false,"created_at":"2026-11-20T00:00:00Z","published_at":"2026-11-20T01:00:00Z","html_url":"u","body":"notes","assets":[]}"""))
          case (Method.GET, "/repos/yjgbg-labs/toolhub/tags") =>
            selfDef.get.map { base =>
              if req.uri.query.params.getOrElse("page", "1") == "1" then jsonResp(Status.Ok, """[{"name":"v0.1.0"}]""", "Link" -> s"""<$base/repos/yjgbg-labs/toolhub/tags?page=2>; rel="next"""")
              else jsonResp(Status.Ok, """[{"name":"v0.2.0"}]""")
            }
          case _ => IO.pure(jsonResp(Status.NotFound, s"""{"message":"Unhandled ${req.method} ${req.uri.path.renderString}"}"""))
      }
      serve(app).evalTap(u => selfDef.complete(u.renderString.stripSuffix("/")))
    }
