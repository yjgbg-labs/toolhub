package groot.graaljs

import cats.effect.IO
import framework.Client
import framework.http.{InputLocation, ResponseBody}
import framework.model.SchemaNode
import framework.runtime.{Application, Endpoint}
import io.circe.{Json, JsonObject}
import java.nio.charset.StandardCharsets.UTF_8

/** SDK policy over structured framework invocation; no internal HTTP request/response round trip. */
final class GraalCommandSdk private (endpoints: Vector[Endpoint]):
  private case class Command(endpoint: Endpoint):
    val body = endpoint.inputs.find(_.location == InputLocation.Body)
    val parameters = endpoint.inputs.filterNot(_.location == InputLocation.Body)
    val bodyOnly = body.nonEmpty && parameters.isEmpty
    val names = endpoint.inputs.map(input => if input.location == InputLocation.Body then "body" else input.name)
    require(names.distinct.size == names.size, s"Duplicate SDK parameter name (including reserved body): ${endpoint.operationId}")
    val allowed = names.toSet

  private val byId = endpoints.map(endpoint => endpoint.operationId -> Command(endpoint)).toMap
  val commands: Vector[String] = byId.keys.toVector.sorted.map { id =>
    require(id.matches("[A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z][A-Za-z0-9_-]*)*"), s"Invalid SDK operationId: $id")
    id
  }
  private[graaljs] val tree = CommandTree.compile(commands)

  def call(id: String, input: Json): IO[Json] = call(id, Some(input))

  def call(id: String, input: Option[Json] = None): IO[Json] = IO.defer {
    val command = byId.getOrElse(id, throw new IllegalArgumentException(s"Unknown command: $id"))
    val args = if command.bodyOnly then JsonObject.empty else
      input.fold(JsonObject.empty)(_.asObject.getOrElse(throw new IllegalArgumentException("Command input must be an object")))
    require(args.keys.forall(command.allowed), s"Unknown parameter for $id")
    val parameters = command.parameters.flatMap { parameter =>
      val supplied = args(parameter.name)
      if parameter.location == InputLocation.Path then require(supplied.nonEmpty, s"Missing ${parameter.name}")
      supplied.map { value =>
        require(matches(parameter.schema, value), s"Invalid type for ${parameter.name}")
        val values = if parameter.location == InputLocation.Query then value.asArray.getOrElse(Vector(value)).map(scalar)
          else Vector(scalar(value))
        values.foreach { text => parameter.location match
          case InputLocation.Path => require(text.nonEmpty && text != "." && text != "..", s"Invalid path parameter: ${parameter.name}")
          case InputLocation.Header => require(!text.exists(c => c == '\r' || c == '\n'), s"Invalid header.${parameter.name}")
          case InputLocation.Cookie => require(GraalCommandSdk.cookieName.matcher(parameter.name).matches() &&
            GraalCommandSdk.cookieValue.matcher(text).matches(), s"Invalid cookie.${parameter.name}")
          case _ => ()
        }
        (parameter.location -> parameter.name) -> values
      }
    }.toMap
    val body = if command.bodyOnly then input else command.body.flatMap(_ => args("body"))
    if command.body.exists(_.bodyMediaType.contains("text/plain")) then
      body.foreach(value => require(value.isString, "body must be a string"))
    command.endpoint.invoke(Endpoint.Request(parameters, body)).flatMap { response =>
      require(response.contentLength.forall(_ <= GraalCommandSdk.maxBytes), "SDK response exceeds 1 MiB")
      val value = response.body match
        case None => IO.pure(Json.Null)
        case Some(ResponseBody.Json(json)) => IO.pure(json)
        case Some(ResponseBody.Text(text)) => IO.pure(if text.isEmpty then Json.Null else Json.fromString(text))
        case Some(ResponseBody.Streaming(stream)) => stream.take(GraalCommandSdk.maxBytes + 1L).compile.to(Array).map { bytes =>
          require(bytes.length <= GraalCommandSdk.maxBytes, "SDK response exceeds 1 MiB")
          if bytes.isEmpty then Json.Null else Json.fromString(new String(bytes, UTF_8))
        }
      value.map { body => Json.obj("status" -> Json.fromInt(response.status),
        "headers" -> Json.fromFields(response.headers.map((key, value) => key -> Json.fromString(value))), "body" -> body) }
    }
  }

  private def scalar(value: Json): String = value.asString.getOrElse(value.noSpaces)
  private def matches(node: SchemaNode, value: Json): Boolean = node match
    case SchemaNode.Primitive("string", _) => value.isString
    case SchemaNode.Primitive("boolean", _) => value.isBoolean
    case SchemaNode.Primitive("integer", _) => value.asNumber.exists(_.toBigDecimal.exists(_.isWhole))
    case SchemaNode.Primitive("number", _) => value.isNumber
    case SchemaNode.Enumeration(_, _) => value.isString
    case SchemaNode.ArrayOf(element) => value.asArray.exists(_.forall(matches(element(), _)))
    case SchemaNode.Nullable(element) => value.isNull || matches(element(), value)
    case _ => false

object GraalCommandSdk:
  val maxBytes = 1024 * 1024
  private val cookieName = java.util.regex.Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
  private val cookieValue = java.util.regex.Pattern.compile("[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]*")
  val empty: GraalCommandSdk = new GraalCommandSdk(Vector.empty)
  def apply(app: Application): GraalCommandSdk =
    new GraalCommandSdk(app.endpoints.filter(_.exposedOn.contains(Client.graaljs)))
