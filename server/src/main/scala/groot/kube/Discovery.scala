package groot.kube

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json

private[kube] final class Discovery(client: KubeClient):
  @volatile private var cached = Map.empty[String, (Long, Json)]
  private def document(path: String, refresh: Boolean): IO[Json] = IO.defer {
    cached.get(path) match
      case Some((time, json)) if !refresh && System.nanoTime() - time < 60000000000L => IO.pure(json)
      case _ => client.json("GET", path).flatTap(json => IO { synchronized { cached = cached.updated(path, (System.nanoTime(), json)) } })
  }
  private def array(json: Json, field: String): Vector[Json] = json.hcursor.get[Vector[Json]](field)
    .fold(_ => throw KubeFailure.local(502, "InvalidDiscovery", s"Missing discovery $field"), identity)

  def resources(apiVersion: Option[String] = None, refresh: Boolean = false): IO[Vector[ApiResource]] =
    val versions = apiVersion.fold((document("/api", refresh), document("/apis", refresh)).mapN { (core, groups) =>
      val coreVersions = core.hcursor.get[Vector[String]]("versions").getOrElse(Vector.empty)
      coreVersions ++ array(groups, "groups").map(_.hcursor.downField("preferredVersion").get[String]("groupVersion")
        .fold(_ => throw KubeFailure.local(502, "InvalidDiscovery", "Missing preferred group version"), identity))
    })(version => IO.pure(Vector(version)))
    versions.flatMap(_.traverse { version =>
      document(Discovery.base(version), refresh).map { doc => array(doc, "resources").filterNot(_.hcursor.get[String]("name").getOrElse("").contains('/')).map { value =>
        val c = value.hcursor
        def required[A: io.circe.Decoder](field: String): A = c.get[A](field).fold(_ => throw KubeFailure.local(502, "InvalidDiscovery", s"Invalid resource $field"), identity)
        ApiResource(version, required[String]("name"), required[String]("kind"), required[Boolean]("namespaced"),
          c.get[String]("singularName").getOrElse(""), c.get[Vector[String]]("shortNames").getOrElse(Vector.empty), required[Vector[String]]("verbs"))
      } }
    }.map(_.flatten))

  def resolve(resource: String): IO[ApiResource] =
    val parts = resource.split("\\.", 2)
    def select(values: Vector[ApiResource]): Option[ApiResource] =
      val matching = values.filter { r =>
        val group = if r.apiVersion.contains('/') then r.apiVersion.takeWhile(_ != '/') else ""
        (parts.length == 1 || parts(1) == group) && (Vector(r.name, r.singularName, r.kind) ++ r.shortNames).exists(_.equalsIgnoreCase(parts(0)))
      }
      val preferred = if parts.length == 1 && matching.exists(!_.apiVersion.contains('/')) then matching.filter(!_.apiVersion.contains('/')) else matching
      KubeFailure.require(preferred.size <= 1, s"Ambiguous resource $resource; qualify it with its API group")
      preferred.headOption
    resources().flatMap(values => select(values).fold(resources(refresh = true).map(select))((r: ApiResource) => IO.pure(Some(r))))
      .flatMap(value => IO.fromOption(value)(KubeFailure.local(404, "ResourceNotFound", s"Unknown resource: $resource")))

  def kind(apiVersion: String, kind: String): IO[ApiResource] =
    resources(Some(apiVersion)).flatMap { values =>
      values.find(_.kind == kind).fold(resources(Some(apiVersion), refresh = true).map(_.find(_.kind == kind)))(r => IO.pure(Some(r)))
    }.flatMap(value => IO.fromOption(value)(KubeFailure.local(404, "ResourceNotFound", s"Unknown kind $apiVersion/$kind")))

private[kube] object Discovery:
  def base(version: String): String = version.split("/", -1).toList match
    case core :: Nil => "/api/" + KubeClient.segment(core)
    case group :: version :: Nil => "/apis/" + KubeClient.segment(group) + "/" + KubeClient.segment(version)
    case _ => throw KubeFailure.local(400, "Invalid", "Invalid apiVersion")
