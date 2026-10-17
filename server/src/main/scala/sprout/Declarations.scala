package sprout

import cats.effect.IO

private[sprout] final case class ScopeDecl(
    inputs: Vector[InputSpec[?]],
    responses: Vector[ResponseSpec],
    middleware: Vector[Handler => Handler],
    tags: Vector[String]
)

private[sprout] sealed trait RouteDecl

private[sprout] final case class GroupDecl(
    path: String,
    tag: Option[ApiTag],
    scope: ScopeDecl,
    children: Vector[RouteDecl]
) extends RouteDecl

private[sprout] final case class EndpointDecl(
    method: String,
    path: String,
    operationId: Option[String],
    summary: Option[String],
    description: Option[String],
    scope: ScopeDecl,
    handler: Option[RequestContext => IO[EndpointResult]]
) extends RouteDecl

private[sprout] final case class ApplicationDecl(
    server: ServerSettings,
    openApi: OpenApiSettings,
    routes: Vector[GroupDecl]
)

private[sprout] final case class ResolvedEndpoint(
    spec: EndpointSpec,
    handler: RequestContext => IO[EndpointResult],
    middleware: Vector[Handler => Handler]
)

private[sprout] final case class ResolvedApi(
    server: ServerSettings,
    openApi: OpenApiSettings,
    endpoints: Vector[ResolvedEndpoint],
    tags: Vector[ApiTag]
)
