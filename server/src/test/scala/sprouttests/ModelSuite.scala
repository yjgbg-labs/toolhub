package sprouttests

import io.circe.Json
import munit.FunSuite
import sprout.*

class ModelSuite extends FunSuite:
  test("case classes apply constructor defaults only to missing fields") {
    val model = JsonModel[CreateUser]
    assertEquals(model.decoder.decodeJson(Json.obj("name" -> Json.fromString("Ada"))), Right(CreateUser("Ada")))
    assert(model.decoder.decodeJson(Json.obj("name" -> Json.fromString("Ada"), "active" -> Json.Null)).isLeft)
    assert(model.decoder.decodeJson(Json.obj()).isLeft)
    assert(model.decoder.decodeJson(Json.arr()).isLeft)
  }

  test("optional fields accept missing and null; encoder emits explicit null") {
    val model = JsonModel[User]
    val user = User(1L, "Ada")
    val json = model.encoder(user)
    assertEquals(json.hcursor.downField("nickname").focus, Some(Json.Null))
    assertEquals(model.decoder.decodeJson(json), Right(user))
    assertEquals(model.decoder.decodeJson(json.mapObject(_.remove("nickname"))), Right(user))
  }

  test("recursive and mutually recursive models initialize without cycles") {
    val tree = Tree("root", List(Tree("child")))
    val model = JsonModel[Tree]
    assertEquals(model.decoder.decodeJson(model.encoder(tree)), Right(tree))
    val branch = Branch(List(Leaf(Some(Branch(Nil)))))
    val branchModel = JsonModel[Branch]
    assertEquals(branchModel.decoder.decodeJson(branchModel.encoder(branch)), Right(branch))
  }

  test("generic model identities include instantiated type arguments") {
    assertNotEquals(JsonModel[Page[User]].typeId, JsonModel[Page[String]].typeId)
    val model = JsonModel[Page[User]]
    val value = Page(List(User(1L, "Ada")))
    assertEquals(model.decoder.decodeJson(model.encoder(value)), Right(value))
  }

  test("singleton enums use strings and payload enums use tagged objects") {
    val color = JsonModel[Color]
    assertEquals(color.encoder(Color.Green), Json.fromString("Green"))
    assertEquals(color.decoder.decodeJson(Json.fromString("Green")), Right(Color.Green))
    assert(color.decoder.decodeJson(Json.fromString("Unknown")).isLeft)

    val event = JsonModel[Event]
    val value: Event = Event.Created(12L)
    val json = event.encoder(value)
    assertEquals(json.hcursor.get[String]("type"), Right("Created"))
    assertEquals(json.hcursor.downField("value").get[Long]("id"), Right(12L))
    assertEquals(event.decoder.decodeJson(json), Right(value))
    assert(event.decoder.decodeJson(Json.obj("type" -> Json.fromString("Created"))).isLeft)
  }

  test("maps, lists, vectors, UUIDs and decimal values round trip") {
    val model = JsonModel[Map[String, Vector[BigDecimal]]]
    val value = Map("values" -> Vector(BigDecimal("123456789.123456789")))
    assertEquals(model.decoder.decodeJson(model.encoder(value)), Right(value))
    val uuid = java.util.UUID.randomUUID()
    assertEquals(JsonModel[java.util.UUID].decoder.decodeJson(JsonModel[java.util.UUID].encoder(uuid)), Right(uuid))
  }

  test("numeric JSON models require JSON numbers and reject non-finite doubles") {
    assert(JsonModel[Int].decoder.decodeJson(Json.fromString("1")).isLeft)
    assert(JsonModel[Long].decoder.decodeJson(Json.fromString("1")).isLeft)
    assert(JsonModel[BigDecimal].decoder.decodeJson(Json.fromString("1.5")).isLeft)
    assert(JsonModel[Double].decoder.decodeJson(Json.fromString("1.5")).isLeft)
    assert(JsonModel[Double].decoder.decodeJson(io.circe.parser.parse("1e400").toOption.get).isLeft)
    intercept[IllegalArgumentException](JsonModel[Double].encoder(Double.NaN))
  }

  test("constructor defaults are reflected in schema requiredness") {
    val schema = JsonModel[Defaults].schema.node()
    schema match
      case SchemaNode.Product(_, fields) =>
        val all = fields()
        assert(all.forall(!_.required))
        assertEquals(all.find(_.name == "count").flatMap(_.default).map(_()), Some(Json.fromInt(3)))
      case _ => fail("Expected a product schema")
  }
