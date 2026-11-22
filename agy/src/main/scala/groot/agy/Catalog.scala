package groot.agy

import io.circe.{Json, JsonObject}

final case class Parameter(
    name: String,
    location: String,
    required: Boolean,
    schema: Json,
    description: String
)

final case class RequestBodySpec(
    schema: Json,
    required: Boolean,
    mediaType: String,
    description: String
)

final case class Operation(
    id: String,
    method: String,
    path: String,
    description: String,
    parameters: Vector[Parameter],
    body: Option[RequestBodySpec],
    responseSchemas: Vector[Json]
):
  val segments: Vector[String] = id.split('.').toVector
  val namespace: String = if segments.size <= 1 then "root" else segments.dropRight(1).mkString(".")
  val topNamespace: String = if segments.size <= 1 then "root" else segments.head
  val bodyOnly: Boolean = body.nonEmpty && parameters.isEmpty

final case class NamespaceSummary(
    name: String,
    description: String,
    operationCount: Int,
    subNamespaces: Vector[String],
    operationIds: Vector[String]
)

object Catalog:
  def parse(text: String): Json =
    io.circe.jawn.parse(text).fold(error => throw new IllegalArgumentException(s"Invalid JSON: ${error.message}"), identity)
  def obj(value: Json, name: String): JsonObject =
    value.asObject.getOrElse(throw new IllegalArgumentException(s"Expected $name object"))
  def text(value: Json, name: String): String =
    value.hcursor.get[String](name).getOrElse(throw new IllegalArgumentException(s"Missing string: $name"))
  def optionalText(value: Json, name: String): String =
    value.hcursor.get[String](name).getOrElse("")
  def field(value: Json, name: String): Option[Json] = value.asObject.flatMap(_(name))
  def bool(value: Json, name: String, default: Boolean = false): Boolean =
    field(value, name).flatMap(_.asBoolean).getOrElse(default)
  def array(value: Json, name: String): Vector[Json] =
    field(value, name).fold(Vector.empty[Json])(_.asArray.getOrElse(throw new IllegalArgumentException(s"Expected $name array")))
  def types(schema: Json): Vector[String] =
    field(schema, "type").toVector.flatMap(value => value.asString.toVector ++ value.asArray.toVector.flatten.flatMap(_.asString))

