package framework.http

import framework.model.SchemaNode
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.collection.immutable.ListMap
import scala.collection.mutable
import sttp.apispec.{ExampleSingleValue, ExtensionValue, Pattern, Schema as ApiSchema, SchemaType}
import sttp.apispec.openapi.{Response as ApiResponse, *}

private[framework] object OpenApi:
  private class Schemas:
    val components = mutable.LinkedHashMap.empty[String, ApiSchema]

    private def componentName(id: String): String =
      val readable = id.takeWhile(_ != '[').replaceAll("[^A-Za-z0-9._-]", "_")
      val hash = MessageDigest.getInstance("SHA-256").digest(id.getBytes(UTF_8)).take(8).map(b => f"${b & 0xff}%02x").mkString
      s"${readable}_$hash"

    private def named(id: String)(build: => ApiSchema): ApiSchema =
      val name = componentName(id)
      if !components.contains(name) then
        components(name) = ApiSchema.Empty
        components(name) = build
      ApiSchema($ref = Some(s"#/components/schemas/$name"))

    def render(node: SchemaNode): ApiSchema = node match
      case SchemaNode.Primitive(kind, format) =>
        val tpe = SchemaType.Values.find(_.value == kind).getOrElse(throw new IllegalArgumentException(s"Unknown schema type: $kind"))
        ApiSchema(tpe).copy(format = format)
      case SchemaNode.ArrayOf(element) => ApiSchema(SchemaType.Array).copy(items = Some(render(element())))
      case SchemaNode.Dictionary(value) => ApiSchema(SchemaType.Object).copy(additionalProperties = Some(render(value())))
      case SchemaNode.Nullable(value) => render(value()).nullable
      case SchemaNode.Product(id, fields) => named(id) {
        val entries = fields()
        ApiSchema(SchemaType.Object).copy(
          properties = ListMap.from(entries.map { field =>
            field.name -> render(field.schema()).copy(default = field.default.map(f => ExampleSingleValue(f().noSpaces)))
          }),
          required = entries.filter(_.required).map(_.name).toList
        )
      }
      case SchemaNode.Enumeration(id, values) => named(id) {
        ApiSchema(SchemaType.String).copy(`enum` = Some(values.map(v => ExampleSingleValue(Json.fromString(v).noSpaces)).toList))
      }
      case SchemaNode.Sum(id, variants) => named(id) {
        ApiSchema(oneOf = variants().map { case (label, shape) =>
          ApiSchema(SchemaType.Object).copy(
            properties = ListMap(
              "type" -> ApiSchema(SchemaType.String).copy(const = Some(ExampleSingleValue(Json.fromString(label).noSpaces))),
              "value" -> render(shape)
            ),
            required = List("type", "value")
          )
        }.toList)
      }

    def input(input: InputSpec[?]): ApiSchema =
      val base = render(input.schema.node()).copy(default = input.default.map(j => ExampleSingleValue(j.noSpaces)))
      input.constraints.flatMap(_.schema).foldLeft(base) { (schema, rule) =>
        rule.keyword match
          case "minimum" =>
            val value = rule.value.asNumber.flatMap(_.toBigDecimal).get
            schema.copy(minimum = Some(schema.minimum.fold(value)(_.max(value))))
          case "maximum" =>
            val value = rule.value.asNumber.flatMap(_.toBigDecimal).get
            schema.copy(maximum = Some(schema.maximum.fold(value)(_.min(value))))
          case "minLength" =>
            val value = rule.value.asNumber.flatMap(_.toInt).get
            schema.copy(minLength = Some(schema.minLength.fold(value)(_.max(value))))
          case "maxLength" =>
            val value = rule.value.asNumber.flatMap(_.toInt).get
            schema.copy(maxLength = Some(schema.maxLength.fold(value)(_.min(value))))
          case "pattern" =>
            val value = Pattern(rule.value.asString.get)
            if schema.pattern.isEmpty then schema.copy(pattern = Some(value))
            else schema.copy(allOf = schema.allOf :+ ApiSchema(pattern = Some(value)))
          case other => throw new IllegalArgumentException(s"Unknown constraint: $other")
      }

  def generate(endpoints: Vector[EndpointSpec], config: OpenApiSettings, extensions: Map[String, Json]): OpenAPI =
    val schemas = new Schemas

    def operation(endpoint: EndpointSpec): Operation =
      val responses = endpoint.responses.foldLeft(Responses.Empty) { (responses, output) =>
        val content = output.content.map { entry =>
          val variants = entry.schemas.map(schema => schemas.render(schema.node()))
          val schema = if variants.size == 1 then variants.head else ApiSchema(anyOf = variants.toList)
          entry.mediaType -> MediaType(schema = Some(schema))
        }
        responses.addResponse(output.status, ApiResponse(output.description, content = ListMap.from(content)))
      }
      val parameters = endpoint.inputs.filterNot(_.location == InputLocation.Body).map { input =>
        val in = input.location match
          case InputLocation.Path   => ParameterIn.Path
          case InputLocation.Query  => ParameterIn.Query
          case InputLocation.Header => ParameterIn.Header
          case InputLocation.Cookie => ParameterIn.Cookie
          case InputLocation.Body   => throw new IllegalStateException("Body is not a parameter")
        val form = input.location == InputLocation.Query || input.location == InputLocation.Cookie
        Right(Parameter(input.name, in, description = input.description, required = Some(input.required),
          style = Some(if form then ParameterStyle.Form else ParameterStyle.Simple),
          explode = Some(input.location == InputLocation.Query), schema = Some(schemas.input(input))))
      }.toList
      val body = endpoint.inputs.find(_.location == InputLocation.Body).map { input =>
        Right(RequestBody(description = input.description, required = Some(input.required),
          content = ListMap(input.bodyMediaType.get -> MediaType(schema = Some(schemas.input(input))))))
      }
      Operation(tags = endpoint.tags.toList, summary = endpoint.summary, description = endpoint.description,
        operationId = Some(endpoint.operationId), parameters = parameters, requestBody = body, responses = responses)

    val paths = endpoints.sortBy(e => (e.path.value, e.method)).foldLeft(Paths.Empty) { (paths, endpoint) =>
      val op = operation(endpoint)
      val item = endpoint.method match
        case "GET"     => PathItem(get = Some(op))
        case "POST"    => PathItem(post = Some(op))
        case "PUT"     => PathItem(put = Some(op))
        case "PATCH"   => PathItem(patch = Some(op))
        case "DELETE"  => PathItem(delete = Some(op))
        case "HEAD"    => PathItem(head = Some(op))
        case "OPTIONS" => PathItem(options = Some(op))
      paths.addPathItem(endpoint.path.value, item)
    }
    OpenAPI(openapi = "3.1.2", info = Info(config.title, config.version, description = config.description),
      extensions = ListMap.from(extensions.toVector.sortBy(_._1).map((name, value) => name -> ExtensionValue(value.noSpaces))),
      paths = paths, components = Some(Components(schemas = ListMap.from(schemas.components.toVector.sortBy(_._1)))))
