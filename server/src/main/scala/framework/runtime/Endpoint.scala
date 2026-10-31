package framework.runtime

import cats.effect.IO
import framework.Client
import framework.http.InputLocation
import framework.model.SchemaNode
import org.http4s.HttpApp

/** A frozen endpoint declaration with an HTTP entry point bound to this endpoint alone. */
final class Endpoint private[framework] (
    val operationId: String,
    val method: String,
    val path: String,
    val inputs: Vector[Endpoint.Input],
    val exposedOn: Set[Client],
    val httpApp: HttpApp[IO]
)

object Endpoint:
  final case class Input(
      name: String,
      location: InputLocation,
      schema: SchemaNode,
      bodyMediaType: Option[String]
  )
