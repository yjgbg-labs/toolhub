package framework.runtime

import cats.data.Kleisli
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import framework.Client
import framework.http.*
import framework.model.JsonModel
import fs2.concurrent.{Signal, SignallingRef}
import io.circe.{Json, Printer}
import io.circe.syntax.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import org.http4s.{Header, HttpApp, Request, Response, Status}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import scala.concurrent.duration.*
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

  private def httpHandler(run: (Request[IO], Vector[String]) => IO[Response[IO]]): HttpApp[IO] = Kleisli { request =>
    IO.defer {
      val actual = request.uri.path.segments.map(_.decoded()).toVector
      val segments = if actual.lastOption.contains("") then actual.dropRight(1) else actual
      run(request, segments)
    }.handleErrorWith { error =>
      IO(scribe.error("Request failed", error)).as(errorResponse(500, "internal_error", "Internal server error"))
    }.map { response =>
      if request.method.name == "HEAD" then response.withBodyStream(fs2.Stream.empty) else response
    }
  }

  val httpApp: HttpApp[IO] =
    val handler = httpHandler((req, segs) => dispatch(req, segs, serverSettings.maxBodyBytes))
    serverSettings.cors.fold(handler)(Cors(_, handler))

  val endpoints: Vector[Endpoint] = ordered.map { endpoint =>
    val spec = endpoint.spec
    new Endpoint(spec.operationId, spec.method, spec.path.value, spec.inputs.map { input =>
      Endpoint.Input(input.name, input.location, input.schema.node(), input.bodyMediaType)
    }, spec.exposedOn, httpHandler((request, segments) => route(request, segments, Vector(endpoint), serverSettings.maxBodyBytes)))
  }

  def dynamicHttpApp(settingsSignal: Signal[IO, ServerSettings]): HttpApp[IO] =
    val appCache = new AtomicReference[(ServerSettings, HttpApp[IO])](null)
    def appFor(s: ServerSettings): HttpApp[IO] =
      val cached = appCache.get()
      if cached != null && cached._1 == s then cached._2
      else
        val base = httpHandler((req, segs) => dispatch(req, segs, s.maxBodyBytes))
        val wrapped = s.cors.fold(base)(Cors(_, base))
        appCache.set(s -> wrapped)
        wrapped
    Kleisli { req =>
      settingsSignal.get.flatMap(s => appFor(s).run(req))
    }

  def resource: Resource[IO, org.http4s.server.Server] =
    EmberServerBuilder.default[IO]
      .withHost(Host.fromString(serverSettings.host).get)
      .withPort(Port.fromInt(serverSettings.port).get)
      .withHttpApp(httpApp)
      .build.evalTap(server => IO {
        scribe.info(s"Groot HTTP listening at ${server.address}; OpenAPI: ${openApiSettings.endpoint}")
      })

  /** Bind EmberServer and dynamically rebind `host` / `port` in-place whenever `settingsSignal` changes. */
  def resourceDynamic(settingsSignal: Signal[IO, ServerSettings]): Resource[IO, Signal[IO, InetSocketAddress]] =
    val app = dynamicHttpApp(settingsSignal)
    def bindOne(h: String, p: Int): IO[(org.http4s.server.Server, IO[Unit])] =
      for
        hostVal <- IO.fromOption(Host.fromString(h.trim))(new IllegalArgumentException(s"Invalid server.host: $h"))
        portVal <- IO.fromOption(Port.fromInt(p))(new IllegalArgumentException(s"Invalid server.port: $p"))
        allocated <- EmberServerBuilder.default[IO]
          .withHost(hostVal)
          .withPort(portVal)
          .withHttpApp(app)
          .withShutdownTimeout(2.seconds)
          .build
          .allocated
        _ <- IO(scribe.info(s"Groot HTTP listening at ${allocated._1.address}; OpenAPI: ${openApiSettings.endpoint}"))
      yield allocated

    final case class ActiveListener(host: String, configuredPort: Int, server: org.http4s.server.Server, release: IO[Unit])

    for
      mutex <- Resource.eval(Mutex[IO])
      init <- Resource.eval(settingsSignal.get)
      initHost = init.host.trim
      first <- Resource.eval(bindOne(initHost, init.port))
      activeRef <- Resource.make(
        Ref.of[IO, Option[ActiveListener]](Some(ActiveListener(initHost, init.port, first._1, first._2)))
      )(ref => mutex.lock.surround(ref.getAndSet(None).flatMap(_.traverse_(_.release.attempt.void))))
      addrSignal <- Resource.eval(SignallingRef[IO, InetSocketAddress](first._1.address))
      rebindTo = (nextHost: String, nextPort: Int) =>
        mutex.lock.surround {
          activeRef.get.flatMap {
            case None => IO.unit
            case Some(cur) if cur.host == nextHost && (cur.configuredPort == nextPort || cur.server.address.getPort == nextPort) =>
              IO.unit
            case Some(cur) if nextPort != cur.server.address.getPort =>
              bindOne(nextHost, nextPort).attempt.flatMap {
                case Right((newSrv, newRel)) =>
                  cur.release.attempt.void *>
                    activeRef.set(Some(ActiveListener(nextHost, nextPort, newSrv, newRel))) *>
                    addrSignal.set(newSrv.address) *>
                    IO(scribe.info(s"Rebound Groot HTTP listener from ${cur.server.address} to ${newSrv.address}"))
                case Left(err) =>
                  IO(scribe.error(s"Failed to rebind Groot HTTP listener to $nextHost:$nextPort; keeping ${cur.server.address} active", err))
              }
            case Some(cur) =>
              // Same port, different host interface (e.g. 0.0.0.0 -> 127.0.0.1): release first, with automatic rollback on failure.
              cur.release.attempt.void *>
                bindOne(nextHost, nextPort).attempt.flatMap {
                  case Right((newSrv, newRel)) =>
                    activeRef.set(Some(ActiveListener(nextHost, nextPort, newSrv, newRel))) *>
                      addrSignal.set(newSrv.address) *>
                      IO(scribe.info(s"Rebound Groot HTTP listener from ${cur.host}:${cur.server.address.getPort} to ${newSrv.address}"))
                  case Left(err) =>
                    IO(scribe.error(s"Failed to bind Groot HTTP listener to $nextHost:$nextPort; restoring ${cur.host}:${cur.server.address.getPort}", err)) *>
                      bindOne(cur.host, cur.server.address.getPort).attempt.flatMap {
                        case Right((restoredSrv, restoredRel)) =>
                          activeRef.set(Some(ActiveListener(cur.host, cur.configuredPort, restoredSrv, restoredRel))) *>
                            addrSignal.set(restoredSrv.address)
                        case Left(fatal) =>
                          activeRef.set(None) *> IO(scribe.error("Failed to restore previous HTTP listener", fatal))
                      }
                }
          }
        }
      _ <- settingsSignal.map(s => (s.host.trim, s.port)).discrete.changes
        .evalMap((h, p) => rebindTo(h, p).handleErrorWith(e => IO(scribe.error("Unexpected HTTP listener rebind error", e))))
        .compile.drain.background
    yield addrSignal

  def serve: IO[Nothing] = resource.useForever

  def serveDynamic(settingsSignal: Signal[IO, ServerSettings]): IO[Nothing] =
    resourceDynamic(settingsSignal).useForever

  private def dispatch(request: Request[IO], segments: Vector[String], maxBodyBytes: Long): IO[Response[IO]] =
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
    else route(request, segments, publicEndpoints, maxBodyBytes)

  private def route(request: Request[IO], segments: Vector[String], visible: Vector[BoundEndpoint], maxBodyBytes: Long): IO[Response[IO]] =
    val matches = visible.flatMap(endpoint => endpoint.spec.path.matches(segments).map(params => endpoint -> params))
    matches.headOption match
      case None => IO.pure(errorResponse(404, "not_found", "Route not found"))
      case Some((best, _)) =>
        val samePath = matches.filter(_._1.spec.path.value == best.spec.path.value)
        samePath.find(_._1.spec.method == request.method.name) match
          case None => IO.pure(errorResponse(405, "method_not_allowed", "Method not allowed")
            .putHeaders(Header.Raw(CIString("Allow"), samePath.map(_._1.spec.method).distinct.sorted.mkString(", "))))
          case Some((endpoint, params)) => execute(endpoint, params, request, maxBodyBytes)

  private def execute(endpoint: BoundEndpoint, params: Map[String, String], request: Request[IO], maxBodyBytes: Long): IO[Response[IO]] =
    val spec = endpoint.spec
    val body = spec.inputs.find(_.location == InputLocation.Body) match
      case Some(input) => readBody(request, input.bodyMediaType.get, maxBodyBytes)
      case None => IO.pure(Right(None): Either[Response[IO], Option[Json]])
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
              case (Some(mediaType), Some(ResponseBody.Text(payload))) => textResponse(output.status, payload, mediaType)
              case (Some(mediaType), Some(ResponseBody.Streaming(bytes))) =>
                Response[IO](Status.fromInt(output.status).toOption.get).withBodyStream(bytes)
                  .putHeaders(Header.Raw(CIString("Content-Type"), mediaType), Header.Raw(CIString("Cache-Control"), "no-cache"))
              case (None, None) => Response[IO](Status.fromInt(output.status).toOption.get)
              case _ => throw new IllegalStateException("Response does not match its declaration")
          }
    }

  private def readBody(request: Request[IO], mediaType: String, maxBodyBytes: Long): IO[Either[Response[IO], Option[Json]]] =
    request.body.take(maxBodyBytes + 1).compile.to(Array).map { bytes =>
      val isText = mediaType == "text/plain"
      val matchesType = request.headers.get[`Content-Type`].exists(_.mediaType == org.http4s.MediaType.parse(mediaType).toOption.get)
      if bytes.length > maxBodyBytes then Left(errorResponse(413, "body_too_large", "Request body too large"))
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
