package groot.kube

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import framework.rpcDsl.*
import groot.graaljs.{GraalCommandSdk, GraalManager, JavaScript}
import groot.graaljs.GraalTestSupport.*
import io.circe.Json
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import munit.CatsEffectSuite
import org.http4s.{HttpApp, Method, Request, Response, Status, Uri}
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString
import scala.concurrent.duration.*

class KubeSuite extends CatsEffectSuite:
  private def json(text: String): Json = io.circe.parser.parse(text).fold(throw _, identity)
  private val pod = json("""{"apiVersion":"v1","kind":"Pod","metadata":{"name":"p","namespace":"team"}}""")
  private def response(value: Json, status: Status = Status.Ok): IO[Response[IO]] = IO.pure(Response[IO](status).withEntity(value.noSpaces))
  private val discovery: Map[String, Json] = Map(
    "/api" -> json("""{"versions":["v1"]}"""),
    "/apis" -> json("""{"groups":[{"preferredVersion":{"groupVersion":"apps/v1"}},{"preferredVersion":{"groupVersion":"example.test/v1"}}]}"""),
    "/api/v1" -> json("""{"resources":[{"name":"pods","singularName":"pod","kind":"Pod","namespaced":true,"shortNames":["po"],"verbs":["get","list"]},{"name":"namespaces","singularName":"namespace","kind":"Namespace","namespaced":false,"verbs":["get","list"]},{"name":"pods/log","kind":"Pod","namespaced":true,"verbs":["get"]}]}"""),
    "/apis/apps/v1" -> json("""{"resources":[{"name":"deployments","singularName":"deployment","kind":"Deployment","namespaced":true,"verbs":["get","list","patch","delete"]}]}"""),
    "/apis/example.test/v1" -> json("""{"resources":[{"name":"gadgets","singularName":"gadget","kind":"Gadget","namespaced":true,"verbs":["get","list","patch","delete"]}]}"""))
  private def server(handler: HttpApp[IO]): Resource[IO, String] = EmberServerBuilder.default[IO].withHost(ipv4"127.0.0.1").withPort(port"0")
    .withShutdownTimeout(1.second).withHttpApp(handler).build.map(s => s"http://127.0.0.1:${s.address.getPort}")
  private def mock(handler: Request[IO] => IO[Response[IO]]): HttpApp[IO] = cats.data.Kleisli { request =>
    discovery.get(request.uri.path.renderString).fold(handler(request))(response(_))
  }
  private def get(service: KubeService, name: Option[String] = None, resource: String = "pods", ns: Option[String] = None, all: Boolean = false,
      limit: Option[Int] = None, cursor: Option[String] = None) = service.get("test", resource, name, ns, all, None, None, limit, cursor)

  test("contexts persist atomically, redact credentials, reject invalid replacement and require explicit context") {
    temporary.use { home =>
      val config = ContextConfig("http://127.0.0.1:1", token = Some("secret-token"))
      KubeService.resource(home).use { service =>
        val app = httpApp { KubeRoutes.register(service) }
        val sdk = GraalCommandSdk(app)
        val document = json(app.openApiJson)
        def responseSchema(path: String, method: String = "get"): Json =
          val ref = document.hcursor.downField("paths").downField(path).downField(method).downField("responses")
            .downField("200").downField("content").downField("application/json").downField("schema").get[String]("$ref").toOption.get
          document.hcursor.downField("components").downField("schemas").downField(ref.stripPrefix("#/components/schemas/")).focus.get
        assertEquals(responseSchema("/kube/get").hcursor.downField("properties").keys.map(_.toSet), Some(Set("items", "pagination")))
        Vector("/kube/contexts/list" -> "get", "/kube/resources" -> "get", "/kube/apply" -> "post").foreach { (path, method) =>
          val properties = responseSchema(path, method).hcursor.downField("properties")
          assertEquals(properties.keys.map(_.toSet), Some(Set("items")))
          assertEquals(properties.downField("items").get[String]("type"), Right("array"))
        }
        for
          saved <- service.putContext("test", config)
          _ = assert(!JsonModel[ContextSummary].encoder(saved).noSpaces.contains("secret-token"))
          bad <- service.putContext("test", config.copy(clientKey = Some("secret-invalid-key"))).attempt
          _ = assertEquals(bad.swap.toOption.get.asInstanceOf[KubeFailure].status, 400)
          listed <- service.listContexts
          _ = assertEquals(listed.items, Vector(saved))
          listedResponse <- sdk.call("kube.contexts.list")
          _ = assertEquals(listedResponse.hcursor.downField("body").downField("items").as[Vector[Json]], Right(Vector(JsonModel[ContextSummary].encoder(saved))))
          _ = assertEquals(listedResponse.hcursor.downField("body").keys.map(_.toSet), Some(Set("items")))
          permissions <- IO.blocking(Files.getPosixFilePermissions(home.resolve("contexts.json")))
          _ = assertEquals(PosixFilePermissions.toString(permissions), "rw-------")
          noContext <- sdk.call("kube.get", json("""{"resource":"pods"}"""))
          _ = assertEquals(noContext.hcursor.get[Int]("status"), Right(400))
          missing <- sdk.call("kube.request", json("""{"context":"absent","path":"/version"}"""))
          _ = assertEquals(missing.hcursor.get[Int]("status"), Right(404))
          _ = app.endpoints.filter(e => e.operationId.startsWith("kube.") && !e.operationId.startsWith("kube.contexts.")).foreach { e =>
            assertEquals(e.exposedOn, Set(framework.Client.cli, framework.Client.codemode, framework.Client.graaljs))
          }
          locked <- KubeService.resource(home).use(_ => IO.unit).attempt
          _ = assert(locked.isLeft)
        yield ()
      } *> KubeService.resource(home).use { service => for
        entries <- service.listContexts
        _ = assertEquals(entries.items.map(_.name), Vector("test"))
        removed <- service.deleteContext("test")
        again <- service.deleteContext("test")
        _ = assert(removed && !again)
      yield () } *> KubeService.resource(home).use(_.listContexts.map(entries => assertEquals(entries.items, Vector.empty)))
    }
  }

  test("discovery resolves aliases, groups and CRDs; SDK bodies carry items, pagination, null terminal fields and Kubernetes errors") {
    temporary.use { home => Ref.of[IO, Vector[Request[IO]]](Vector.empty).flatMap { requests =>
      val handler = mock { request => requests.update(_ :+ request) *> (request.uri.path.renderString match
        case "/api/v1/namespaces/team/pods/p" => response(pod)
        case path if path.endsWith("/missing") => response(KubeFailure.local(404, "NotFound", "missing").body, Status.NotFound)
        case _ if request.uri.query.params.get("continue").contains("last") => response(json("""{"items":[],"metadata":{"continue":"","remainingItemCount":0}}"""))
        case _ if request.uri.query.params.get("continue").contains("no-metadata") => response(json("""{"items":[]}"""))
        case _ => response(Json.obj("items" -> Json.arr(pod), "metadata" -> json("""{"continue":"a+/= b","resourceVersion":"123","remainingItemCount":5}"""))))
      }
      server(handler).use { url => KubeService.resource(home).use { service =>
        val sdk = GraalCommandSdk(httpApp { KubeRoutes.register(service) })
        for
          _ <- service.putContext("test", ContextConfig(url, "team", token = Some("token")))
          page <- sdk.call("kube.get", json("""{"context":"test","resource":"po","allNamespaces":true,"limit":1,"continue":"a+/= b","labelSelector":"app in (x,y)","fieldSelector":"status.phase=Running"}"""))
          _ = assertEquals(page.hcursor.downField("body").downField("items").as[Vector[Json]], Right(Vector(pod)))
          _ = assertEquals(page.hcursor.downField("body").downField("pagination").focus,
            Some(json("""{"continue":"a+/= b","resourceVersion":"123","remainingItemCount":5}""")))
          _ = assert(!page.hcursor.downField("headers").keys.toVector.flatten.exists(_.startsWith("x-kube-")))
          one <- get(service, Some("p"))
          _ = assertEquals(one.items, Vector(pod))
          _ = assertEquals(one.pagination, None)
          _ <- get(service, resource = "deployments.apps")
          _ <- get(service, resource = "gadgets.example.test")
          _ <- get(service, resource = "namespaces")
          seen <- requests.get
          _ = assertEquals(seen.head.uri.query.params("continue"), "a+/= b")
          _ = assertEquals(seen.head.uri.query.params("labelSelector"), "app in (x,y)")
          _ = assertEquals(seen.head.headers.headers.find(_.name == CIString("Authorization")).map(_.value), Some("Bearer token"))
          _ = assertEquals(seen.map(_.uri.path.renderString), Vector("/api/v1/pods", "/api/v1/namespaces/team/pods/p",
            "/apis/apps/v1/namespaces/team/deployments", "/apis/example.test/v1/namespaces/team/gadgets", "/api/v1/namespaces"))
          missing <- sdk.call("kube.get", json("""{"context":"test","resource":"pods","name":"missing"}"""))
          _ = assertEquals(missing.hcursor.get[Int]("status"), Right(404))
          _ = assertEquals(missing.hcursor.downField("body").get[String]("kind"), Right("Status"))
          named <- sdk.call("kube.get", json("""{"context":"test","resource":"pods","name":"p"}"""))
          _ = assertEquals(named.hcursor.downField("body").focus, Some(Json.obj("items" -> Json.arr(pod), "pagination" -> Json.Null)))
          last <- sdk.call("kube.get", json("""{"context":"test","resource":"pods","continue":"last"}"""))
          _ = assertEquals(last.hcursor.downField("body").focus, Some(json("""{"items":[],"pagination":{"continue":null,"resourceVersion":null,"remainingItemCount":0}}""")))
          noMetadata <- sdk.call("kube.get", json("""{"context":"test","resource":"pods","continue":"no-metadata"}"""))
          _ = assertEquals(noMetadata.hcursor.downField("body").downField("pagination").focus,
            Some(json("""{"continue":null,"resourceVersion":null,"remainingItemCount":null}""")))
          bad <- get(service, Some("p"), all = true).attempt
          _ = assertEquals(bad.swap.toOption.get.asInstanceOf[KubeFailure].status, 400)
          resourceList <- service.resources("test", None)
          _ = assertEquals(resourceList.items.size, 4)
          resourceResponse <- sdk.call("kube.resources", json("""{"context":"test"}"""))
          _ = assertEquals(resourceResponse.hcursor.downField("body").downField("items").as[Vector[Json]].map(_.size), Right(4))
          _ = assertEquals(resourceResponse.hcursor.downField("body").keys.map(_.toSet), Some(Set("items")))
        yield ()
      } }
    } }
  }

  test("apply uses SSA for an exact kind/version, defaults namespaces, preserves conflicts and sends DeleteOptions") {
    temporary.use { home => Ref.of[IO, Vector[(Request[IO], Json)]](Vector.empty).flatMap { requests =>
      val handler = mock { request => request.as[String].flatMap { text =>
        val body = json(text)
        requests.update(_ :+ (request -> body)) *> (if request.uri.path.renderString.endsWith("/conflict") then
          response(KubeFailure.local(409, "Conflict", "field ownership conflict").body, Status.Conflict)
        else if request.uri.path.renderString.endsWith("/missing") then response(KubeFailure.local(404, "NotFound", "missing").body, Status.NotFound)
        else response(body))
      } }
      server(handler).use { url => KubeService.resource(home).use { service =>
        val obj = json("""{"apiVersion":"example.test/v1","kind":"Gadget","metadata":{"name":"demo"},"spec":{"x":1}}""")
        val sdk = GraalCommandSdk(httpApp { KubeRoutes.register(service) })
        for
          _ <- service.putContext("test", ContextConfig(url, "team"))
          applied <- service.apply("test", ApplyRequest(Vector(obj), "test-manager", dryRun = true))
          _ = assertEquals(applied.items.head.hcursor.downField("metadata").get[String]("namespace"), Right("team"))
          conflict <- sdk.call("kube.apply", Json.obj("context" -> Json.fromString("test"), "body" -> JsonModel[ApplyRequest].encoder(
            ApplyRequest(Vector(obj.mapObject(_.add("metadata", json("""{"name":"conflict"}"""))))))))
          _ = assertEquals(conflict.hcursor.get[Int]("status"), Right(409))
          _ <- service.delete("test", "gadgets.example.test", "demo", None, "Foreground", Some(0), false, true)
          missing <- service.delete("test", "gadgets.example.test", "missing", None, "Background", None, true, false)
          _ = assertEquals(missing.hcursor.get[Int]("code"), Right(404))
          seen <- requests.get
          first = seen.head
          _ = assertEquals(first._1.method, Method.PATCH)
          _ = assertEquals(first._1.contentType.map(_.mediaType), org.http4s.MediaType.parse("application/apply-patch+yaml").toOption)
          _ = assertEquals(first._1.uri.query.params, Map("fieldManager" -> "test-manager", "force" -> "false", "dryRun" -> "All"))
          _ = assertEquals(seen(2)._1.method, Method.DELETE)
          _ = assertEquals(seen(2)._2.hcursor.get[Long]("gracePeriodSeconds"), Right(0L))
          _ = assertEquals(seen(2)._2.hcursor.get[String]("propagationPolicy"), Right("Foreground"))
          before <- requests.get
          invalid <- service.apply("test", ApplyRequest(Vector(obj, Json.obj()))).attempt
          after <- requests.get
          _ = assert(invalid.isLeft)
          _ = assertEquals(after.size, before.size)
          appliedResponse <- sdk.call("kube.apply", Json.obj("context" -> Json.fromString("test"),
            "body" -> JsonModel[ApplyRequest].encoder(ApplyRequest(Vector(obj)))))
          _ = assertEquals(appliedResponse.hcursor.downField("body").downField("items").as[Vector[Json]], Right(applied.items))
          _ = assertEquals(appliedResponse.hcursor.downField("body").keys.map(_.toSet), Some(Set("items")))
        yield ()
      } }
    } }
  }

  test("context replacement/deletion retire leased pools only after active requests finish; requests cancel promptly") {
    temporary.use { home =>
      for
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        waiting <- Deferred[IO, Unit]
        finishWaiting <- Deferred[IO, Unit]
        bodyStarted <- Deferred[IO, Unit]
        _ <- server(cats.data.Kleisli { request => request.uri.path.renderString match
          case "/slow" => entered.complete(()) *> release.get *> response(Json.fromString("old"))
          case "/wait" => waiting.complete(()) *> finishWaiting.get *> response(Json.Null)
          case "/wait-body" => IO.pure(Response[IO]().withBodyStream(fs2.Stream.emit(120.toByte).covary[IO] ++
            fs2.Stream.eval(bodyStarted.complete(())).drain ++ fs2.Stream.never[IO]))
          case _ => response(Json.fromString("new"))
        }).use { url => KubeService.resource(home).use { service => for
          _ <- service.putContext("test", ContextConfig(url))
          old <- service.request("test", "/slow").start
          _ <- entered.get
          _ <- service.putContext("test", ContextConfig(url, "other"))
          newer <- service.request("test", "/version")
          _ = assertEquals(newer, Json.fromString("new"))
          _ <- service.deleteContext("test")
          missing <- service.request("test", "/version").attempt
          _ = assertEquals(missing.swap.toOption.get.asInstanceOf[KubeFailure].status, 404)
          _ <- release.complete(())
          result <- old.joinWithNever
          _ = assertEquals(result, Json.fromString("old"))
          _ <- service.putContext("test", ContextConfig(url))
          pending <- service.request("test", "/wait").start
          _ <- waiting.get
          _ <- pending.cancel.timeout(3.seconds)
          _ <- service.deleteContext("test").timeout(3.seconds)
          _ <- finishWaiting.complete(())
          _ <- service.putContext("test", ContextConfig(url))
          bodyPending <- service.request("test", "/wait-body").start
          _ <- bodyStarted.get
          _ <- bodyPending.cancel.timeout(3.seconds)
          _ <- service.deleteContext("test").timeout(3.seconds)
        yield () } }
      yield ()
    }
  }

  test("logs are bounded text, responses are capped, redirects are not followed and raw GET stays on the configured origin") {
    temporary.use { home => Ref.of[IO, Vector[Request[IO]]](Vector.empty).flatMap { seen =>
      server(cats.data.Kleisli { request => seen.update(_ :+ request) *> (request.uri.path.renderString match
        case "/large" => IO.pure(Response[IO]().withEntity("x" * (KubeClient.maxBytes + 1)))
        case "/redirect" => IO.pure(Response[IO](Status.Found).putHeaders(org.http4s.Header.Raw(CIString("Location"), "/target")))
        case _ => IO.pure(Response[IO]().withEntity("log line\n")))
      }).use { url => KubeService.resource(home).use { service =>
        val sdk = GraalCommandSdk(httpApp { KubeRoutes.register(service) })
        for
          _ <- service.putContext("test", ContextConfig(url, "team"))
          logs <- service.logs("test", "p", None, Some("c"), 10, Some(60), true, 1000)
          _ = assertEquals(logs.toString, "log line\n")
          large <- sdk.call("kube.request", json("""{"context":"test","path":"/large"}"""))
          _ = assertEquals(large.hcursor.get[Int]("status"), Right(413))
          redirect <- sdk.call("kube.request", json("""{"context":"test","path":"/redirect"}"""))
          _ = assertEquals(redirect.hcursor.get[Int]("status"), Right(302))
          _ <- Vector("//evil.test/api", "https://evil.test/api", "/../api", "/%2e%2e/api", "/api?watch=true").traverse_(path =>
            service.request("test", path).attempt.map(result => assertEquals(result.swap.toOption.get.asInstanceOf[KubeFailure].status, 400)))
          requests <- seen.get
          _ = assertEquals(requests.size, 3)
          _ = assertEquals(requests.head.uri.query.params, Map("container" -> "c", "sinceSeconds" -> "60", "tailLines" -> "10", "previous" -> "true", "limitBytes" -> "1000", "follow" -> "false"))
        yield ()
      } }
    } }
  }

  test("real GraalJS SDK consumes items and pagination from the body without a subprocess") {
    temporary.use { home => server(mock(_ => response(Json.obj("items" -> Json.arr(pod), "metadata" -> json("""{"continue":"next"}""")))))
      .use { url => KubeService.resource(home.resolve("kube")).use { service =>
        val sdk = GraalCommandSdk(httpApp { KubeRoutes.register(service) })
        service.putContext("test", ContextConfig(url)) *> GraalManager.resource(home.resolve("tasks"), IO.pure(sdk)).use { manager =>
          for
            task <- manager.start(JavaScript.source("const r=await tools.kube.get({context:'test',resource:'pods',allNamespaces:true,limit:1}); if(r.body.items.length!==1||r.body.pagination.continue!=='next'||r.body.pagination.resourceVersion!==null||Object.keys(r.headers).some(k=>k.startsWith('x-kube-')))throw Error(JSON.stringify(r));"), "1s", "10s")
            finished <- manager.await(task.id)
          yield assertEquals(finished.state, "succeeded", finished.error.toString)
        }
      } }
    }
  }
