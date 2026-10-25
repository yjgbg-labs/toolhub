package sprout.sdk.cli

import io.circe.{Json, JsonObject}
import sprout.sdk.*

final case class Parameter(name: String, location: String, required: Boolean, schema: Json, explode: Boolean, description: String)
final case class Body(schema: Json, required: Boolean)
final case class Operation(id: String, method: String, path: String, description: String, parameters: Vector[Parameter], body: Option[Body]):
  val segments: Vector[String] = id.split('.').toVector

object Catalog:
  def parse(text: String): Json = io.circe.jawn.parse(text).fold(error => throw new IllegalArgumentException(error.message), identity)
  def obj(value: Json, name: String): JsonObject = value.asObject.getOrElse(throw new IllegalArgumentException(s"Expected $name object"))
  def text(value: Json, name: String): String = value.hcursor.get[String](name).getOrElse(throw new IllegalArgumentException(s"Missing string: $name"))
  def optionalText(value: Json, name: String): String = value.hcursor.get[String](name).getOrElse("")
  def field(value: Json, name: String): Option[Json] = value.asObject.flatMap(_(name))
  def bool(value: Json, name: String, default: Boolean = false): Boolean = field(value, name).flatMap(_.asBoolean).getOrElse(default)
  def array(value: Json, name: String): Vector[Json] = field(value, name).fold(Vector.empty[Json])(_.asArray.getOrElse(throw new IllegalArgumentException(s"Expected $name array")))
  def types(schema: Json): Vector[String] = field(schema, "type").toVector.flatMap(value => value.asString.toVector ++ value.asArray.toVector.flatten.flatMap(_.asString))