/** OpenAPI 3.1 catalog filtered to operations exposed to GraalJS (matching `tools.*` in Groot Server). */
final class Catalog(val document: Json):
  import Catalog.*
  require(text(document, "openapi").startsWith("3.1."), "Expected OpenAPI 3.1")
  val title: String = field(document, "info").map(optionalText(_, "title")).filter(_.nonEmpty).getOrElse("Groot")

  private val operationPattern = "[A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z][A-Za-z0-9_-]*)*"
  private val methods = Set("get", "post", "put", "patch", "delete", "head", "options")
  private val locations = Set("path", "query", "header", "cookie")

  private def exposedToGraalJs(declaration: Json): Boolean = field(declaration, "x-expose-on").forall { value =>
    val clients = value.asArray.getOrElse(throw new IllegalArgumentException("Expected x-expose-on array"))
    require(clients.forall(_.isString), "Expected x-expose-on client strings")
    clients.exists(_.asString.contains("graaljs"))
  }

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

  val groupDescriptions: Map[String, String] = field(document, "x-rpc").toVector.flatMap { metadata =>
    array(metadata, "commands").map(group => text(group, "name") -> optionalText(group, "description"))
  }.filter(_._2.nonEmpty).toMap

  val operations: Vector[Operation] = obj(field(document, "paths").getOrElse(Json.obj()), "paths").toVector.flatMap { (path, rawItem) =>
    require(path.startsWith("/") && !path.exists(c => c == '?' || c == '#'), s"Invalid HTTP path: $path")
    val item = resolve(rawItem)
    obj(item, path).toVector.filter(entry => methods(entry._1) && exposedToGraalJs(entry._2)).map { (method, declaration) =>
      val id = text(declaration, "operationId")
      require(id.matches(operationPattern), s"Invalid operationId: $id")
      val merged = scala.collection.mutable.LinkedHashMap.empty[(String, String), Parameter]
      (array(item, "parameters") ++ array(declaration, "parameters")).foreach { raw =>
        val param = resolve(raw)
        val name = text(param, "name")
        val location = text(param, "in")
        require(locations(location), s"Unsupported parameter location: $location")
        val schema = field(param, "schema").getOrElse(throw new IllegalArgumentException(s"Missing schema for $name"))
        merged(location -> (if location == "header" then name.toLowerCase else name)) = Parameter(
          name = name,
          location = location,
          required = bool(param, "required") || location == "path",
          schema = schema,
          description = optionalText(param, "description")
        )
      }
      val body = field(declaration, "requestBody").map { raw =>
        val request = resolve(raw)
        val content = request.hcursor.downField("content")
        val mediaType = Vector("application/json", "text/plain").find(media => content.downField(media).downField("schema").focus.nonEmpty)
          .getOrElse(throw new IllegalArgumentException(s"$id: only JSON and text/plain request bodies are supported"))
        val schema = content.downField(mediaType).downField("schema").focus.get
        RequestBodySpec(schema, bool(request, "required"), mediaType, optionalText(request, "description"))
      }
      val responses = field(declaration, "responses").toVector.flatMap(obj(_, "responses").toVector).flatMap { (status, rawResp) =>
        if !status.matches("2\\d\\d|2XX") then Vector.empty
        else
          val resp = resolve(rawResp)
          val content = field(resp, "content").map(obj(_, "content").toVector).getOrElse(Vector.empty)
          if content.isEmpty || method == "head" then Vector(Json.obj("type" -> Json.fromString("null")))
          else content.map { (media, rawMedia) =>
            val entry = obj(rawMedia, "response media")
            if media == "application/json" || media.endsWith("+json") then entry("schema").getOrElse(Json.obj())
            else Json.obj("type" -> Json.fromString("string"))
          }
      }
      val desc = Vector(optionalText(declaration, "summary"), optionalText(declaration, "description")).filter(_.nonEmpty).mkString("\n")
      Operation(
        id = id,
        method = method.toUpperCase,
        path = path,
        description = if desc.nonEmpty then desc else s"${method.toUpperCase} $path",
        parameters = merged.values.toVector,
        body = body,
        responseSchemas = responses
      )
    }
  }.sortBy(_.id)

  require(operations.map(_.id).distinct.size == operations.size, "Duplicate operationId")

  val byId: Map[String, Operation] = operations.map(op => op.id -> op).toMap

  val topLevelSummaries: Vector[NamespaceSummary] =
    val grouped = operations.groupBy(_.topNamespace)
    val orderedKeys = grouped.keys.toVector.filterNot(_ == "root").sorted ++ Option.when(grouped.contains("root"))("root")
    orderedKeys.map { top =>
      val ops = grouped(top)
      val subs = ops.map(_.namespace).filter(ns => ns != top && ns != "root").distinct.sorted
      val desc =
        if top == "root" then "Top-level commands invoked directly as tools.<name>(...)"
        else groupDescriptions.getOrElse(top, s"$title — $top")
      NamespaceSummary(top, desc, ops.size, subs, ops.map(_.id))
    }

final class CatalogLoader(transport: HttpTransport, schemaPath: String = "/openapi.json", ttlMs: Long = 10000L):
  private var cached: Option[(Catalog, Long)] = None

  def load(forceRefresh: Boolean = false): Catalog = synchronized {
    val now = System.currentTimeMillis()
    cached match
      case Some((catalog, loadedAt)) if !forceRefresh && (now - loadedAt) < ttlMs => catalog
      case _ =>
        val response = transport.send(HttpRequest("GET", schemaPath, headers = Vector("Accept" -> "application/json")))
        if !response.isSuccess then throw new RuntimeException(s"Failed to fetch $schemaPath: HTTP ${response.status} ${response.body.take(300)}")
        val catalog = new Catalog(Catalog.parse(response.body))
        cached = Some(catalog -> now)
        catalog
  }
