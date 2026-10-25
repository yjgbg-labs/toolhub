package sprout.sdk

import io.circe.Json

/** Missing and explicit null are different on the wire. */
enum Optional[+A]:
  case Missing
  case Present(value: A)

  def toOption: Option[A] = this match
    case Missing => None
    case Present(value) => Some(value)

final case class HttpRequest(
    method: String,
    path: String,
    query: Vector[(String, String)] = Vector.empty,
    headers: Vector[(String, String)] = Vector.empty,
    body: Option[String] = None
):
  def url(baseUrl: String): String =
    val suffix = if query.isEmpty then "" else query.map((k, v) => s"${Wire.encode(k)}=${Wire.encode(v)}").mkString("?", "&", "")
    baseUrl.stripSuffix("/") + path + suffix

final case class HttpResponse(status: Int, headers: Vector[(String, String)], body: String):
  def mediaType: Option[String] = headers.collectFirst {
    case (name, value) if name.equalsIgnoreCase("content-type") => value.takeWhile(_ != ';').trim.toLowerCase
  }

sealed abstract class ClientError(message: String, cause: Throwable = null) extends Exception(message, cause)
object ClientError:
  final case class Http(response: HttpResponse) extends ClientError(s"HTTP ${response.status}: ${response.body}")
  final case class Decode(message: String, response: HttpResponse) extends ClientError(message)
  final case class Transport(error: Throwable) extends ClientError(error.toString, error)
  def fromThrowable(error: Throwable): ClientError = error match
    case known: ClientError => known
    case other => Transport(other)

/** No effect type class, HTTP backend or platform types appear in this interface. */
trait Executor[F[_]]:
  def execute[A](call: Call[A]): F[A]

final case class Call[A](request: HttpRequest, decode: HttpResponse => Either[ClientError, A]):
  def map[B](f: A => B): Call[B] = copy(decode = response => decode(response).map(f))
  def withResponse: Call[(HttpResponse, A)] = copy(decode = response => decode(response).map(response -> _))

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

  def json[A](response: HttpResponse, codec: JsonCodec[A]): Either[ClientError, A] =
    io.circe.jawn.parse(response.body).left.map(_.message).flatMap(codec.read)
      .left.map(message => ClientError.Decode(message, response))

  def unexpected[A](response: HttpResponse): Either[ClientError, A] =
    if response.status >= 200 && response.status < 300 then Left(ClientError.Decode("Undeclared success status or content type", response))
    else Left(ClientError.Http(response))
