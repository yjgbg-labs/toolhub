package sprout

import cats.effect.IO
import io.circe.Json
import scala.collection.mutable.ArrayBuffer

private[sprout] final class ConfigurationGate:
  private var open = true
  def check(): Unit = require(open, "Application configuration is frozen")
  def close(): Unit = open = false

trait ConfigScope:
  private[sprout] def gate: ConfigurationGate

trait DescriptionScope extends ConfigScope:
  private[sprout] var descriptionValue: Option[String] = None

trait TagScope extends ConfigScope:
  private[sprout] val tags = ArrayBuffer.empty[String]

final class ServerScope private[sprout] (private[sprout] val gate: ConfigurationGate) extends ConfigScope:
  private[sprout] var hostValue = "127.0.0.1"
  private[sprout] var portValue = 8080
  private[sprout] var maxBodyBytesValue = 1024L * 1024L

final class InfoScope private[sprout] (private[sprout] val gate: ConfigurationGate) extends DescriptionScope:
  private[sprout] var titleValue = "Sprout HTTP"
  private[sprout] var versionValue = "0.1.0"

final class OpenApiScope private[sprout] (private[sprout] val gate: ConfigurationGate) extends ConfigScope:
  private[sprout] var endpointValue = "/openapi.json"
  private[sprout] val infoScope = new InfoScope(gate)

final class InputScope[A] private[sprout] (private[sprout] val gate: ConfigurationGate) extends DescriptionScope:
  private[sprout] var defaultValue: Option[A] = None
  private[sprout] val constraints = ArrayBuffer.empty[Constraint[A]]

final class ResponseScope private[sprout] (private[sprout] val gate: ConfigurationGate) extends DescriptionScope

abstract class DeclarationScope private[sprout] (
    private[sprout] val gate: ConfigurationGate
) extends DescriptionScope with TagScope:
  private val inputFactories = ArrayBuffer.empty[() => InputSpec[?]]
  private val outputFactories = ArrayBuffer.empty[() => ResponseSpec]
  private[sprout] val middlewareValue = ArrayBuffer.empty[Handler => Handler]

  private[sprout] def freezeDeclarations(): ScopeDecl =
    ScopeDecl(inputFactories.map(_()).toVector, outputFactories.map(_()).toVector, middlewareValue.toVector, tags.toVector)

  private[sprout] def parameter[A](name: String, location: InputLocation)(using codec: ParamCodec[A]): Input[A] =
    gate.check()
    require(name.nonEmpty, "Parameter name must not be empty")
    require(location != InputLocation.Header || !Set("accept", "content-type", "authorization").contains(name.toLowerCase(java.util.Locale.ROOT)),
      s"OpenAPI does not describe $name as an ordinary header parameter")
    require(!codec.repeated || location == InputLocation.Query, "Repeated parameters are supported in query strings")
    val config = new InputScope[A](gate)
    val handle = new Input[A](new InputKey[A], config)
    inputFactories += (() =>
      val rules = config.constraints.toVector
      config.defaultValue.foreach(value => require(rules.forall(_.accepts(value)), s"Invalid default for $name"))
      val missing = config.defaultValue.orElse(codec.missing)
      require(location != InputLocation.Path || missing.isEmpty, s"Path parameter $name must be required and have no default")
      InputSpec(handle.key, name, location, codec.schema, config.descriptionValue,
        missing.isEmpty, config.defaultValue.map(codec.encode), codec.repeated, rules, {
          case RawInput.Values(values) if values.isEmpty => missing.toRight("Missing required parameter")
          case RawInput.Values(values) => codec.decode(values)
          case _ => Left("Expected a parameter")
        })
    )
    handle

  private[sprout] def jsonBody[A](using model: JsonModel[A]): Input[A] =
    gate.check()
    val config = new InputScope[A](gate)
    val handle = new Input[A](new InputKey[A], config)
    inputFactories += (() =>
      val rules = config.constraints.toVector
      config.defaultValue.foreach(value => require(rules.forall(_.accepts(value)), "Invalid body default"))
      val missing = config.defaultValue.orElse(model.missing)
      InputSpec(handle.key, "body", InputLocation.Body, model.schema, config.descriptionValue,
        missing.isEmpty, config.defaultValue.map(model.encoder.apply), false, rules, {
          case RawInput.Body(Some(json)) => model.decoder.decodeJson(json).left.map(_.getMessage)
          case RawInput.Body(None) => missing.toRight("Missing required request body")
          case _ => Left("Expected a JSON body")
        })
    )
    handle

  private[sprout] def output[A](
      status: Int,
      mediaType: Option[String],
      schema: Option[Schema[A]],
      encode: A => Option[String]
  ): OutputDeclaration[A] =
    gate.check()
    require(status >= 200 && status <= 599, s"Unsupported response status: $status")
    require(!Set(204, 205, 304).contains(status) || mediaType.isEmpty, s"Status $status must not have a body")
    val config = new ResponseScope(gate)
    val key = new ResponseKey[A]
    val result = new OutputDeclaration[A](key, encode, config)
    outputFactories += (() => ResponseSpec(key, status,
      config.descriptionValue.getOrElse(org.http4s.Status.fromInt(status).toOption.get.reason), mediaType, schema))
    result

final class RoutesScope private[sprout] (gate: ConfigurationGate, name: String) extends DeclarationScope(gate):
  private[sprout] val children = ArrayBuffer.empty[() => RouteDecl]

  private[sprout] def freeze(): GroupDecl =
    GroupDecl(name, descriptionValue, freezeDeclarations(), children.map(_()).toVector)

final class EndpointScope[A] private[sprout] (
    gate: ConfigurationGate,
    method: String,
    path: String,
    operation: Option[String] = None
) extends DeclarationScope(gate):
  private var handler: Option[HandlerDecl[A]] = None
  private[sprout] var operationIdValue: Option[String] = None
  private[sprout] var summaryValue: Option[String] = None
  private[sprout] var successValue: Option[Output[A]] = None

  private[sprout] def setHandler(logic: RequestContext => (A | EndpointResult | IO[A | EndpointResult])): Unit =
    gate.check()
    require(handler.isEmpty, s"Duplicate handler: $method $path")
    handler = Some(HandlerDecl(successValue, logic))

  private[sprout] def freeze(): EndpointDecl =
    EndpointDecl(method, path, operation, operationIdValue, summaryValue, descriptionValue, freezeDeclarations(), handler)

final class AppScope private[sprout] () extends ConfigScope:
  private[sprout] val gate = new ConfigurationGate
  private[sprout] val serverScope = new ServerScope(gate)
  private[sprout] val openApiScope = new OpenApiScope(gate)
  private[sprout] val routes = ArrayBuffer.empty[RoutesScope]

  private[sprout] def freeze(): ApplicationDecl =
    gate.close()
    val info = openApiScope.infoScope
    ApplicationDecl(ServerSettings(serverScope.hostValue, serverScope.portValue, serverScope.maxBodyBytesValue),
      OpenApiSettings(openApiScope.endpointValue, info.titleValue, info.versionValue, info.descriptionValue), routes.map(_.freeze()).toVector)
