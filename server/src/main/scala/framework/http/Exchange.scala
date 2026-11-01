package framework.http

import cats.effect.IO
import framework.model.JsonModel
import fs2.Stream

private[framework] final class InputKey[A]
private[framework] final class ResponseKey[A]

type Handler = RequestContext ?=> IO[EndpointResult]

final class Input[A] private[framework] (
    private[framework] val key: InputKey[A],
    private val config: InputScope[A]
):
  def apply(init: InputScope[A] ?=> Unit): Input[A] =
    config.gate.check()
    init(using config)
    this

final class RequestContext private[framework] (slots: Map[InputKey[?], Int], values: Vector[Any]):
  def read[A](input: Input[A]): A =
    val slot = slots.getOrElse(input.key, throw new IllegalArgumentException("Input belongs to a different endpoint"))
    values(slot).asInstanceOf[A]

private[framework] enum ResponseBody:
  case Text(value: String)
  case Streaming(bytes: Stream[IO, Byte])

final class EndpointResult private[framework] (
    private[framework] val key: ResponseKey[?],
    private[framework] val payload: Option[ResponseBody]
)

private[framework] final class RaisedResponse(val result: EndpointResult) extends RuntimeException(null, null, false, false)

class Output[A] private[framework] (key: ResponseKey[A], encode: A => Option[ResponseBody]):
  def apply(value: A): EndpointResult = new EndpointResult(key, encode(value))
  def raise(value: A): IO[Nothing] = IO.defer(IO.raiseError(new RaisedResponse(apply(value))))

final class OutputDeclaration[A] private[framework] (
    key: ResponseKey[A],
    encode: A => Option[ResponseBody],
    config: ResponseScope
) extends Output[A](key, encode):
  private[framework] def configure(init: ResponseScope ?=> Unit): Output[A] =
    config.gate.check()
    init(using config)
    this

final case class FieldError(location: String, name: String, message: String) derives JsonModel
final case class ErrorBody(code: String, message: String, errors: List[FieldError] = Nil) derives JsonModel
