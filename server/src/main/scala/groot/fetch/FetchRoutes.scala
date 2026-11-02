package groot.fetch

import framework.rpc.CommandsScope
import framework.rpcDsl.*

object FetchRoutes:
  def register(service: FetchService)(using CommandsScope): Unit = command[FetchResponse]("fetch") {
    exposeOn(graaljs)
    description("Fetch an HTTP/HTTPS URL; responseType selects base64 (default), UTF-8 text or parsed JSON")
    val request = body[FetchRequest]("HTTP request; bodyEncoding selects utf8/base64 input, responseType selects bodyBase64/bodyText/bodyJson output")
    val invalid = response.json[FetchError](400) { description("Invalid request or failed HTTP connection") }
    val large = response.json[FetchError](413) { description("Request or response exceeds the size limit") }
    val timeout = response.json[FetchError](504) { description("HTTP request exceeded its 30-second deadline") }
    recover {
      case error: FetchFailure if error.name == "RangeError" => large(FetchError(error.name, error.getMessage))
      case error: FetchFailure if error.name == "TimeoutError" => timeout(FetchError(error.name, error.getMessage))
      case error: FetchFailure => invalid(FetchError(error.name, error.getMessage))
    }
    handle { service(request.value) }
  }
