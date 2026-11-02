package groot.fetch

import framework.model.JsonModel

enum BodyEncoding derives JsonModel:
  case utf8, base64

enum ResponseType derives JsonModel:
  case base64, text, json

case class FetchRequest(
    url: String,
    method: String = "GET",
    headers: Map[String, String] = Map.empty,
    body: Option[String] = None,
    bodyEncoding: BodyEncoding = BodyEncoding.utf8,
    responseType: ResponseType = ResponseType.base64
) derives JsonModel

case class FetchResponse(
    url: String,
    status: Int,
    statusText: String,
    ok: Boolean,
    redirected: Boolean,
    headers: Map[String, String],
    bodyBase64: Option[String],
    setCookies: List[String],
    bodyText: Option[String] = None,
    bodyJson: Option[io.circe.Json] = None
) derives JsonModel

case class FetchError(name: String, message: String) derives JsonModel
case class FetchFailure(name: String, message: String) extends RuntimeException(message)
