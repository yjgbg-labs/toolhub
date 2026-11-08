package groot.http

import framework.rpc.CommandsScope
import framework.rpcDsl.*

object HttpRoutes:
  def register(service: HttpService)(using CommandsScope): Unit = command[HttpResponse]("http") {
    exposeOn(graaljs)
    description("Send an HTTP/HTTPS request with method, headers and optional UTF-8/base64 body; return buffered response bytes as base64")
    val request = body[HttpRequest]("HTTP request; defaults to GET, string bodies use UTF-8, and bodyEncoding=base64 accepts binary data")
    val invalid = response.json[HttpError](400) { description("Invalid request or failed HTTP connection") }
    val large = response.json[HttpError](413) { description("Request or response exceeds the size limit") }
    val timeout = response.json[HttpError](504) { description("HTTP request exceeded its 30-second deadline") }
    recover {
      case error: HttpFailure if error.name == "RangeError" => large(HttpError(error.name, error.getMessage))
      case error: HttpFailure if error.name == "TimeoutError" => timeout(HttpError(error.name, error.getMessage))
      case error: HttpFailure => invalid(HttpError(error.name, error.getMessage))
    }
    handle { service(request.value) }
  }
