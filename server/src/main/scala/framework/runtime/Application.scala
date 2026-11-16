package framework.runtime

import cats.data.Kleisli
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import framework.Client
import framework.http.*
import framework.model.JsonModel
import io.circe.{Json, Printer}
import io.circe.syntax.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import org.http4s.{Header, HttpApp, Request, Response, Status}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import scala.util.Try
import sttp.apispec.openapi.circe.*

final class Application private[framework] (
    boundEndpoints: Vector[BoundEndpoint],
    val serverSettings: ServerSettings,
    val openApiSettings: OpenApiSettings,
    val openApi: sttp.apispec.openapi.OpenAPI
):
  val openApiJson: String = Printer.noSpaces.copy(sortKeys = true).print(openApi.asJson)
  private val etag = "\"" + MessageDigest.getInstance("SHA-256").digest(openApiJson.getBytes(UTF_8))
    .map(b => f"${b & 0xff}%02x").mkString + "\""
  private val ordered = boundEndpoints.sortBy(e => (e.spec.path.priority, e.spec.path.value, e.spec.method)).reverse
  private val publicEndpoints = ordered.filter(endpoint => endpoint.spec.exposedOn.exists(_ != Client.graaljs))
  private val docs = PathPattern.parse(openApiSettings.endpoint)

  private def httpHandler(
      visible: Vector[BoundEndpoint],
      source: String,
      cors: Option[CorsSettings] = None,
      includeDocs: Boolean = false
  ): HttpApp[IO] = Kleisli { request =>
    IO.defer {
      val actual = request.uri.path.segments.map(_.decoded()).toVector
      val segments = if actual.lastOption.contains("") then actual.dropRight(1) else actual
      val isDocs = includeDocs && docs.matches(segments).nonEmpty
      val matches = if isDocs then Vector.empty else
        visible.flatMap(endpoint => endpoint.spec.path.matches(segments).map(endpoint -> _))
      val samePath = matches.headOption.toVector.flatMap { (best, _) =>
        matches.filter(_._1.spec.path.value == best.spec.path.value)
      }
      val method = if cors.nonEmpty && request.method.name == "OPTIONS" && headerValues(request, "Origin").nonEmpty then
        headerValues(request, "Access-Control-Request-Method").headOption.getOrElse(request.method.name)
      else request.method.name
      // Resolve logging policy even for validation errors, 405s and CORS preflights.
      val logged = samePath.find(_._1.spec.method == method)
        .orElse(samePath.find(!_._1.spec.logSkip)).orElse(samePath.headOption).map(_._1.spec)
      AccessLog(serverSettings.accessLog, request, logged, source) { log =>
        def failed(error: Throwable): IO[Response[IO]] =
          log.failed(error) *> IO(scribe.error("Request failed", error))
            .as(errorResponse(500, "internal_error", "Internal server error"))
        val handler: HttpApp[IO] = Kleisli { req =>
          IO.defer(if isDocs then document(req) else route(req, samePath, log)).handleErrorWith(failed)
        }
        val decorated = cors.fold(handler)(Cors(_, handler))
        decorated(request).handleErrorWith(failed).map { response =>
          if request.method.name == "HEAD" then response.withBodyStream(fs2.Stream.empty) else response
        }
      }
    }.handleErrorWith { error =>
      // Failures before route selection still have an access record and a 500 response.
      AccessLog(serverSettings.accessLog, request, None, source) { log =>
        log.failed(error) *> IO(scribe.error("Request failed", error))
          .as(errorResponse(500, "internal_error", "Internal server error"))
      }.map(response => if request.method.name == "HEAD" then response.withBodyStream(fs2.Stream.empty) else response)
    }
  }

  val httpApp: HttpApp[IO] = httpHandler(publicEndpoints, "http", serverSettings.cors, includeDocs = true)

  val endpoints: Vector[Endpoint] = ordered.map { endpoint =>
    val spec = endpoint.spec
    new Endpoint(spec.operationId, spec.method, spec.path.value, spec.inputs.map { input =>
      Endpoint.Input(input.name, input.location, input.schema.node(), input.bodyMediaType)
    }, spec.exposedOn, httpHandler(Vector(endpoint), "in_process"))
  }

  def resource: Resource[IO, org.http4s.server.Server] =
    EmberServerBuilder.default[IO]
      .withHost(Host.fromString(serverSettings.host).get)
      .withPort(Port.fromInt(serverSettings.port).get)
      .withHttpApp(httpApp)
      .build.evalTap(server => IO {
        scribe.info(s"Groot HTTP listening at ${server.address}; OpenAPI: ${openApiSettings.endpoint}")
      })

  def serve: IO[Nothing] = resource.useForever

  private def document(request: Request[IO]): IO[Response[IO]] =
    if Set("GET", "HEAD").contains(request.method.name) then
      val matches = headerValues(request, "If-None-Match").flatMap(_.split(",")).exists { value =>
        val tag = value.trim.stripPrefix("W/")
        tag == "*" || tag == etag
      }
      val result = if matches then Response[IO](Status.NotModified) else textResponse(200, openApiJson, "application/json")
      IO.pure(result.putHeaders(Header.Raw(CIString("ETag"), etag)))
    else IO.pure(errorResponse(405, "method_not_allowed", "Method not allowed")
      .putHeaders(Header.Raw(CIString("Allow"), "GET, HEAD")))

  private def route(
      request: Request[IO],
      matches: Vector[(BoundEndpoint, Map[String, String])],
      log: AccessLog.Context
  ): IO[Response[IO]] =
    if matches.isEmpty then IO.pure(errorResponse(404, "not_found", "Route not found"))
    else matches.find(_._1.spec.method == request.method.name) match
      case None => IO.pure(errorResponse(405, "method_not_allowed", "Method not allowed")
        .putHeaders(Header.Raw(CIString("Allow"), matches.map(_._1.spec.method).distinct.sorted.mkString(", "))))
      case Some((endpoint, params)) => execute(endpoint, params, request, log)

  private def execute(endpoint: BoundEndpoint, params: Map[String, String], request: Request[IO], log: AccessLog.Context): IO[Response[IO]] =
    val spec = endpoint.spec
    def values(input: InputSpec[?]): Vector[String] = input.location match
      case InputLocation.Path => params.get(input.name).toVector
      case InputLocation.Query => request.uri.query.multiParams.get(input.name).toVector.flatten
      case InputLocation.Header => headerValues(request, input.name)
      case InputLocation.Cookie => request.cookies.filter(_.name == input.name).map(_.content).toVector
      case InputLocation.Body => Vector.empty
    val logParams = spec.inputs.filter(i => i.location != InputLocation.Body && !i.logSkip).traverse_ { input =>
      log.input(s"${input.location.toString.toLowerCase}.${input.name}", {
        val raw = values(input)
        if raw.isEmpty then input.default
        else if raw.size == 1 && !input.repeated then Some(Json.fromString(raw.head))
        else Some(Json.fromValues(raw.map(Json.fromString)))
      })
    }
    val body = spec.inputs.find(_.location == InputLocation.Body) match
      case Some(input) => readBody(request, input.bodyMediaType.get)
      case None => IO.pure(Right(None): Either[Response[IO], Option[Json]])
    logParams *> body.flatMap {
      case Left(response) => IO.pure(response)
      case Right(json) =>
        val logBody = spec.inputs.find(i => i.location == InputLocation.Body && !i.logSkip)
          .fold(IO.unit)(input => log.input("body", json.orElse(input.default)))
        val results = spec.inputs.map { input =>
          val raw = input.location match
            case InputLocation.Body => RawInput.Body(json)
            case _ => RawInput.Values(values(input))
          input.decode(raw)
        }
        val errors = results.collect { case Left(error) => error }.toList
        logBody *> (if errors.nonEmpty then IO.pure(errorResponse(400, "invalid_request", "Request validation failed", errors))
        else
          val context = new RequestContext(endpoint.inputSlots, results.collect { case Right(value) => value })
          endpoint.run(context).flatMap { result =>
            val output = endpoint.outputs.getOrElse(result.key, throw new IllegalArgumentException("Response belongs to a different endpoint"))
            (output.mediaType, result.payload) match
              case (Some(mediaType), Some(ResponseBody.Text(payload))) => IO.pure(textResponse(output.status, payload, mediaType))
              case (Some(mediaType), Some(ResponseBody.Streaming(bytes))) =>
                log.streaming.as(Response[IO](Status.fromInt(output.status).toOption.get).withBodyStream(bytes)
                  .putHeaders(Header.Raw(CIString("Content-Type"), mediaType), Header.Raw(CIString("Cache-Control"), "no-cache")))
              case (None, None) => IO.pure(Response[IO](Status.fromInt(output.status).toOption.get))
              case _ => throw new IllegalStateException("Response does not match its declaration")
          })
    }

  private def readBody(request: Request[IO], mediaType: String): IO[Either[Response[IO], Option[Json]]] =
    request.body.take(serverSettings.maxBodyBytes + 1).compile.to(Array).map { bytes =>
      val isText = mediaType == "text/plain"
      val matchesType = request.headers.get[`Content-Type`].exists(_.mediaType == org.http4s.MediaType.parse(mediaType).toOption.get)
      if bytes.length > serverSettings.maxBodyBytes then Left(errorResponse(413, "body_too_large", "Request body too large"))
      else if bytes.isEmpty && isText && matchesType then Right(Some(Json.fromString("")))
      else if bytes.isEmpty then Right(None)
      else if !matchesType then Left(errorResponse(415, "unsupported_media_type", s"Expected $mediaType"))
      else
        val decoded = Try(UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString).toEither.left.map(_ => "Invalid UTF-8")
          .flatMap(text => if isText then Right(Json.fromString(text)) else io.circe.parser.parse(text).left.map(_ => "Malformed JSON"))
        decoded.left.map(message => errorResponse(400, if isText then "invalid_text" else "invalid_json", message)).map(Some(_))
    }

  private def headerValues(request: Request[IO], name: String): Vector[String] =
    request.headers.headers.filter(_.name == CIString(name)).map(_.value).toVector

  private def errorResponse(status: Int, code: String, message: String, errors: List[FieldError] = Nil): Response[IO] =
    textResponse(status, JsonModel[ErrorBody].encoder(ErrorBody(code, message, errors)).noSpaces, "application/json")

  private def textResponse(status: Int, payload: String, mediaType: String): Response[IO] =
    Response[IO](Status.fromInt(status).toOption.get).withEntity(payload)
      .putHeaders(Header.Raw(CIString("Content-Type"), mediaType))

private[framework] object Application:
  def build(api: ResolvedApi): Application =
    new Application(api.endpoints.map(Compiler.bind), api.server, api.openApi,
      OpenApi.generate(api.endpoints.map(_.spec), api.openApi, api.extensions))
