package groot.http

import framework.model.JsonModel
import framework.validation.*

final case class HttpConfig(
    @duration timeout: String = "30s",
    @positive maxBytes: Int = 1024 * 1024,
    @min(0) @max(100) maxRedirects: Int = 20,
    @positive maxHeaderBytes: Int = 64 * 1024
) derives JsonModel, Validator

enum BodyEncoding derives JsonModel:
  case utf8, base64

case class HttpRequest(
    url: String,
    method: String = "GET",
    headers: Map[String, String] = Map.empty,
    body: Option[String] = None,
    bodyEncoding: BodyEncoding = BodyEncoding.utf8
) derives JsonModel

case class HttpResponse(
    url: String,
    status: Int,
    statusText: String,
    ok: Boolean,
    redirected: Boolean,
    headers: Map[String, String],
    bodyBase64: String,
    setCookies: List[String]
) derives JsonModel

case class HttpError(name: String, message: String) derives JsonModel
case class HttpFailure(name: String, message: String) extends RuntimeException(message)