final class Catalog(document: Json):
  import Catalog.*
  require(text(document, "openapi").startsWith("3.1."), "Expected OpenAPI 3.1")
  val title: String = document.hcursor.downField("info").get[String]("title").getOrElse("API")
  private val operationPattern = "[A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z][A-Za-z0-9_-]*)*"
  private val methods = Set("get", "post", "put", "patch", "delete", "head", "options")
  private val locations = Set("path", "query", "header", "cookie")

  def resolve(value: Json, seen: Set[String] = Set.empty): Json = field(value, "$ref") match
    case None => value
    case Some(reference) =>
      val ref = reference.asString.getOrElse(throw new IllegalArgumentException("Expected string $ref"))
      require(ref.startsWith("#/"), s"Only local OpenAPI references are supported: $ref")
      require(!seen(ref), s"Cyclic OpenAPI reference: $ref")
      val target = ref.drop(2).split('/').foldLeft(document) { (current, encoded) =>
        val key = java.net.URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8").replace("~1", "/").replace("~0", "~")
        field(current, key).getOrElse(throw new IllegalArgumentException(s"Unresolved reference: $ref"))
      }
      val inherited = obj(resolve(target, seen + ref), ref)
      Json.fromJsonObject(JsonObject.fromIterable(inherited.toVector ++ obj(value, ref).toVector.filterNot(_._1 == "$ref")))

  val descriptions: Map[String, String] = field(document, "x-rpc").toVector.flatMap { metadata =>
    array(metadata, "commands").map(group => text(group, "name") -> optionalText(group, "description"))
  }.toMap

  val operations: Vector[Operation] = obj(field(document, "paths").getOrElse(Json.obj()), "paths").toVector.flatMap { (path, rawItem) =>
    require(path.startsWith("/") && !path.exists(c => c == '?' || c == '#'), s"Invalid HTTP path: $path")
    val item = resolve(rawItem)
    obj(item, path).toVector.filter(entry => methods(entry._1)).map { (method, declaration) =>
      val id = text(declaration, "operationId")
      require(id.matches(operationPattern), s"Invalid operationId: $id")
      val merged = scala.collection.mutable.LinkedHashMap.empty[(String, String), Parameter]
      (array(item, "parameters") ++ array(declaration, "parameters")).foreach { raw =>
        val param = resolve(raw)
        val name = text(param, "name")
        val location = text(param, "in")
        require(locations(location), s"Unsupported parameter location: $location")
        val schema = resolve(field(param, "schema").getOrElse(throw new IllegalArgumentException(s"Missing schema for $name")))
        val supported = types(schema).filterNot(_ == "null")
        require(supported.size == 1 && Set("string", "integer", "number", "boolean", "array")(supported.head), s"Unsupported parameter schema: $name")
        if supported.head == "array" then
          val items = resolve(field(schema, "items").getOrElse(Json.Null))
          require(location == "query" && types(items).size == 1 && Set("string", "integer", "number", "boolean")(types(items).head), s"Only primitive query arrays are supported: $name")
        val style = if location == "query" || location == "cookie" then "form" else "simple"
        require(field(param, "style").forall(_.asString.contains(style)) && !bool(param, "allowReserved"), s"Unsupported parameter serialization: $name")
        merged(location -> (if location == "header" then name.toLowerCase else name)) = Parameter(name, location,
          bool(param, "required") || location == "path", schema, bool(param, "explode", style == "form"), optionalText(param, "description"))
      }
      val parameters = merged.values.toVector
      val placeholders = "\\{([^{}]+)\\}".r.findAllMatchIn(path).map(_.group(1)).toSet
      require(placeholders == parameters.filter(_.location == "path").map(_.name).toSet, s"Path parameter mismatch: $id")
      val body = field(declaration, "requestBody").map { raw =>
        require(method != "get" && method != "head", s"$id: GET/HEAD cannot have a body")
        val request = resolve(raw)
        val schema = request.hcursor.downField("content").downField("application/json").downField("schema").focus
          .getOrElse(throw new IllegalArgumentException(s"$id: only JSON request bodies are supported"))
        Body(schema, bool(request, "required"))
      }
      Operation(id, method.toUpperCase, path, Vector(optionalText(declaration, "summary"), optionalText(declaration, "description")).filter(_.nonEmpty).mkString("\n"), parameters, body)
    }
  }.sortBy(_.id)
  require(operations.map(_.id).distinct.size == operations.size, "Duplicate operationId")

  def validate(raw: Json, value: Json, path: String, depth: Int = 0): Unit =
    require(depth < 256, s"$path: schema nesting is too deep")
    if raw == Json.True then return
    require(raw != Json.False, s"$path: value is not allowed")
    val schema = resolve(raw)
    val allowed = types(schema)
    val number = value.asNumber.flatMap(_.toBigDecimal)
    def matches(kind: String): Boolean = kind match
      case "null" => value.isNull
      case "string" => value.isString
      case "integer" => number.exists(_.isWhole)
      case "number" => number.nonEmpty
      case "boolean" => value.isBoolean
      case "array" => value.isArray
      case "object" => value.isObject
      case _ => false
    require(allowed.isEmpty || allowed.exists(matches), s"$path: expected ${allowed.mkString("|")}")
    field(schema, "enum").foreach(values => require(values.asArray.exists(_.contains(value)), s"$path: invalid enum value"))
    field(schema, "const").foreach(expected => require(expected == value, s"$path: invalid constant"))
    for keyword <- Vector("allOf", "anyOf", "oneOf"); variants <- field(schema, keyword) do
      val branches = variants.asArray.getOrElse(throw new IllegalArgumentException(s"$path: expected $keyword array"))
      val matches = branches.count(branch => scala.util.Try(validate(branch, value, path, depth + 1)).isSuccess)
      require(if keyword == "allOf" then matches == branches.size else if keyword == "oneOf" then matches == 1 else matches > 0, s"$path: does not match $keyword")
    number.foreach { actual =>
      def limit(name: String)(test: BigDecimal => Boolean): Unit = field(schema, name).flatMap(_.asNumber).flatMap(_.toBigDecimal).foreach(bound => require(test(bound), s"$path: violates $name $bound"))
      limit("minimum")(actual >= _)
      limit("maximum")(actual <= _)
      limit("exclusiveMinimum")(actual > _)
      limit("exclusiveMaximum")(actual < _)
      optionalText(schema, "format") match
        case "int32" => require(actual.isWhole && actual >= Int.MinValue && actual <= Int.MaxValue, s"$path: outside int32 range")
        case "int64" => require(actual.isWhole && actual >= Long.MinValue && actual <= Long.MaxValue, s"$path: outside int64 range")
        case _ => ()
    }
    value.asString.foreach { actual =>
      val length = actual.codePointCount(0, actual.length)
      field(schema, "minLength").flatMap(_.asNumber).flatMap(_.toInt).foreach(bound => require(length >= bound, s"$path: below minLength $bound"))
      field(schema, "maxLength").flatMap(_.asNumber).flatMap(_.toInt).foreach(bound => require(length <= bound, s"$path: above maxLength $bound"))
      field(schema, "pattern").flatMap(_.asString).foreach(pattern => require(pattern.r.findFirstIn(actual).nonEmpty, s"$path: does not match pattern"))
    }
    value.asArray.foreach { items =>
      field(schema, "items").foreach(itemSchema => items.zipWithIndex.foreach((item, index) => validate(itemSchema, item, s"$path/$index", depth + 1)))
    }
    value.asObject.foreach { fields =>
      val properties = field(schema, "properties").fold(JsonObject.empty)(obj(_, "properties"))
      array(schema, "required").foreach(key => require(key.asString.exists(fields.contains), s"$path: missing required field ${key.noSpaces}"))
      fields.toVector.foreach { (name, item) =>
        properties(name) match
          case Some(child) => validate(child, item, s"$path/$name", depth + 1)
          case None => field(schema, "additionalProperties").foreach(child => validate(child, item, s"$path/$name", depth + 1))
      }
    }

  def request(operation: Operation, input: Json): HttpRequest =
    val values = obj(input, "input")
    require(values.keys.forall(key => locations(key) || key == "body"), "Unknown input location")
    locations.foreach { location => values(location).foreach { group =>
      val fields = obj(group, location)
      require(fields.keys.forall(name => operation.parameters.exists(p => p.location == location && p.name == name)), s"Unknown $location parameter")
    } }
    def value(param: Parameter): Option[Json] = values(param.location).flatMap(field(_, param.name))
    operation.parameters.foreach { param =>
      val supplied = value(param)
      require(!param.required || supplied.nonEmpty, s"Missing required argument: --${param.location}.${param.name}")
      supplied.foreach(validate(param.schema, _, s"${param.location}.${param.name}"))
    }
    val body = values("body")
    require(operation.body.nonEmpty || body.isEmpty, s"${operation.id} has no body")
    operation.body.foreach { expected =>
      require(!expected.required || body.nonEmpty, "Missing required argument: --body")
      body.foreach(validate(expected.schema, _, "body"))
    }
    val path = operation.parameters.filter(_.location == "path").foldLeft(operation.path) { (path, param) =>
      path.replace("{" + param.name + "}", Wire.encode(Wire.scalar(value(param).get)))
    }
    def pairs(location: String): Vector[(String, String)] = operation.parameters.filter(_.location == location).flatMap { param =>
      value(param).toVector.flatMap(Wire.parameters(param.name, _, param.explode))
    }
    val cookies = pairs("cookie")
    val cookieHeader = if cookies.isEmpty then Vector.empty else Vector("Cookie" -> cookies.map((k, v) => s"${Wire.encode(k)}=${Wire.encode(v)}").mkString("; "))
    HttpRequest(operation.method, path, pairs("query"), pairs("header") ++ cookieHeader ++ body.toVector.map(_ => "Content-Type" -> "application/json"), body.map(_.noSpaces))
