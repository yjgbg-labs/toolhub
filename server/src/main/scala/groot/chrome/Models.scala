package groot.chrome

import framework.model.JsonModel
import io.circe.Json

final case class ChromeFailure(code: String, message: String) extends RuntimeException(message)

case class ChromeInstance(instanceName: String, status: String, kiosk: Boolean, headless: Boolean,
    pid: Option[Long], profilePath: String) derives JsonModel
case class ChromeTab(tabName: String, targetId: String, url: String, title: String) derives JsonModel
case class PageInfo(url: String, title: String) derives JsonModel
case class SnapshotNode(ref: Option[String], frameId: String, role: String, name: String,
    value: Option[Json], depth: Int) derives JsonModel
case class PageSnapshot(url: String, title: String, nodes: Vector[SnapshotNode], truncated: Boolean) derives JsonModel
case class Screenshot(path: String, mimeType: String, width: Int, height: Int) derives JsonModel
case class ChromeCookie(name: String, value: String, domain: String, path: String, expires: Double,
    httpOnly: Boolean, secure: Boolean, sameSite: Option[String], partitionKey: Option[Json]) derives JsonModel

private[chrome] case class SavedInstance(instanceName: String, kiosk: Boolean, headless: Boolean,
    pid: Option[Long] = None, startedAt: Option[String] = None, tabs: Map[String, String] = Map.empty) derives JsonModel

private[chrome] object ChromeJson:
  def str(value: String): Json = Json.fromString(value)
  def obj(values: (String, Json)*): Json = Json.obj(values*)
  def text(json: Json, name: String): String = json.hcursor.get[String](name).getOrElse("")
  def array(json: Json, name: String): Vector[Json] = json.hcursor.get[Vector[Json]](name).getOrElse(Vector.empty)
  def field(json: Json, name: String): Json = json.hcursor.downField(name).focus.getOrElse(Json.Null)
  def flag(json: Json, name: String): Boolean = json.hcursor.get[Boolean](name).getOrElse(false)

private[chrome] object ChromeValidation:
  def name(value: String): Unit =
    if !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}") then
      throw ChromeFailure("invalid_chrome", "Names must contain 1–64 ASCII letters, digits, dots, underscores or hyphens, starting with a letter or digit")
  def timeout(value: Int): Unit =
    if value < 1 || value > 120000 then throw ChromeFailure("invalid_chrome", "timeoutMs must be between 1 and 120000")
  def domain(value: String): String =
    val normalized = try java.net.IDN.toASCII(value.stripPrefix(".").stripSuffix(".")).toLowerCase(java.util.Locale.ROOT)
      catch case _: IllegalArgumentException => throw ChromeFailure("invalid_chrome", "domain must be a valid hostname")
    if normalized.isEmpty || normalized.length > 253 || normalized.split("\\.", -1).exists(part =>
        part.isEmpty || part.length > 63 || !part.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) then
      throw ChromeFailure("invalid_chrome", "domain must be a hostname, without a scheme, port or path")
    normalized
  def matchesDomain(cookie: String, requested: String): Boolean =
    val host = cookie.stripPrefix(".").toLowerCase(java.util.Locale.ROOT)
    host == requested || host.endsWith("." + requested)
