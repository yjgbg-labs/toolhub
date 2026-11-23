package groot.http

import framework.model.JsonModel
import framework.validation.{Contracts, Validator, contract}

final case class HttpConfig(
    @contract[String](Contracts.isDuration, "must be a positive duration") timeout: String = "30s",
    @contract[Int](_ > 0) maxBytes: Int = 1024 * 1024,
    @contract[Int](r => r >= 0 && r <= 100, "must be in 0..100") maxRedirects: Int = 20,
    @contract[Int](_ > 0) maxHeaderBytes: Int = 64 * 1024
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
