package groot.kube

import framework.model.JsonModel
import io.circe.Json

case class ContextConfig(server: String, namespace: String = "default", caCertificate: Option[String] = None,
    clientCertificate: Option[String] = None, clientKey: Option[String] = None, token: Option[String] = None) derives JsonModel:
  override def toString: String = s"ContextConfig($server,$namespace,<redacted>)"
case class ContextSummary(name: String, server: String, namespace: String, customCa: Boolean,
    clientCertificate: Boolean, token: Boolean) derives JsonModel
case class ApiResource(apiVersion: String, name: String, kind: String, namespaced: Boolean,
    singularName: String, shortNames: Vector[String], verbs: Vector[String]) derives JsonModel
case class KubeItems[A](items: Vector[A]) derives JsonModel
case class KubePagination(continue: Option[String], resourceVersion: Option[String], remainingItemCount: Option[Long]) derives JsonModel
case class KubeGetResult(items: Vector[Json], pagination: Option[KubePagination]) derives JsonModel
case class ApplyRequest(objects: Vector[Json], fieldManager: String = "sprout", force: Boolean = false,
    dryRun: Boolean = false) derives JsonModel
private[kube] case class KubeFailure(status: Int, body: Json) extends RuntimeException("Kubernetes request failed")
private[kube] object KubeFailure:
  def local(status: Int, reason: String, message: String): KubeFailure = KubeFailure(status, Json.obj(
    "apiVersion" -> Json.fromString("v1"), "kind" -> Json.fromString("Status"), "status" -> Json.fromString("Failure"),
    "code" -> Json.fromInt(status), "reason" -> Json.fromString(reason), "message" -> Json.fromString(message)))
  def require(valid: Boolean, message: String): Unit = if !valid then throw local(400, "Invalid", message)
