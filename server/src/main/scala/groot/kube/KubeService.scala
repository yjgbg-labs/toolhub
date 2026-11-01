package groot.kube

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.rpc.{PlainText, WithHeaders}
import io.circe.Json
import java.nio.file.Path

final class KubeService private[kube] (private[kube] val contexts: Contexts):
  import KubeService.*
  def listContexts: IO[Vector[ContextSummary]] = contexts.list
  def putContext(name: String, config: ContextConfig): IO[ContextSummary] = contexts.put(name, config)
  def deleteContext(name: String): IO[Boolean] = contexts.delete(name)
  def resources(context: String, apiVersion: Option[String]): IO[Vector[ApiResource]] = contexts.lease(context).use(_.discovery.resources(apiVersion, refresh = true))

  def get(context: String, resource: String, name: Option[String], namespace: Option[String], allNamespaces: Boolean,
      labelSelector: Option[String], fieldSelector: Option[String], limit: Option[Int], continue: Option[String]): IO[WithHeaders[Vector[Json]]] =
    contexts.lease(context).use { client => IO.defer {
      KubeFailure.require(!allNamespaces || (namespace.isEmpty && name.isEmpty), "allNamespaces cannot be combined with namespace or name")
      KubeFailure.require(name.isEmpty || (labelSelector.isEmpty && fieldSelector.isEmpty && limit.isEmpty && continue.isEmpty), "Selectors and pagination require a collection request")
      KubeFailure.require(limit.forall(n => n > 0 && n <= 10000), "limit must be between 1 and 10000")
      client.discovery.resolve(resource).flatMap { r =>
        val query = params("labelSelector" -> labelSelector, "fieldSelector" -> fieldSelector, "limit" -> limit.map(_.toString), "continue" -> continue)
        client.json("GET", resourcePath(client, r, namespace, name, allNamespaces), query).map { json =>
          if name.nonEmpty then WithHeaders(Vector(json))
          else
            val values = json.hcursor.get[Vector[Json]]("items").fold(_ => throw KubeFailure.local(502, "InvalidResponse", "Apiserver response has no items array"), identity)
            val metadata = json.hcursor.downField("metadata")
            val headers = Vector("X-Kube-Continue" -> metadata.get[String]("continue").toOption.filter(_.nonEmpty),
              "X-Kube-Resource-Version" -> metadata.get[String]("resourceVersion").toOption,
              "X-Kube-Remaining-Item-Count" -> metadata.get[Long]("remainingItemCount").toOption.map(_.toString)).collect { case (key, Some(value)) => key -> value }.toMap
            WithHeaders(values, headers)
        }
      }
    } }

  def apply(context: String, input: ApplyRequest): IO[Vector[Json]] = contexts.lease(context).use { client => IO.defer {
    KubeFailure.require(input.objects.nonEmpty && input.objects.size <= 100, "objects must contain 1 to 100 resources")
    KubeFailure.require(input.fieldManager.nonEmpty && input.fieldManager.length <= 128 && !input.fieldManager.exists(Character.isISOControl), "Invalid fieldManager")
    val query = Vector("fieldManager" -> input.fieldManager, "force" -> input.force.toString) ++ Option.when(input.dryRun)("dryRun" -> "All")
    // Validate and resolve the whole batch before the first write; API failures can still leave a partial batch.
    input.objects.traverse { obj =>
      def required(json: Json, key: String): String = json.hcursor.get[String](key).toOption.filter(_.nonEmpty)
        .getOrElse(throw KubeFailure.local(400, "Invalid", s"Apply object requires $key"))
      val version = required(obj, "apiVersion")
      val kind = required(obj, "kind")
      val metadata = obj.hcursor.downField("metadata").focus.getOrElse(Json.Null)
      val name = required(metadata, "name")
      val namespace = metadata.hcursor.get[Option[String]]("namespace").fold(_ => throw KubeFailure.local(400, "Invalid", "Invalid metadata.namespace"), identity)
      client.discovery.kind(version, kind).map { resource =>
        val path = resourcePath(client, resource, namespace, Some(name))
        val normalized = if resource.namespaced && namespace.isEmpty then obj.mapObject(_.add("metadata", metadata.mapObject(_.add("namespace", Json.fromString(client.config.namespace))))) else obj
        path -> normalized
      }
    }.flatMap(_.traverse { (path, obj) => client.json("PATCH", path, query, Some(obj), "application/apply-patch+yaml") })
  } }

  def delete(context: String, resource: String, name: String, namespace: Option[String], propagationPolicy: String,
      gracePeriodSeconds: Option[Long], ignoreNotFound: Boolean, dryRun: Boolean): IO[Json] = contexts.lease(context).use { client => IO.defer {
    KubeFailure.require(Set("Background", "Foreground", "Orphan").contains(propagationPolicy), "Invalid propagationPolicy")
    KubeFailure.require(gracePeriodSeconds.forall(_ >= 0), "gracePeriodSeconds must not be negative")
    client.discovery.resolve(resource).flatMap { r =>
      val body = Json.obj("apiVersion" -> Json.fromString("v1"), "kind" -> Json.fromString("DeleteOptions"),
        "propagationPolicy" -> Json.fromString(propagationPolicy)).mapObject { obj =>
          gracePeriodSeconds.fold(obj)(n => obj.add("gracePeriodSeconds", Json.fromLong(n)))
        }
      client.json("DELETE", resourcePath(client, r, namespace, Some(name)), Option.when(dryRun)("dryRun" -> "All").toVector, Some(body))
        .recover { case error: KubeFailure if ignoreNotFound && error.status == 404 => error.body }
    }
  } }

  def logs(context: String, name: String, namespace: Option[String], container: Option[String], tail: Int,
      since: Option[Long], previous: Boolean, limitBytes: Int): IO[PlainText] = contexts.lease(context).use { client => IO.defer {
    KubeFailure.require(tail >= 0 && since.forall(_ > 0) && limitBytes > 0 && limitBytes <= KubeClient.maxBytes, "Invalid log limits")
    val path = s"/api/v1/namespaces/${KubeClient.segment(namespace.getOrElse(client.config.namespace))}/pods/${KubeClient.segment(name)}/log"
    val query = params("container" -> container, "sinceSeconds" -> since.map(_.toString)) ++
      Vector("tailLines" -> tail.toString, "previous" -> previous.toString, "limitBytes" -> limitBytes.toString, "follow" -> "false")
    client.send("GET", path, query).map(PlainText(_))
  } }

  def request(context: String, path: String): IO[Json] = contexts.lease(context).use { client => IO.defer {
    KubeClient.relativePath(path)
    val uri = org.http4s.Uri.unsafeFromString(path)
    KubeFailure.require(!uri.query.multiParams.exists { (key, values) => Set("watch", "follow").contains(key) && values.exists(v => v != "false" && v != "0") }, "Streaming watch/follow is not supported")
    client.send("GET", path).map(text => io.circe.parser.parse(text).getOrElse(Json.fromString(text)))
  } }

object KubeService:
  def resource(home: Path = groot.schedule.Store.defaultHome.resolve("kube")): Resource[IO, KubeService] = Contexts.resource(home).map(new KubeService(_))
  private def params(values: (String, Option[String])*): Vector[(String, String)] = values.toVector.collect { case (key, Some(value)) => key -> value }
  private def resourcePath(client: KubeClient, resource: ApiResource, namespace: Option[String], name: Option[String], all: Boolean = false): String =
    KubeFailure.require(resource.namespaced || namespace.isEmpty, "Cluster-scoped resources do not accept namespace")
    val scope = if resource.namespaced && !all then s"/namespaces/${KubeClient.segment(namespace.getOrElse(client.config.namespace))}" else ""
    Discovery.base(resource.apiVersion) + scope + "/" + KubeClient.segment(resource.name) + name.fold("")(n => "/" + KubeClient.segment(n))
