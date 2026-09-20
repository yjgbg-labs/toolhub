package sprout

import cats.effect.IO

private[sprout] final case class ScopeDecl(
    inputs: Vector[InputSpec[?]],
    responses: Vector[ResponseSpec],
    middleware: Vector[Handler => Handler],
    tags: Vector[String]
)

private[sprout] sealed trait RouteDecl

private[sprout] final case class HandlerDecl[A](
    success: Option[Output[A]],
    logic: RequestContext => (A | EndpointResult | IO[A | EndpointResult])
)

private[sprout] final case class GroupDecl(
    name: String,
    description: Option[String],
    scope: ScopeDecl,
    children: Vector[RouteDecl]
) extends RouteDecl

private[sprout] final case class EndpointDecl(
    method: String,
    path: String,
    operation: Option[String],
    operationId: Option[String],
    summary: Option[String],
    description: Option[String],
    scope: ScopeDecl,
    handler: Option[HandlerDecl[?]]
) extends RouteDecl

private[sprout] final case class ApplicationDecl(
    server: ServerSettings,
    openApi: OpenApiSettings,
    routes: Vector[GroupDecl]
)

private[sprout] final case class ResolvedEndpoint(
    spec: EndpointSpec,
    handler: HandlerDecl[?],
    middleware: Vector[Handler => Handler]
)

private[sprout] final case class ResolvedApi(
    server: ServerSettings,
    openApi: OpenApiSettings,
    endpoints: Vector[ResolvedEndpoint],
    groups: Vector[OperationGroup]
)
