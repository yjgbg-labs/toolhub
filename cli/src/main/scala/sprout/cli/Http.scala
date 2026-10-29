package sprout.cli

import io.circe.Json

final case class HttpRequest(
    method: String,
    path: String,
    query: Vector[(String, String)] = Vector.empty,
    headers: Vector[(String, String)] = Vector.empty,
    body: Option[String] = None,
    allowStreaming: Boolean = false
):
  def url(baseUrl: String): String =
    val suffix = if query.isEmpty then "" else query.map((k, v) => s"${Wire.encode(k)}=${Wire.encode(v)}").mkString("?", "&", "")
    baseUrl.stripSuffix("/") + path + suffix

final case class HttpResponse(status: Int, headers: Vector[(String, String)], body: String):
  def mediaType: Option[String] = headers.collectFirst {
    case (name, value) if name.equalsIgnoreCase("content-type") => value.takeWhile(_ != ';').trim.toLowerCase
  }

object Wire:
  def encode(value: String): String =
    val hex = "0123456789ABCDEF"
    val result = new StringBuilder
    value.getBytes("UTF-8").foreach { byte =>
      val n = byte & 255
      if (n >= 65 && n <= 90) || (n >= 97 && n <= 122) || (n >= 48 && n <= 57) || "-._~".contains(n.toChar) then result.append(n.toChar)
      else result.append('%').append(hex(n >> 4)).append(hex(n & 15))
    }
    result.toString

  def scalar(value: Json): String = value.asString.getOrElse(value.noSpaces)
  def parameters(name: String, value: Json, explode: Boolean): Vector[(String, String)] =
    if value.isNull then Vector.empty
    else value.asArray match
      case Some(items) if explode => items.map(item => name -> scalar(item))
      case Some(items) => Vector(name -> items.map(scalar).mkString(","))
      case None => Vector(name -> scalar(value))
