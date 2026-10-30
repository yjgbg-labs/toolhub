package framework.rpc

import cats.effect.IO
import framework.http.{EndpointScope, Output, ResponseBody}
import framework.model.{JsonModel, Schema}
import fs2.{Chunk, Stream}
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.NotGiven

trait SuccessResponse[A]:
  private[framework] def mediaType: Option[String]
  private[framework] def schema: Option[Schema[?]]
  private[framework] def encode(value: A): Option[ResponseBody]

  private[framework] final def declare(scope: EndpointScope): Output[A] =
    scope.output[A](200, mediaType, schema, encode)

object SuccessResponse extends JsonSuccessResponses:
  given SuccessResponse[PlainText] with
    private[framework] val mediaType = Some("text/plain")
    private[framework] val schema = Some(JsonModel[String].schema)
    private[framework] def encode(value: PlainText) = Some(ResponseBody.Text(value))

  given SuccessResponse[Shell] with
    private[framework] val mediaType = Some("application/x-sh")
    private[framework] val schema = Some(JsonModel[String].schema)
    private[framework] def encode(value: Shell) = Some(ResponseBody.Text(value))

  given SuccessResponse[Unit] with
    private[framework] val mediaType = None
    private[framework] val schema = None
    private[framework] def encode(value: Unit) = None

  given [A](using element: SuccessResponse[A], scalar: NotGiven[A <:< SSE[?]]): SuccessResponse[SSE[A]] with
    private[framework] val mediaType = Some("text/event-stream")
    // OpenAPI 3.1 describes the event-stream wire body as text, not a JSON array.
    private[framework] val schema = Some(JsonModel[String].schema)
    private[framework] def encode(value: SSE[A]) = Some(ResponseBody.Streaming(
      value.events.flatMap { event =>
        val data = element.encode(event) match
          case Some(ResponseBody.Text(text)) => text
          case None => ""
          case Some(ResponseBody.Streaming(_)) => throw new IllegalArgumentException("SSE data must be a scalar response")
        Stream.chunk[IO, Byte](Chunk.array(SSE.frame(data).getBytes(UTF_8)))
      }
    ))

private[framework] trait JsonSuccessResponses:
  given [A](using model: JsonModel[A]): SuccessResponse[A] with
    private[framework] val mediaType = Some("application/json")
    private[framework] val schema = Some(model.schema)
    private[framework] def encode(value: A) = Some(ResponseBody.Text(model.encoder(value).noSpaces))
