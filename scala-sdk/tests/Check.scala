package sprout.sdk.tests

import _root_.cats.effect.{IO, IOApp}
import _root_.zio.{ZIO, Runtime, Unsafe}
import io.circe.Json
import sprout.sdk.*
import sprout.sdk.cats.CatsExecutor
import sprout.sdk.zio.ZioExecutor
import sprout.sdk.sttp.SttpClient
import _root_.sttp.client4.testing.BackendStub
import _root_.sttp.shared.Identity
import sprout.generated.{Calls, Client, ScheduleJob}
import sprout.fixture.*

/** The identical executable suite is compiled and run on JVM, JS and Native. */
object Check extends IOApp.Simple:
  private def response(status: Int, body: String, media: String = "application/json") =
    HttpResponse(status, Vector("Content-Type" -> s"$media; charset=utf-8"), body)

  def run: IO[Unit] = for
    _ <- IO {
      val document = Catalog.parse("""{
        "openapi":"3.1.0", "info":{"title":"Unrelated API","version":"2"},
        "components":{"schemas":{"Node":{
          "type":"object", "required":["id"],
          "properties":{"id":{"type":"integer","format":"int64"},"child":{"$ref":"#/components/schemas/Node"}}
        }}},
        "paths":{"/items/{id}":{"patch":{
          "operationId":"items.edit",
          "parameters":[{"name":"id","in":"path","schema":{"type":"string"}}],
          "requestBody":{"required":true,"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Node"}}}},
          "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Node"}}}}}
        }}}
      }""")
      val discovery = Catalog.discover()
      assert(discovery.request == HttpRequest("GET", "/openapi.json"))
      val catalog = discovery.decode(response(200, document.noSpaces)).toOption.get
      val backend = BackendStub.synchronous
        .whenRequestMatches(_.uri.path.lastOption.contains("openapi.json")).thenRespondAdjust(document.noSpaces)
        .whenAnyRequest.thenRespondAdjust("updated")
      val connected: DynamicClient[Identity] = SttpClient.connect(backend, "http://unused.invalid")
      assert(connected.call("items.edit", Catalog.parse("""{"path":{"id":"item"},"body":{"id":1}}""")).body == "updated")
      val dynamic = catalog.call("items.edit", Catalog.parse("""{"path":{"id":"a /空+"},"body":{"id":9223372036854775807,"child":{"id":2}}}"""))
      assert(dynamic.request.method == "PATCH" && dynamic.request.path == "/items/a%20%2F%E7%A9%BA%2B")
      assert(dynamic.request.body.get.contains("9223372036854775807"))
      assert(dynamic.decode(response(404, "missing")).isLeft)
      val schema = catalog.inputSchema(catalog.byId("items.edit"))
      assert(schema.noSpaces.contains("#/$defs/ref0") && schema.hcursor.downField("$defs").downField("ref0").succeeded)
      assert(catalog.outputSchema(catalog.byId("items.edit")).hcursor.downField("$defs").succeeded)
      assert(scala.util.Try(catalog.call("items.edit", Json.obj())).isFailure)
      val json = io.circe.jawn.parse("""{"id":9223372036854775807,"value":null,"mode":"fast","optional":null,"child":{"id":1,"value":"leaf","mode":"slow-mode"}}""").toOption.get
      val tree = Tree.codec.read(json).toOption.get
      assert(tree.id == Long.MaxValue)
      assert(tree.value == None)
      assert(tree.optional == Optional.Present(None))
      assert(tree.labels == Optional.Missing)
      assert(Tree.codec.write(tree) == json)
      assert(Tree.codec.read(json.mapObject(_.remove("value"))).isLeft)
      assert(Tree.codec.read(json.mapObject(_.add("id", Json.fromString("1")))).isLeft)
      assert(Event.codec.read(io.circe.jawn.parse("""{"type":"removed","value":12}""").toOption.get).isRight)
      assert(Event.codec.read(io.circe.jawn.parse("""{"type":"removed","value":"wrong"}""").toOption.get).isLeft)
      assert(NumberOrText.codec.read(Json.fromBigInt(BigInt("999999999999999999999999999"))).isRight)

      val call = sprout.fixture.Calls.tree.update(
        id = "a /空+", tag = Optional.Present(Vector("x y", "z&")),
        joined = Optional.Present(Vector("a", "b")), `X-Token` = "token",
        session = Optional.Present("a;b"), body = tree
      )
      assert(call.request.url("http://localhost").startsWith("http://localhost/tree/a%20%2F%E7%A9%BA%2B?tag=x%20y&tag=z%26&joined=a%2Cb"))
      assert(call.request.headers.contains("X-Token" -> "token"))
      assert(call.request.headers.contains("Cookie" -> "session=a%3Bb"))
      assert(call.request.body.flatMap(body => io.circe.jawn.parse(body).toOption).contains(json))
      assert(call.decode(response(200, json.noSpaces)).isRight)
      assert(call.decode(response(202, "queued", "text/plain")) == Right(TreeUpdateResult.Status202TextPlain("queued")))
      assert(call.decode(HttpResponse(204, Vector.empty, "")) == Right(TreeUpdateResult.Status204(())))
      assert(call.decode(response(200, "not-json")).left.toOption.get.isInstanceOf[ClientError.Decode])
      assert(call.decode(response(400, "error")).left.toOption.get == ClientError.Http(response(400, "error")))
      assert(call.decode(response(200, json.noSpaces, "text/plain")).isLeft)
      assert(Calls.schedule.delete("test").request.method == "GET")
      assert(Calls.schedule.`new`("test", ScheduleJob(command = Vector("echo", "hello"), every = "1h")).request.method == "POST")
      assert(Calls.health().withResponse.decode(response(200, "ok", "text/plain")).map(_._2) == Right("ok"))
    }
    catsClient = new Client[IO](new CatsExecutor[IO](_ => IO.pure(response(200, "[]"))))
    entries <- catsClient.schedule.list()
    _ <- IO(assert(entries.isEmpty))
    error <- new CatsExecutor[IO](_ => IO.raiseError(new Exception("offline"))).execute(Calls.health()).attempt
    _ <- IO(assert(error.left.toOption.get.isInstanceOf[ClientError.Transport]))
    zioClient = new Client[[A] =>> ZIO[Any, ClientError, A]](new ZioExecutor[Any](_ => ZIO.succeed(response(200, "ok", "text/plain"))))
    value <- IO.fromFuture(IO(Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.runToFuture(zioClient.health()) }))
    _ <- IO(assert(value == "ok"))
    _ <- IO.println("Scala SDK checks passed: recursive models, int64 precision, presence/null, unions, requests, responses, Cats and ZIO")
  yield ()
