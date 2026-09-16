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

final class RoutesScope private[sprout] (
    private[sprout] val app: AppScope,
    private[sprout] val prefix: String,
    private[sprout] val parent: Option[RoutesScope]
) extends TagScope:
  private[sprout] def gate = app.gate
  private[sprout] def inheritedTags: Vector[String] =
    (parent.toVector.flatMap(_.inheritedTags) ++ tags).distinct

final class InputScope[A] private[sprout] (private[sprout] val gate: ConfigurationGate) extends DescriptionScope:
  private[sprout] var defaultValue: Option[A] = None
  private[sprout] val constraints = ArrayBuffer.empty[Constraint[A]]

final class ResponseScope private[sprout] (private[sprout] val gate: ConfigurationGate) extends DescriptionScope

final class EndpointScope private[sprout] (
    private[sprout] val group: RoutesScope,
    method: String,
    path: PathPattern
) extends DescriptionScope with TagScope:
  private[sprout] def gate = group.gate
  private val owner = new Object
  private val inputFactories = ArrayBuffer.empty[() => InputSpec[?]]
  private val outputFactories = ArrayBuffer.empty[() => ResponseSpec]
  private var handler: Option[RequestContext => IO[EndpointResult]] = None
  private[sprout] var operationIdValue: Option[String] = None
  private[sprout] var summaryValue: Option[String] = None

  private[sprout] def parameter[A](name: String, location: InputLocation)(using codec: ParamCodec[A]): Input[A] =
    gate.check()
    require(name.nonEmpty, "Parameter name must not be empty")
    require(location != InputLocation.Header || !Set("accept", "content-type", "authorization").contains(name.toLowerCase(java.util.Locale.ROOT)),
      s"OpenAPI does not describe $name as an ordinary header parameter")
    require(!codec.repeated || location == InputLocation.Query, "Repeated parameters are supported in query strings")
    val config = new InputScope[A](gate)
    val handle = new Input[A](owner, inputFactories.size, config)
    inputFactories += (() =>
      val rules = config.constraints.toVector
      config.defaultValue.foreach(value => require(rules.forall(_.accepts(value)), s"Invalid default for $name"))
      val missing = config.defaultValue.orElse(codec.missing)
      require(location != InputLocation.Path || missing.isEmpty, s"Path parameter $name must be required and have no default")
      InputSpec(handle, name, location, codec.schema, config.descriptionValue,
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
    val handle = new Input[A](owner, inputFactories.size, config)
    inputFactories += (() =>
      val rules = config.constraints.toVector
      config.defaultValue.foreach(value => require(rules.forall(_.accepts(value)), "Invalid body default"))
      val missing = config.defaultValue.orElse(model.missing)
      InputSpec(handle, "body", InputLocation.Body, model.schema, config.descriptionValue,
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
    require(method != "HEAD" || mediaType.isEmpty, "HEAD responses must not have a body")
    val config = new ResponseScope(gate)
    val result = new OutputDeclaration[A](owner, outputFactories.size, encode, config)
    outputFactories += (() => ResponseSpec(status,
      config.descriptionValue.getOrElse(org.http4s.Status.fromInt(status).toOption.get.reason), mediaType, schema))
    result

  private[sprout] def setHandler(run: RequestContext => IO[EndpointResult]): Unit =
    gate.check()
    require(handler.isEmpty, s"Duplicate handler: $method ${path.value}")
    handler = Some(run)

  private[sprout] def freeze(): BoundEndpoint =
    val inputs = inputFactories.map(_()).toVector
    val outputs = outputFactories.map(_()).toVector
    val identity = s"$method ${path.value}"
    require(handler.nonEmpty, s"Missing handler: $identity")
    require(outputs.nonEmpty, s"No responses declared: $identity")
    require(outputs.map(_.status).distinct.size == outputs.size, s"Duplicate response status: $identity")
    val keys = inputs.map(i => i.location -> (if i.location == InputLocation.Header then i.name.toLowerCase(java.util.Locale.ROOT) else i.name))
    require(keys.distinct.size == keys.size, s"Duplicate input: $identity")
    require(inputs.count(_.location == InputLocation.Body) <= 1, s"Only one request body is allowed: $identity")
    require(inputs.filter(_.location == InputLocation.Path).map(_.name).toSet == path.parameters.toSet,
      s"Path parameters do not match template: $identity")
    val automaticId = method.toLowerCase + "_" + path.value.replaceAll("[^A-Za-z0-9]", "_").stripPrefix("_")
    val id = operationIdValue.getOrElse(automaticId)
    require(id.nonEmpty, s"Empty operationId: $identity")
    BoundEndpoint(EndpointSpec(owner, method, path, id, summaryValue, descriptionValue,
      (group.inheritedTags ++ tags).distinct, inputs, outputs), handler.get)

final class AppScope private[sprout] () extends ConfigScope:
  private[sprout] val gate = new ConfigurationGate
  private[sprout] val serverScope = new ServerScope(gate)
  private[sprout] val openApiScope = new OpenApiScope(gate)
  private[sprout] val endpoints = ArrayBuffer.empty[EndpointScope]

  private[sprout] def freeze(): Application =
    gate.close()
    val bound = endpoints.map(_.freeze()).toVector
    val identities = bound.map(e => e.spec.method -> e.spec.path.value)
    require(identities.distinct.size == identities.size, "Duplicate method/path registration")
    bound.groupBy(_.spec.path.shape).values.foreach { sameShape =>
      require(sameShape.map(_.spec.path.value).distinct.size == 1, "Conflicting path templates with different parameter names")
    }
    val ids = bound.map(_.spec.operationId)
    require(ids.distinct.size == ids.size, "Duplicate operationId")
    val docsPath = PathPattern.parse(openApiScope.endpointValue)
    require(docsPath.parameters.isEmpty, "OpenAPI endpoint must be a static path")
    require(!bound.exists(e => e.spec.path.value == docsPath.value), "OpenAPI endpoint conflicts with a registered route")
    require(serverScope.maxBodyBytesValue > 0 && serverScope.maxBodyBytesValue <= Int.MaxValue - 1L, "Invalid maximum body size")
    val info = openApiScope.infoScope
    require(info.titleValue.nonEmpty && info.versionValue.nonEmpty, "OpenAPI title and version must not be empty")
    Application.build(bound, ServerSettings(serverScope.hostValue, serverScope.portValue, serverScope.maxBodyBytesValue),
      OpenApiSettings(docsPath.value, info.titleValue, info.versionValue, info.descriptionValue))
