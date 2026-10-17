package framework.http

import cats.effect.IO
import framework.model.{JsonModel, Schema}
import io.circe.Json
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.LinkedHashMap

private[framework] final class ConfigurationGate:
  private var open = true
  def check(): Unit = require(open, "Application configuration is frozen")
  def close(): Unit = open = false

trait ConfigScope:
  private[framework] def gate: ConfigurationGate

trait DescriptionScope extends ConfigScope:
  private[framework] var descriptionValue: Option[String] = None

trait TagScope extends ConfigScope:
  private[framework] val tags = ArrayBuffer.empty[String]

final class ServerScope private[framework] (private[framework] val gate: ConfigurationGate) extends ConfigScope:
  private[framework] var hostValue = "127.0.0.1"
  private[framework] var portValue = 8080
  private[framework] var socketValue: Option[String] = None
  private[framework] var maxBodyBytesValue = 1024L * 1024L

final class InfoScope private[framework] (private[framework] val gate: ConfigurationGate) extends DescriptionScope:
  private[framework] var titleValue = "Sprout HTTP"
  private[framework] var versionValue = "0.1.0"

final class OpenApiScope private[framework] (private[framework] val gate: ConfigurationGate) extends ConfigScope:
  private[framework] var endpointValue = "/openapi.json"
  private[framework] val infoScope = new InfoScope(gate)

final class InputScope[A] private[framework] (private[framework] val gate: ConfigurationGate) extends DescriptionScope:
  private[framework] var defaultValue: Option[A] = None
  private[framework] val constraints = ArrayBuffer.empty[Constraint[A]]

final class ResponseScope private[framework] (private[framework] val gate: ConfigurationGate) extends DescriptionScope

abstract class DeclarationScope private[framework] (
    private[framework] val gate: ConfigurationGate
) extends DescriptionScope with TagScope:
  private val inputFactories = ArrayBuffer.empty[() => InputSpec[?]]
  private val outputFactories = ArrayBuffer.empty[() => ResponseSpec]
  private[framework] val middlewareValue = ArrayBuffer.empty[Handler => Handler]
  private[framework] var hasBody: Boolean = false

  private[framework] def freezeDeclarations(): ScopeDecl =
    ScopeDecl(inputFactories.map(_()).toVector, outputFactories.map(_()).toVector, middlewareValue.toVector, tags.toVector)

  private[framework] def parameter[A](name: String, location: InputLocation)(using codec: ParamCodec[A]): Input[A] =
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

  private[framework] def jsonBody[A](using model: JsonModel[A]): Input[A] =
    gate.check()
    hasBody = true
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

  private[framework] def output[A](
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

final class RoutesScope private[framework] (gate: ConfigurationGate) extends DeclarationScope(gate):
  private[framework] val endpoints = ArrayBuffer.empty[EndpointScope]
  private[framework] val sharedScopes = ArrayBuffer[DeclarationScope](this)

  private[framework] def freeze(): Vector[EndpointDecl] =
    val shared = sharedScopes.map(scope => scope -> scope.freezeDeclarations()).toMap
    endpoints.map(_.freeze(shared)).toVector

final class EndpointScope private[framework] (
    gate: ConfigurationGate,
    method: EndpointScope => String,
    path: String,
    inherited: Vector[DeclarationScope]
) extends DeclarationScope(gate):
  private var handler: Option[RequestContext => IO[EndpointResult]] = None
  private[framework] var operationIdValue: Option[String] = None
  private[framework] var summaryValue: Option[String] = None

  private[framework] def setHandler(logic: RequestContext => IO[EndpointResult]): Unit =
    gate.check()
    require(handler.isEmpty, s"Duplicate handler: ${method(this)} $path")
    handler = Some(logic)

  private[framework] def freeze(shared: Map[DeclarationScope, ScopeDecl]): EndpointDecl =
    val scope = ScopeDecl.combine(inherited.map(shared) :+ freezeDeclarations())
    EndpointDecl(method(this), path, operationIdValue, summaryValue, descriptionValue, scope, handler)

final class AppScope private[framework] () extends ConfigScope:
  private[framework] val gate = new ConfigurationGate
  private[framework] val serverScope = new ServerScope(gate)
  private[framework] val openApiScope = new OpenApiScope(gate)
  private[framework] val routes = ArrayBuffer.empty[RoutesScope]
  private[framework] val extensions = LinkedHashMap.empty[String, () => Json]

  private[framework] def freeze(): ApplicationDecl =
    gate.close()
    val info = openApiScope.infoScope
    ApplicationDecl(ServerSettings(serverScope.hostValue, serverScope.portValue, serverScope.maxBodyBytesValue, serverScope.socketValue),
      OpenApiSettings(openApiScope.endpointValue, info.titleValue, info.versionValue, info.descriptionValue),
      routes.flatMap(_.freeze()).toVector, extensions.iterator.map((name, value) => name -> value()).toMap)
