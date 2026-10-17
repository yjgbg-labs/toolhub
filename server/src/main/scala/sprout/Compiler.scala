package sprout

import cats.effect.IO
import scala.collection.mutable

private[sprout] object Compiler:
  private val empty = ScopeDecl(Vector.empty, Vector.empty, Vector.empty, Vector.empty)

  private def inherit(parent: ScopeDecl, child: ScopeDecl): ScopeDecl =
    ScopeDecl(parent.inputs ++ child.inputs, parent.responses ++ child.responses,
      parent.middleware ++ child.middleware, (parent.tags ++ child.tags).distinct)

  def resolve(app: ApplicationDecl): ResolvedApi =
    val tags = mutable.ArrayBuffer.empty[ApiTag]
    val endpoints = mutable.ArrayBuffer.empty[ResolvedEndpoint]

    def visit(node: RouteDecl, prefix: String, inherited: ScopeDecl): Unit = node match
      case group: GroupDecl =>
        group.tag.foreach(tags += _)
        val path = PathPattern.join(prefix, group.path)
        group.children.foreach(visit(_, path, inherit(inherited, group.scope)))
      case endpoint: EndpointDecl =>
        val scope = inherit(inherited, endpoint.scope)
        val route = PathPattern.parse(PathPattern.join(prefix, endpoint.path))
        val identity = s"${endpoint.method} ${route.value}"
        require(endpoint.handler.nonEmpty, s"Missing handler: $identity")
        require(scope.responses.nonEmpty, s"No responses declared: $identity")
        require(scope.responses.map(_.status).distinct.size == scope.responses.size, s"Duplicate response status: $identity")
        require(endpoint.method != "HEAD" || scope.responses.forall(_.mediaType.isEmpty), "HEAD responses must not have a body")
        val keys = scope.inputs.map(i => i.location -> (if i.location == InputLocation.Header then i.name.toLowerCase(java.util.Locale.ROOT) else i.name))
        require(keys.distinct.size == keys.size, s"Duplicate input: $identity")
        require(scope.inputs.count(_.location == InputLocation.Body) <= 1, s"Only one request body is allowed: $identity")
        require(scope.inputs.filter(_.location == InputLocation.Path).map(_.name).toSet == route.parameters.toSet,
          s"Path parameters do not match template: $identity")
        val automaticId = endpoint.method.toLowerCase + "_" + route.value.replaceAll("[^A-Za-z0-9]", "_").stripPrefix("_")
        val id = endpoint.operationId.getOrElse(automaticId)
        require(id.nonEmpty, s"Empty operationId: $identity")
        val spec = EndpointSpec(endpoint.method, route, id, endpoint.summary, endpoint.description,
          scope.tags, scope.inputs, scope.responses, responses(endpoint.method, scope))
        endpoints += ResolvedEndpoint(spec, endpoint.handler.get, scope.middleware)

    app.routes.foreach(visit(_, "", empty))
    val resolved = endpoints.toVector
    val identities = resolved.map(e => e.spec.method -> e.spec.path.value)
    require(identities.distinct.size == identities.size, "Duplicate method/path registration")
    resolved.groupBy(_.spec.path.shape).values.foreach { sameShape =>
      require(sameShape.map(_.spec.path.value).distinct.size == 1, "Conflicting path templates with different parameter names")
    }
    val ids = resolved.map(_.spec.operationId)
    require(ids.distinct.size == ids.size, "Duplicate operationId")
    val tagSpecs = tags.toVector.groupBy(_.name).toVector.sortBy(_._1).map { (name, declarations) =>
      val descriptions = declarations.flatMap(_.description).distinct
      require(descriptions.size <= 1, s"Conflicting descriptions for tag: $name")
      ApiTag(name, descriptions.headOption)
    }
    val docsPath = PathPattern.parse(app.openApi.endpoint)
    require(docsPath.parameters.isEmpty, "OpenAPI endpoint must be a static path")
    require(!resolved.exists(_.spec.path.value == docsPath.value), "OpenAPI endpoint conflicts with a registered route")
    require(app.server.maxBodyBytes > 0 && app.server.maxBodyBytes <= Int.MaxValue - 1L, "Invalid maximum body size")
    require(app.openApi.title.nonEmpty && app.openApi.version.nonEmpty, "OpenAPI title and version must not be empty")
    ResolvedApi(app.server, app.openApi.copy(endpoint = docsPath.value), resolved, tagSpecs)

  private def responses(method: String, scope: ScopeDecl): Vector[ResponseContract] =
    val declared = scope.responses.map { response =>
      ResponseContract(response.status, response.description,
        response.mediaType.zip(response.schema).map((mediaType, schema) => ResponseContent(mediaType, Vector(schema))).toVector)
    }
    val automatic = Vector(500 -> "Internal server error") ++
      Option.when(scope.inputs.nonEmpty)(400 -> "Invalid request").toVector ++
      (if scope.inputs.exists(_.location == InputLocation.Body) then
        Vector(413 -> "Request body too large", 415 -> "Expected application/json") else Vector.empty)
    automatic.foldLeft(declared) { case (responses, (status, description)) =>
      val existing = responses.find(_.status == status)
      val content = if method == "HEAD" then Vector.empty else
        val original = existing.toVector.flatMap(_.content)
        val schemas = original.find(_.mediaType == "application/json").toVector.flatMap(_.schemas) :+ JsonModel[ErrorBody].schema
        original.filterNot(_.mediaType == "application/json") :+ ResponseContent("application/json", schemas)
      val combined = ResponseContract(status, existing.fold(description)(r => s"${r.description}; $description"), content)
      existing.fold(responses :+ combined)(_ => responses.map(r => if r.status == status then combined else r))
    }

  def bind(endpoint: ResolvedEndpoint): BoundEndpoint =
    def respond(action: => IO[EndpointResult]): IO[EndpointResult] = IO.defer(action).recover {
      case raised: RaisedResponse => raised.result
    }
    val base: RequestContext => IO[EndpointResult] = context => respond(endpoint.handler(context))
    val run = endpoint.middleware.foldRight(base) { (wrap, next) => context => respond {
      val handler: Handler = (ctx: RequestContext) ?=> next(ctx)
      val wrapped: Handler = wrap(handler)
      wrapped(using context)
    } }
    BoundEndpoint(endpoint.spec, run)
