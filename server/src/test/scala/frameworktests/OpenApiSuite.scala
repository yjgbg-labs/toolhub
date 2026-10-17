package frameworktests

import cats.effect.IO
import io.circe.{Json, JsonObject}
import munit.FunSuite
import framework.httpDsl.*

class OpenApiSuite extends FunSuite:
  private def parse(app: framework.runtime.Application): Json = io.circe.parser.parse(app.openApiJson).toOption.get

  private def resolve(doc: Json, schema: Json): Json =
    schema.hcursor.get[String]("$ref").toOption match
      case None => schema
      case Some(ref) =>
        val name = ref.stripPrefix("#/components/schemas/")
        doc.hcursor.downField("components").downField("schemas").downField(name).focus.get

  private def assertReferencesResolve(doc: Json): Unit =
    def walk(json: Json): Unit =
      json.asObject.foreach { obj =>
        obj("$ref").flatMap(_.asString).foreach { ref =>
          assert(ref.startsWith("#/components/schemas/"))
          assert(doc.hcursor.downField("components").downField("schemas").downField(ref.stripPrefix("#/components/schemas/")).succeeded, ref)
        }
        obj.values.foreach(walk)
      }
      json.asArray.foreach(_.foreach(walk))
    walk(doc)

  test("group inheritance, scoped metadata, input constraints and multiple methods are documented") {
    val app = httpApp {
      openapi { info { title("Users API"); version("2.0.0"); description("API description") } }
      routes {
        group("/api") {
          group("/users/{id}") {
            tag("Users")
            get() {
              operationId("getUser")
              summary("Find a user")
              description("Operation description")
              val id = path[Long]("id") { description("User ID"); minimum(1L) }
              val limit = query[Int]("limit") { default(20); minimum(5); minimum(1); maximum(100) }
              val ok = response.json[User](200) { description("Found") }
              handle { IO.pure(ok(User(id.value, limit.value.toString))) }
            }
            post("") {
              val id = path[Long]("id")
              val input = body.json[CreateUser] { description("User payload") }
              val created = response.json[User](201) {}
              handle { IO.pure(created(User(id.value, input.value.name))) }
            }
          }
          tag("API")
        }
      }
    }
    val doc = parse(app)
    val getCursor = doc.hcursor.downField("paths").downField("/api/users/{id}").downField("get")
    assertEquals(doc.hcursor.downField("info").get[String]("description"), Right("API description"))
    assertEquals(getCursor.get[String]("description"), Right("Operation description"))
    assertEquals(getCursor.get[List[String]]("tags"), Right(List("API", "Users")))
    assertEquals(getCursor.get[String]("operationId"), Right("getUser"))
    val params = getCursor.get[List[Json]]("parameters").toOption.get
    val id = params.find(_.hcursor.get[String]("name").contains("id")).get.hcursor
    val limit = params.find(_.hcursor.get[String]("name").contains("limit")).get.hcursor
    assertEquals(id.get[String]("description"), Right("User ID"))
    assertEquals(id.get[Boolean]("required"), Right(true))
    assertEquals(limit.get[Boolean]("required"), Right(false))
    assertEquals(limit.downField("schema").get[Int]("default"), Right(20))
    assertEquals(limit.downField("schema").get[Int]("minimum"), Right(5))
    assertEquals(getCursor.downField("responses").downField("200").get[String]("description"), Right("Found"))
    assert(getCursor.downField("responses").downField("400").succeeded)
    val postCursor = doc.hcursor.downField("paths").downField("/api/users/{id}").downField("post")
    assertEquals(postCursor.downField("requestBody").get[String]("description"), Right("User payload"))
    assert(postCursor.downField("responses").downField("413").succeeded)
    assert(postCursor.downField("responses").downField("415").succeeded)
    assert(!doc.hcursor.downField("paths").downField("/openapi.json").succeeded)
    assertReferencesResolve(doc)
  }

  test("recursive and generic schemas use stable distinct component references") {
    val app = httpApp {
      routes {
        get("/users") { val ok = response.json[Page[User]](200) {}; handle { IO.pure(ok(Page(Nil))) } }
        get("/strings") { val ok = response.json[Page[String]](200) {}; handle { IO.pure(ok(Page(Nil))) } }
        get("/tree") { val ok = response.json[Tree](200) {}; handle { IO.pure(ok(Tree("root"))) } }
        get("/branch") { val ok = response.json[Branch](200) {}; handle { IO.pure(ok(Branch(Nil))) } }
      }
    }
    val doc = parse(app)
    def responseSchema(path: String) = doc.hcursor.downField("paths").downField(path).downField("get")
      .downField("responses").downField("200").downField("content").downField("application/json").downField("schema").focus.get
    assertNotEquals(responseSchema("/users"), responseSchema("/strings"))
    val strings = resolve(doc, responseSchema("/strings"))
    assertEquals(strings.hcursor.downField("properties").downField("items").downField("items").get[String]("type"), Right("string"))
    val treeRef = responseSchema("/tree")
    val tree = resolve(doc, treeRef)
    assertEquals(tree.hcursor.downField("properties").downField("children").downField("items").focus, Some(treeRef))
    assertReferencesResolve(doc)
    assertEquals(parse(app), doc)
  }

  test("model defaults, nullability and enum encoding are reflected in the same contract") {
    val app = httpApp {
      routes {
        post("/create") { val in = body.json[CreateUser]; val ok = response.json[CreateUser](200) {}; handle { IO.pure(ok(in.value)) } }
        get("/color") { val ok = response.json[Color](200) {}; handle { IO.pure(ok(Color.Red)) } }
        get("/event") { val ok = response.json[Event](200) {}; handle { IO.pure(ok(Event.Created(1L))) } }
      }
    }
    val doc = parse(app)
    val request = doc.hcursor.downField("paths").downField("/create").downField("post").downField("requestBody")
      .downField("content").downField("application/json").downField("schema").focus.get
    val schema = resolve(doc, request).hcursor
    assertEquals(schema.get[List[String]]("required"), Right(List("name")))
    assertEquals(schema.downField("properties").downField("active").get[Boolean]("default"), Right(true))
    assertEquals(schema.downField("properties").downField("nickname").get[List[String]]("type"), Right(List("string", "null")))
    val components = doc.hcursor.downField("components").downField("schemas").as[Map[String, Json]].toOption.get.values
    assert(components.exists(_.hcursor.get[List[String]]("enum").contains(List("Red", "Green", "Blue"))))
    assert(components.exists(_.hcursor.get[List[Json]]("oneOf").exists(_.size == 2)))
    assertReferencesResolve(doc)
  }

  test("automatic validation errors merge with explicitly declared status codes") {
    val app = httpApp {
      routes {
        get("/") {
          val n = query[Int]("n")
          val bad = response.json[String](400) {}
          handle { IO.pure(bad(n.value.toString)) }
        }
      }
    }
    val responseCursor = parse(app).hcursor.downField("paths").downField("/").downField("get")
      .downField("responses").downField("400").downField("content").downField("application/json").downField("schema")
    assertEquals(responseCursor.get[List[Json]]("anyOf").map(_.size), Right(2))
  }

  test("HEAD operations describe bodyless responses, including framework failures") {
    val app = httpApp {
      routes {
        head("/health") {
          val ok = response.empty(200) {}
          handle { IO.pure(ok(())) }
        }
      }
    }
    val responses = parse(app).hcursor.downField("paths").downField("/health").downField("head")
      .get[Map[String, Json]]("responses").toOption.get
    assert(responses.values.forall(!_.hcursor.downField("content").succeeded))
  }

  test("Swagger's independent OpenAPI 3.1 parser accepts the generated document") {
    import scala.jdk.CollectionConverters.*
    val app = framework.rpcDsl.httpApp {
      framework.rpcDsl.commands("reports") {
        import framework.rpcDsl.*
        description("Manage reports")
        commands("daily") {
          description("Daily reports")
          command[String]("read") { handle { IO.pure("report") } }
        }
      }
      routes {
        get("/trees/{id}") {
          val id = path[Long]("id") { minimum(1L) }
          val color = query[Option[String]]("color")
          val ok = response.json[Page[Tree]](200) {}
          handle { IO.pure(ok(Page(List(Tree(id.value.toString))))) }
        }
        post("/events") {
          val input = body.json[Event]
          val created = response.json[Event](201) {}
          handle { IO.pure(created(input.value)) }
        }
      }
    }
    val options = new io.swagger.v3.parser.core.models.ParseOptions()
    options.setResolve(false)
    val result = new io.swagger.v3.parser.OpenAPIV3Parser().readContents(app.openApiJson, null, options)
    assert(result.getOpenAPI() != null)
    assertEquals(result.getMessages().asScala.toList, Nil)
  }
