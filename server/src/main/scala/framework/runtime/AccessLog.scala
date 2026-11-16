package framework.runtime

import cats.effect.{IO, Ref, Resource}
import cats.effect.kernel.Outcome
import cats.syntax.all.*
import framework.http.EndpointSpec
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID
import org.http4s.{Request, Response}

/** One JSON line per request, or an open/close pair for streaming responses. */
private[runtime] object AccessLog:
  private val maxValueBytes = 2048
  private val maxLineBytes = 8192

  private case class State(
      inputs: Vector[(String, Json)] = Vector.empty,
      streaming: Boolean = false,
      error: Option[String] = None
  )

  final class Context private[AccessLog] (state: Option[Ref[IO, State]]):
    def input(name: String, value: => Option[Json]): IO[Unit] =
      state.fold(IO.unit)(ref => IO.defer {
        value.fold(IO.unit)(json => ref.update(s => s.copy(inputs = s.inputs :+ (name -> bounded(json)))))
      })

    def streaming: IO[Unit] = state.fold(IO.unit)(_.update(_.copy(streaming = true)))
    def failed(error: Throwable): IO[Unit] =
      state.fold(IO.unit)(_.update(_.copy(error = Some(error.getClass.getName))))

  private val disabled = new Context(None)

  // A prefix always ends at a complete Unicode code point.
  private def shorten(text: String, count: Int): String =
    if text.length <= count then text
    else text.take(if Character.isHighSurrogate(text.charAt(count - 1)) then count - 1 else count) + "…"

  private def bounded(value: Json): Json =
    val encoded = value.noSpaces
    if encoded.getBytes(UTF_8).length <= maxValueBytes then value
    else Json.obj("truncated" -> Json.True, "preview" -> Json.fromString(shorten(encoded, 256)))

  private def write(fields: Vector[(String, Json)], inputs: Vector[(String, Json)]): IO[Unit] = IO.blocking {
    var kept = inputs
    def line: String = Json.fromFields(fields ++ Vector(
      "params" -> Json.fromFields(kept),
      "paramsTruncated" -> Json.fromBoolean(kept.size != inputs.size)
    )).noSpaces
    var rendered = line
    while rendered.getBytes(UTF_8).length > maxLineBytes && kept.nonEmpty do
      kept = kept.dropRight(1)
      rendered = line
    // PrintStream.println keeps concurrent access records on separate lines.
    System.out.println(rendered)
  }.handleError(_ => ())

  def apply(
      enabled: Boolean,
      request: Request[IO],
      endpoint: Option[EndpointSpec],
      source: String
  )(run: Context => IO[Response[IO]]): IO[Response[IO]] =
    if !enabled || endpoint.exists(_.logSkip) then run(disabled)
    else for
      started <- IO.monotonic
      id <- IO(UUID.randomUUID().toString)
      state <- Ref.of[IO, State](State())
      closed <- Ref.of[IO, Boolean](false)
      context = new Context(Some(state))
      response <- {
        def emit(event: String, status: Option[Int], outcome: String, error: Option[String] = None): IO[Unit] =
          (IO.realTime, IO.monotonic, state.get).tupled.flatMap { (now, end, snapshot) =>
            val fields = Vector(
              "type" -> Json.fromString("access"),
              "event" -> Json.fromString(event),
              "time" -> Json.fromString(Instant.ofEpochMilli(now.toMillis).toString),
              "requestId" -> Json.fromString(id),
              "source" -> Json.fromString(source),
              "command" -> endpoint.fold(Json.Null)(e => Json.fromString(shorten(e.operationId, 256))),
              "method" -> Json.fromString(shorten(request.method.name, 32)),
              "path" -> Json.fromString(shorten(endpoint.fold(request.uri.path.renderString)(_.path.value), 512)),
              "status" -> status.fold(Json.Null)(Json.fromInt),
              "durationMs" -> Json.fromLong((end - started).toMillis),
              "outcome" -> Json.fromString(outcome)
            ) ++ error.orElse(snapshot.error).map(e => "error" -> Json.fromString(shorten(e, 256)))
            write(fields, snapshot.inputs)
          }

        IO.uncancelable { poll =>
          poll(IO.defer(run(context))).guaranteeCase {
            case Outcome.Canceled() => emit("request", None, "canceled")
            case Outcome.Errored(error) => emit("request", None, "error", Some(error.getClass.getName))
            case _ => IO.unit
          }.flatMap { response =>
            state.get.flatMap { snapshot =>
              if snapshot.streaming && request.method.name != "HEAD" then
                emit("stream_open", Some(response.status.code), "open").as {
                  response.withBodyStream(response.body.onFinalizeCase { exit =>
                    closed.getAndSet(true).flatMap {
                      case true => IO.unit
                      case false =>
                        val (outcome, error) = exit match
                          case Resource.ExitCase.Succeeded => ("completed", None)
                          case Resource.ExitCase.Canceled => ("canceled", None)
                          case Resource.ExitCase.Errored(error) => ("error", Some(error.getClass.getName))
                        emit("stream_close", Some(response.status.code), outcome, error)
                    }
                  })
                }
              else emit("request", Some(response.status.code),
                if snapshot.error.nonEmpty || response.status.code >= 500 then "error" else "completed").as(response)
            }
          }
        }
      }
    yield response
