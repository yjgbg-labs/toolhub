package groot.fxtwitter

import cats.effect.{Deferred, IO, Resource}
import com.comcast.ip4s.*
import framework.rpcDsl.*
import groot.graaljs.{GraalCommandSdk, GraalJsRoutes, GraalManager, JavaScript}
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import org.http4s.{Header, Headers, HttpApp, Method, Response, Status, Uri}
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class FxTwitterSuite extends CatsEffectSuite:
  private def temporary: Resource[IO, Path] = Resource.make(
    IO.blocking { Files.createDirectories(Path.of("/tmp/opencode")); Files.createTempDirectory(Path.of("/tmp/opencode"), "fxtwitter-test-") }
  )(p => IO.blocking { val fs = Files.walk(p); try fs.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists) finally fs.close() })

  private def serve(app: HttpApp[IO]): Resource[IO, Uri] = EmberServerBuilder.default[IO]
    .withHost(ipv4"127.0.0.1").withPort(port"0").withHttpApp(app).build.map(s => Uri.unsafeFromString(s"http://127.0.0.1:${s.address.getPort}"))

  private def j(raw: String): Json = parse(raw).fold(throw _, identity)
  private def jsonResp(status: Status, body: String): Response[IO] =
    Response[IO](status = status, headers = Headers(Header.Raw(CIString("Content-Type"), "application/json"))).withEntity(j(body).noSpaces)

  private def awaitUntil[A](action: IO[A])(check: A => Boolean): IO[A] =
    def loop: IO[A] = action.flatMap(v => if check(v) then IO.pure(v) else IO.sleep(15.millis) *> IO.defer(loop))
    loop.timeout(15.seconds)

  test("parseTweetId and parseHandle accept raw identifiers and X/Twitter URLs") {
    assertEquals(FxTwitterService.parseTweetId("2124655797879894316"), "2124655797879894316")
    assertEquals(FxTwitterService.parseTweetId("https://x.com/elonmusk/status/2124655797879894316?s=20"), "2124655797879894316")
    assertEquals(FxTwitterService.parseTweetId("https://twitter.com/i/web/statuses/20"), "20")
    assertEquals(FxTwitterService.parseHandle("@elonmusk"), "elonmusk")
    assertEquals(FxTwitterService.parseHandle("https://x.com/elonmusk?lang=en"), "elonmusk")
  }

  test("GraalJS tools.fxTwitter.* end-to-end: projects tweets, articles, tombstones, flattened timeline threads, trends and upstream 503") {
    val mock = HttpApp[IO] { req =>
      (req.method, req.uri.path.renderString) match
        case (Method.GET, "/2/status/2124655797879894316") =>
          val tr = if req.uri.query.params.get("lang").contains("zh") then """"translation":{"text":"中文翻译"},""" else ""
          IO.pure(jsonResp(Status.Ok, s"""{
            "code": 200,
            "status": {
              "type": "status", "id": "2124655797879894316", "url": "https://x.com/elonmusk/status/2124655797879894316",
              "text": "",
              $tr
              "author": {"id": "44196397", "screen_name": "elonmusk", "name": "Elon Musk", "verification": {"verified": true, "type": "individual"}},
              "likes": 1000, "reposts": 120, "replies": 45, "quotes": 12, "bookmarks": 80, "views": 50000,
              "created_at": "Mon Nov 23 00:06:31 +0000 2026", "created_timestamp": 1795392391,
              "reposted_by": {"screen_name": "someone"},
              "quote": {"type": "tombstone", "reason": "deleted", "message": "This Post was deleted."},
              "media": {"photos": [{"type": "photo", "url": "https://pbs.twimg.com/media/1.jpg", "width": 800, "height": 600, "altText": "pic"}]},
              "article": {"id": "art_1", "title": "Long Essay", "preview_text": "Preview", "created_at": "2026-11-23", "content": {"blocks": [{"text": "Para 1"}, {"text": "Para 2"}]}}
            }
          }"""))

        case (Method.GET, "/2/profile/elonmusk") =>
          IO.pure(jsonResp(Status.Ok, """{
            "code": 200,
            "user": {
              "id": "44196397", "screen_name": "elonmusk", "name": "Elon Musk", "description": "Tech",
              "followers": 240000000, "following": 1400, "statuses": 110000, "media_count": 4700, "likes": 250000,
              "protected": false, "url": "https://x.com/elonmusk",
              "verification": {"verified": true, "type": "individual"},
              "about_account": {"based_in": "United States", "created_country_accurate": true, "source": "App Store", "username_changes": {"count": 1, "last_changed_at": null}}
            }
          }"""))

        case (Method.GET, "/2/profile/elonmusk/statuses") =>
          IO.pure(jsonResp(Status.Ok, """{
            "code": 200,
            "cursor": {"top": "c_top", "bottom": "c_bot"},
            "results": [
              {"type": "status", "id": "101", "url": "https://x.com/elonmusk/status/101", "text": "Single post", "likes": 10},
              {"type": "thread", "conversation_id": "102", "truncated": false, "statuses": [
                {"type": "status", "id": "102", "url": "https://x.com/elonmusk/status/102", "text": "Thread 1"},
                {"type": "status", "id": "103", "url": "https://x.com/elonmusk/status/103", "text": "Thread 2"}
              ]}
            ]
          }"""))

        case (Method.GET, "/2/conversation/101") =>
          IO.pure(jsonResp(Status.Ok, """{
            "code": 200,
            "cursor": {"bottom": "rep_next"},
            "status": {"type": "status", "id": "101", "url": "https://x.com/elonmusk/status/101", "text": "Root"},
            "thread": [],
            "replies": [
              {"type": "status", "id": "201", "url": "https://x.com/u/status/201", "text": "Nice!"},
              {"type": "tombstone", "reason": "suspended", "message": "Account suspended"}
            ]
          }"""))

        case (Method.GET, "/2/trends") =>
          IO.pure(jsonResp(Status.Ok, """{"code":200,"timeline_type":"trending","trends":[{"name":"#AI","rank":1,"context":"Technology"}],"cursor":{"bottom":"t_bot"}}"""))

        case (Method.GET, "/2/search") =>
          // FxTwitter returns HTTP 200 or 503 with top-level code=503
          IO.pure(jsonResp(Status.Ok, """{"code":503,"message":"X/Twitter search is unavailable due to current API limitations."}"""))

        case _ =>
          IO.pure(jsonResp(Status.NotFound, """{"code":404,"message":"Not found"}"""))
    }

    temporary.use { root =>
      serve(mock).use { uri =>
        for
          commands <- Deferred[IO, GraalCommandSdk]
          _ <- FxTwitterService.resource(baseUri = uri).use { svc =>
            GraalManager.resource(root.resolve("graal"), commands.get).use { manager =>
              val app = httpApp { GraalJsRoutes.register(manager); FxTwitterRoutes.register(svc) }
              for
                _ <- commands.complete(GraalCommandSdk(app))
                sub <- manager.submit(JavaScript.source("""
                  const fx = tools.fxTwitter;
                  const st = (await fx.status({ id: "https://x.com/elonmusk/status/2124655797879894316", lang: "zh" })).body;
                  const usr = (await fx.user({ handle: "@elonmusk", aboutAccount: true })).body;
                  const tl = (await fx.timeline({ handle: "https://x.com/elonmusk" })).body;
                  const conv = (await fx.conversation({ id: "101" })).body;
                  const tr = (await fx.trends({ count: 5 })).body;
                  let searchCode = 0;
                  try { await fx.api({ path: "/2/search", query: { q: "test" } }); }
                  catch (e) { searchCode = e.response.status; }
                  console.log(JSON.stringify({
                    articleTitle: st.article.title,
                    articleBody: st.article.content,
                    translation: st.translation,
                    quoteTombstone: st.quote.tombstoneReason,
                    mediaCount: st.media.length,
                    repostedBy: st.repostedBy,
                    basedIn: usr.aboutAccount.basedIn,
                    tlIds: tl.items.map(i => i.id),
                    tlCursor: tl.cursor.bottom,
                    replyTombstone: conv.replies[1].tombstoneReason,
                    topTrend: tr.trends[0].name,
                    searchCode
                  }));
                """), "5s", "15s")
                done <- awaitUntil(manager.status(sub.id))(_.terminal)
                log <- manager.logs(sub.id).map(_.toString)
                _ = assertEquals(done.state, "succeeded", log)
                s = j(log.linesIterator.find(_.startsWith("{")).getOrElse(fail(log))).hcursor
                _ = assertEquals(s.get[String]("articleTitle"), Right("Long Essay"))
                _ = assertEquals(s.get[String]("articleBody"), Right("Para 1\nPara 2"))
                _ = assertEquals(s.get[String]("translation"), Right("中文翻译"))
                _ = assertEquals(s.get[String]("quoteTombstone"), Right("deleted"))
                _ = assertEquals(s.get[Int]("mediaCount"), Right(1))
                _ = assertEquals(s.get[String]("repostedBy"), Right("someone"))
                _ = assertEquals(s.get[String]("basedIn"), Right("United States"))
                _ = assertEquals(s.get[Vector[String]]("tlIds"), Right(Vector("101", "102", "103")))
                _ = assertEquals(s.get[String]("tlCursor"), Right("c_bot"))
                _ = assertEquals(s.get[String]("replyTombstone"), Right("suspended"))
                _ = assertEquals(s.get[String]("topTrend"), Right("#AI"))
                _ = assertEquals(s.get[Int]("searchCode"), Right(503))
              yield ()
            }
          }
        yield ()
      }
    }
  }
