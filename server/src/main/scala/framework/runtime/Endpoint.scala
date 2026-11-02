package framework.runtime

import cats.effect.IO
import framework.Client
import framework.http.{InputLocation, ResponseBody}
import framework.model.SchemaNode
import io.circe.{Json, Printer}
import java.nio.charset.StandardCharsets.UTF_8
import org.http4s.HttpApp

/** Frozen declaration with HTTP and structured entry points sharing validation and middleware. */
final class Endpoint private[framework] (
    val operationId: String,
    val method: String,
    val path: String,
    val inputs: Vector[Endpoint.Input],
    val exposedOn: Set[Client],
    val httpApp: HttpApp[IO],
    val invoke: Endpoint.Request => IO[Endpoint.Response]
)

object Endpoint:
  /** Parameter keys use their declared location and name; JSON bodies remain structured. */
  final case class Request(
      parameters: Map[(InputLocation, String), Vector[String]] = Map.empty,
      body: Option[Json] = None
  )

  final case class Response(status: Int, mediaType: Option[String], body: Option[ResponseBody]):
    lazy val contentLength: Option[Int] = body.flatMap {
      case ResponseBody.Json(value) => Some(jsonBytes(value))
      case ResponseBody.Text(value) => Some(value.getBytes(UTF_8).length)
      case _: ResponseBody.Streaming => None
    }
    lazy val headers: Map[String, String] = mediaType.map("content-type" -> _).toMap ++
      contentLength.map(length => "content-length" -> length.toString) ++
      Option.when(body.exists(_.isInstanceOf[ResponseBody.Streaming]))("cache-control" -> "no-cache")

  private[framework] def jsonBytes(value: Json): Int = Printer.noSpaces.printToByteBuffer(value).remaining()

  final case class Input(
      name: String,
      location: InputLocation,
      schema: SchemaNode,
      bodyMediaType: Option[String]
  )
