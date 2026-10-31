package framework.runtime

import cats.effect.IO
import framework.http.InputLocation
import framework.model.SchemaNode
import org.http4s.HttpApp

/** A read-only view of the declarations and HTTP dispatcher available to one client. */
final class InProcessApi private[framework] (
    val endpoints: Vector[InProcessApi.Endpoint],
    val httpApp: HttpApp[IO]
)

object InProcessApi:
  final case class Input(
      name: String,
      location: InputLocation,
      schema: SchemaNode,
      bodyMediaType: Option[String]
  )

  final case class Endpoint(
      operationId: String,
      method: String,
      path: String,
      inputs: Vector[Input]
  )
