package framework.http

import cats.effect.IO
import framework.Client
import framework.model.JsonModel
import framework.validation.*
import io.circe.Json
import scala.concurrent.duration.FiniteDuration

final case class CorsConfig(
    @nonBlank allowOrigins: String = "*",
    @duration maxAge: String = "1h"
) derives JsonModel, Validator

final case class ServerConfig(
    @hostAddress host: String = "0.0.0.0",
    @min(1) @max(65535) port: Int = 18080,
    @positive maxBodyBytes: Long = 1024L * 1024L,
    cors: CorsConfig = CorsConfig()
) derives JsonModel, Validator:
  def toSettings: ServerSettings =
    val origins = cors.allowOrigins.split(",").iterator.map(_.trim).filter(_.nonEmpty).toSet
    ServerSettings(
      host = host.trim,
      port = port,
      maxBodyBytes = maxBodyBytes,
      cors = Some(
        CorsSettings(
          allowedOrigins = if origins.isEmpty then Set("*") else origins,
          allowedMethods = Set("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"),
          allowedHeaders = Set("*"),
          exposedHeaders = Set("ETag", "Allow"),
          allowCredentials = false,
          maxAge = Contracts.parseDuration(cors.maxAge)
        )
      )
    )

final case class ServerSettings(host: String, port: Int, maxBodyBytes: Long, cors: Option[CorsSettings] = None)
final case class CorsSettings(
    allowedOrigins: Set[String],
    allowedMethods: Set[String],
    allowedHeaders: Set[String],
    exposedHeaders: Set[String],
    allowCredentials: Boolean,
    maxAge: FiniteDuration
)
final case class OpenApiSettings(endpoint: String, title: String, version: String, description: Option[String])

private[framework] final case class ScopeDecl(
    inputs: Vector[InputSpec[?]],
    responses: Vector[ResponseSpec],
    middleware: Vector[Handler => Handler],
    tags: Vector[String],
    exposedOn: Set[Client] = Client.all
)

private[framework] object ScopeDecl:
  def combine(scopes: Vector[ScopeDecl]): ScopeDecl =
    ScopeDecl(scopes.flatMap(_.inputs), scopes.flatMap(_.responses),
      scopes.flatMap(_.middleware), scopes.flatMap(_.tags).distinct,
      scopes.foldLeft(Client.all)((clients, scope) => clients.intersect(scope.exposedOn)))

private[framework] final case class EndpointDecl(
    method: String,
    path: String,
    operationId: Option[String],
    summary: Option[String],
    description: Option[String],
    scope: ScopeDecl,
    handler: Option[RequestContext => IO[EndpointResult]]
)

private[framework] final case class ApplicationDecl(
    server: ServerSettings,
    openApi: OpenApiSettings,
    endpoints: Vector[EndpointDecl],
    extensions: Map[String, Json]
)

private[framework] final case class ResolvedEndpoint(
    spec: EndpointSpec,
    handler: RequestContext => IO[EndpointResult],
    middleware: Vector[Handler => Handler]
)

private[framework] final case class ResolvedApi(
    server: ServerSettings,
    openApi: OpenApiSettings,
    endpoints: Vector[ResolvedEndpoint],
    extensions: Map[String, Json]
)
