package framework.runtime

import cats.data.Kleisli
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port, UnixSocketAddress}
import framework.http.*
import framework.model.JsonModel
import io.circe.{Json, Printer}
import io.circe.syntax.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.MessageDigest
import org.http4s.{Header, HttpApp, Request, Response, Status}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import scala.util.Try
import sttp.apispec.openapi.circe.*

final class Application private[framework] (
    endpoints: Vector[BoundEndpoint],
    val serverSettings: ServerSettings,
    val openApiSettings: OpenApiSettings,
    val openApi: sttp.apispec.openapi.OpenAPI
):
  val openApiJson: String = Printer.noSpaces.copy(sortKeys = true).print(openApi.asJson)
  private val etag = "\"" + MessageDigest.getInstance("SHA-256").digest(openApiJson.getBytes(UTF_8))
    .map(b => f"${b & 0xff}%02x").mkString + "\""
  private val ordered = endpoints.sortBy(e => (e.spec.path.priority, e.spec.path.value, e.spec.method)).reverse
  private val docs = PathPattern.parse(openApiSettings.endpoint)

  val httpApp: HttpApp[IO] = Kleisli { request =>
    IO.defer(dispatch(request)).handleErrorWith { error =>
      IO(scribe.error("Request failed", error)).as(errorResponse(500, "internal_error", "Internal server error"))
    }.map { response =>
      if request.method.name == "HEAD" then response.withBodyStream(fs2.Stream.empty) else response
    }
  }

  def resource: Resource[IO, org.http4s.server.Server] =
    val builder = EmberServerBuilder.default[IO]
      .withHost(Host.fromString(serverSettings.host).get)
      .withPort(Port.fromInt(serverSettings.port).get)
      .withHttpApp(httpApp)
    val bound = serverSettings.socket match
      case None => builder.build
      case Some(value) =>
        UnixListener.resource(Path.of(value),
          builder.withUnixSocketConfig(UnixSocketAddress(value), deleteIfExists = false, deleteOnClose = false).build)
    bound.evalTap(server => IO {
      val address = serverSettings.socket.fold(server.address.toString)(path => s"unix://$path")
      scribe.info(s"Sprout HTTP listening at $address; OpenAPI: ${openApiSettings.endpoint}")
    })

  def serve: IO[Nothing] = resource.useForever

  private def dispatch(request: Request[IO]): IO[Response[IO]] =
    val actual = request.uri.path.segments.map(_.decoded()).toVector
    val segments = if actual.lastOption.contains("") then actual.dropRight(1) else actual
    if docs.matches(segments).nonEmpty then
      if Set("GET", "HEAD").contains(request.method.name) then
        val matches = headerValues(request, "If-None-Match").flatMap(_.split(",")).exists { value =>
          val tag = value.trim.stripPrefix("W/")
          tag == "*" || tag == etag
        }
        val result = if matches then Response[IO](Status.NotModified) else textResponse(200, openApiJson, "application/json")
        IO.pure(result.putHeaders(Header.Raw(CIString("ETag"), etag)))
      else IO.pure(errorResponse(405, "method_not_allowed", "Method not allowed")
        .putHeaders(Header.Raw(CIString("Allow"), "GET, HEAD")))
    else
      val matches = ordered.flatMap(endpoint => endpoint.spec.path.matches(segments).map(params => endpoint -> params))
      matches.headOption match
        case None => IO.pure(errorResponse(404, "not_found", "Route not found"))
        case Some((best, _)) =>
          val samePath = matches.filter(_._1.spec.path.value == best.spec.path.value)
          samePath.find(_._1.spec.method == request.method.name) match
            case None => IO.pure(errorResponse(405, "method_not_allowed", "Method not allowed")
              .putHeaders(Header.Raw(CIString("Allow"), samePath.map(_._1.spec.method).distinct.sorted.mkString(", "))))
            case Some((endpoint, params)) => execute(endpoint, params, request)

  private def execute(endpoint: BoundEndpoint, params: Map[String, String], request: Request[IO]): IO[Response[IO]] =
    val spec = endpoint.spec
    val body = if spec.inputs.exists(_.location == InputLocation.Body) then readBody(request)
               else IO.pure(Right(None): Either[Response[IO], Option[Json]])
    body.flatMap {
      case Left(response) => IO.pure(response)
      case Right(json) =>
        val results = spec.inputs.map { input =>
          val raw = input.location match
            case InputLocation.Path   => RawInput.Values(params.get(input.name).toVector)
            case InputLocation.Query  => RawInput.Values(request.uri.query.multiParams.get(input.name).toVector.flatten)
            case InputLocation.Header => RawInput.Values(headerValues(request, input.name))
            case InputLocation.Cookie => RawInput.Values(request.cookies.filter(_.name == input.name).map(_.content).toVector)
            case InputLocation.Body   => RawInput.Body(json)
          input.decode(raw)
        }
        val errors = results.collect { case Left(error) => error }.toList
        if errors.nonEmpty then IO.pure(errorResponse(400, "invalid_request", "Request validation failed", errors))
        else
          val context = new RequestContext(endpoint.inputSlots, results.collect { case Right(value) => value })
          endpoint.run(context).map { result =>
            val output = endpoint.outputs.getOrElse(result.key, throw new IllegalArgumentException("Response belongs to a different endpoint"))
            (output.mediaType, result.payload) match
              case (Some(mediaType), Some(payload)) => textResponse(output.status, payload, mediaType)
              case (None, None) => Response[IO](Status.fromInt(output.status).toOption.get)
              case _ => throw new IllegalStateException("Response does not match its declaration")
          }
    }

  private def readBody(request: Request[IO]): IO[Either[Response[IO], Option[Json]]] =
    request.body.take(serverSettings.maxBodyBytes + 1).compile.to(Array).map { bytes =>
      if bytes.length > serverSettings.maxBodyBytes then Left(errorResponse(413, "body_too_large", "Request body too large"))
      else if bytes.isEmpty then Right(None)
      else if !request.headers.get[`Content-Type`].exists(_.mediaType == org.http4s.MediaType.application.json) then
        Left(errorResponse(415, "unsupported_media_type", "Expected application/json"))
      else
        val decoded = Try(UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString).toEither.left.map(_ => "Invalid UTF-8")
          .flatMap(text => io.circe.parser.parse(text).left.map(_ => "Malformed JSON"))
        decoded.left.map(message => errorResponse(400, "invalid_json", message)).map(Some(_))
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
