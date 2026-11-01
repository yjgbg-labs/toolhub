package groot.kube

import framework.rpc.CommandsScope
import framework.rpcDsl.*
import io.circe.Json

object KubeRoutes:
  private def context(using CommandsScope) = param[String]("context", "Explicit saved context name; never inferred") { minLength(1) }
  private def namespace(using CommandsScope) = param[Option[String]]("namespace", "Namespace; defaults to the saved context namespace")
  private def resource(using CommandsScope) = param[String]("resource", "Discovered resource, e.g. pods or deployments.apps") { minLength(1) }

  def register(service: KubeService)(using CommandsScope): Unit = commands("kube") {
    description("Call Kubernetes apiservers directly with explicit persistent contexts")
    exposeOn(cli, codemode, graaljs)
    val errors = Vector(300, 301, 302, 303, 307, 308, 400, 401, 403, 404, 405, 406, 408, 409, 410, 413, 415, 422, 429, 500, 501, 502, 503, 504)
      .map(status => status -> response.json[Json](status) { description("Kubernetes Status or local connection/validation error") }).toMap
    val unmodified = response.empty(304) { description("Upstream Not Modified") }
    recover {
      case failure: KubeFailure if failure.status == 304 => unmodified(())
      case failure: KubeFailure => errors.getOrElse(failure.status, errors(502))(failure.body)
    }
    commands("contexts") {
      command[ContextSummary]("put") {
        description("Create or fully replace a context; certificates and unencrypted private key use PEM; returns a redacted summary")
        val name = param[String]("name", "Context name") { minLength(1); maxLength(128) }
        val config = body[ContextConfig]("Apiserver origin, default namespace, optional CA PEM, client certificate/key PEM pair and bearer token")
        handle { service.putContext(name.value, config.value) }
      }
      command[Vector[ContextSummary]]("list") {
        description("List saved context summaries without credentials")
        handle { service.listContexts }
      }
      command[Boolean]("delete") {
        description("Remove a context; in-flight requests finish using the old connection pool")
        val name = param[String]("name", "Context name") { minLength(1) }
        handle { service.deleteContext(name.value) }
      }
    }
    command[Vector[ApiResource]]("resources") {
      description("Refresh API discovery, including CRDs; lists preferred versions unless apiVersion is supplied")
      val ctx = context
      val version = param[Option[String]]("apiVersion", "Optional exact version, e.g. apps/v1")
      handle { service.resources(ctx.value, version.value) }
    }
    command[WithHeaders[Vector[Json]]]("get") {
      description("Get a collection or [named resource]; pagination headers: X-Kube-Continue, X-Kube-Resource-Version, X-Kube-Remaining-Item-Count")
      val ctx = context
      val res = resource
      val ns = namespace
      val name = param[Option[String]]("name", "Optional resource name; a missing named resource returns 404")
      val all = param[Boolean]("allNamespaces", "Read across namespaces") { default(false) }
      val labels = param[Option[String]]("labelSelector", "Kubernetes label selector")
      val fields = param[Option[String]]("fieldSelector", "Kubernetes field selector")
      val limit = param[Option[Int]]("limit", "Page size; use smaller pages if the response exceeds 768 KiB")
      val next = param[Option[String]]("continue", "Opaque cursor from X-Kube-Continue")
      handle { service.get(ctx.value, res.value, name.value, ns.value, all.value, labels.value, fields.value, limit.value, next.value) }
    }
    command[Vector[Json]]("apply") {
      description("Server-Side Apply objects sequentially; conflict is 409, force defaults false; a failed batch may be partially applied")
      val ctx = context
      val input = body[ApplyRequest]("Resource objects with apiVersion/kind/metadata.name, optional fieldManager, force and dryRun")
      handle { service.apply(ctx.value, input.value) }
    }
    command[Json]("delete") {
      description("Delete a named resource and return the apiserver result; finalizers may keep it present after acceptance")
      val ctx = context
      val res = resource
      val ns = namespace
      val name = param[String]("name", "Explicit resource name") { minLength(1) }
      val policy = param[String]("propagationPolicy", "Background, Foreground or Orphan") { default("Background") }
      val grace = param[Option[Long]]("gracePeriodSeconds", "Optional nonnegative grace period")
      val ignore = param[Boolean]("ignoreNotFound", "Return a missing object's Status as a successful command result") { default(false) }
      val dry = param[Boolean]("dryRun", "Validate without deleting") { default(false) }
      handle { service.delete(ctx.value, res.value, name.value, ns.value, policy.value, grace.value, ignore.value, dry.value) }
    }
    command[PlainText]("logs") {
      description("Read bounded Pod logs without following")
      val ctx = context
      val ns = namespace
      val name = param[String]("name", "Pod name") { minLength(1) }
      val container = param[Option[String]]("container", "Container name")
      val tail = param[Int]("tail", "Number of final log lines") { default(100); minimum(0) }
      val since = param[Option[Long]]("since", "Read logs newer than this many seconds")
      val previous = param[Boolean]("previous", "Read the previous terminated container's logs") { default(false) }
      val bytes = param[Int]("limitBytes", "Maximum log bytes requested") { default(65536); minimum(1); maximum(786432) }
      handle { service.logs(ctx.value, name.value, ns.value, container.value, tail.value, since.value, previous.value, bytes.value) }
    }
    command[Json]("request") {
      description("GET an apiserver-relative path; return JSON or a JSON string for text; redirects and streaming are unsupported")
      val ctx = context
      val path = param[String]("path", "Absolute relative path with optional query, e.g. /version or /readyz") { minLength(1) }
      handle { service.request(ctx.value, path.value) }
    }
  }
