package framework.http

import cats.effect.IO
import io.circe.Json

final case class ServerSettings(host: String, port: Int, maxBodyBytes: Long)
final case class OpenApiSettings(endpoint: String, title: String, version: String, description: Option[String])

private[framework] final case class ScopeDecl(
    inputs: Vector[InputSpec[?]],
    responses: Vector[ResponseSpec],
    middleware: Vector[Handler => Handler],
    tags: Vector[String]
)

private[framework] object ScopeDecl:
  def combine(scopes: Vector[ScopeDecl]): ScopeDecl =
    ScopeDecl(scopes.flatMap(_.inputs), scopes.flatMap(_.responses),
      scopes.flatMap(_.middleware), scopes.flatMap(_.tags).distinct)

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
