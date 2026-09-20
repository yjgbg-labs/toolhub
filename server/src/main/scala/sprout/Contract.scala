package sprout

import cats.effect.IO
import io.circe.Json

enum InputLocation:
  case Path, Query, Header, Cookie, Body

private[sprout] enum RawInput:
  case Values(values: Vector[String])
  case Body(value: Option[Json])

private[sprout] final case class SchemaConstraint(keyword: String, value: Json)

private[sprout] final case class Constraint[A](
    accepts: A => Boolean,
    message: String,
    schema: Option[SchemaConstraint] = None
)

final class Input[A] private[sprout] (
    private[sprout] val owner: Object,
    private[sprout] val slot: Int,
    private val config: InputScope[A]
):
  def apply(init: InputScope[A] ?=> Unit): Input[A] =
    config.gate.check()
    init(using config)
    this

final class RequestContext private[sprout] (owner: Object, values: Vector[Any]):
  def read[A](input: Input[A]): A =
    require(input.owner eq owner, "Input belongs to a different endpoint")
    values(input.slot).asInstanceOf[A]

final class EndpointResult private[sprout] (
    private[sprout] val owner: Object,
    private[sprout] val slot: Int,
    private[sprout] val payload: Option[String]
)

class Output[A] private[sprout] (owner: Object, slot: Int, encode: A => Option[String]):
  def apply(value: A): EndpointResult = new EndpointResult(owner, slot, encode(value))

final class OutputDeclaration[A] private[sprout] (
    owner: Object,
    slot: Int,
    encode: A => Option[String],
    config: ResponseScope
) extends Output[A](owner, slot, encode):
  private[sprout] def configure(init: ResponseScope ?=> Unit): Output[A] =
    config.gate.check()
    init(using config)
    this

trait HandlerResult[A]:
  def suspend(value: => A): IO[EndpointResult]

object HandlerResult:
  given HandlerResult[EndpointResult] with
    def suspend(value: => EndpointResult) = IO(value)

  given HandlerResult[IO[EndpointResult]] with
    def suspend(value: => IO[EndpointResult]) = IO.defer(value)

private[sprout] final case class InputSpec[A](
    handle: Input[A],
    name: String,
    location: InputLocation,
    schema: Schema[A],
    description: Option[String],
    required: Boolean,
    default: Option[Json],
    repeated: Boolean,
    constraints: Vector[Constraint[A]],
    decodeRaw: RawInput => Either[String, A]
):
  def decode(raw: RawInput): Either[FieldError, A] =
    decodeRaw(raw).flatMap { value =>
      constraints.find(rule => !rule.accepts(value)) match
        case Some(rule) => Left(rule.message)
        case None       => Right(value)
    }.left.map(message => FieldError(location.toString.toLowerCase, name, message))

private[sprout] final case class ResponseSpec(
    status: Int,
    description: String,
    mediaType: Option[String],
    schema: Option[Schema[?]]
)

private[sprout] final case class EndpointSpec(
    owner: Object,
    method: String,
    path: PathPattern,
    operationId: String,
    summary: Option[String],
    description: Option[String],
    tags: Vector[String],
    inputs: Vector[InputSpec[?]],
    outputs: Vector[ResponseSpec]
)

private[sprout] final case class BoundEndpoint(spec: EndpointSpec, run: RequestContext => IO[EndpointResult])

private[sprout] final case class OperationGroup(name: String, description: Option[String])

final case class FieldError(location: String, name: String, message: String) derives JsonModel
final case class ErrorBody(code: String, message: String, errors: List[FieldError] = Nil) derives JsonModel

private[sprout] final case class PathPattern(value: String, segments: Vector[String]):
  val parameters: Vector[String] = segments.filter(_.startsWith("{")).map(_.drop(1).dropRight(1))
  val shape: String = segments.map(s => if s.startsWith("{") then "{}" else s).mkString("/", "/", "")
  val priority: String = segments.map(s => if s.startsWith("{") then '0' else '1').mkString

  def matches(actual: Vector[String]): Option[Map[String, String]] =
    if actual.size != segments.size || actual.exists(_.isEmpty) then None
    else
      segments.zip(actual).foldLeft(Option(Map.empty[String, String])) { case (acc, (pattern, value)) =>
        acc.flatMap { params =>
          if pattern.startsWith("{") then Some(params.updated(pattern.drop(1).dropRight(1), value))
          else Option.when(pattern == value)(params)
        }
      }

private[sprout] object PathPattern:
  def normalize(value: String): String =
    require(value.startsWith("/"), s"Path must start with '/': $value")
    require(!value.contains("?") && !value.contains("#") && !value.contains("//"), s"Invalid path: $value")
    if value == "/" then value else value.stripSuffix("/")

  def parse(value: String): PathPattern =
    val normalized = normalize(value)
    val segments = if normalized == "/" then Vector.empty else normalized.drop(1).split("/", -1).toVector
    segments.foreach { segment =>
      require(!segment.exists(c => c == '{' || c == '}') || segment.matches("\\{[A-Za-z_][A-Za-z0-9_]*\\}"),
        s"Parameters must occupy a whole path segment: $segment")
    }
    val result = PathPattern(normalized, segments)
    require(result.parameters.distinct.size == result.parameters.size, s"Duplicate path parameter in $value")
    result

  def join(prefix: String, path: String): String =
    require(path.isEmpty || path.startsWith("/"), s"Path must start with '/': $path")
    normalize(Option(prefix.stripSuffix("/") + path).filter(_.nonEmpty).getOrElse("/"))
