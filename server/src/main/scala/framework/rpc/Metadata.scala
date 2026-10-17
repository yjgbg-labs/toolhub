package framework.rpc

import io.circe.Json
import scala.collection.mutable.ArrayBuffer

private[framework] final class Metadata:
  val commands = ArrayBuffer.empty[CommandGroup]

  def freeze(): Json =
    val grouped = commands.toVector.groupBy(_.namespace).toVector.sortBy(_._1.mkString("."))
    Json.obj(
      "commands" -> Json.fromValues(grouped.map { (path, declarations) =>
        val descriptions = declarations.flatMap(_.descriptionValue).distinct
        require(descriptions.size <= 1, s"Conflicting descriptions for RPC group: ${path.mkString(".")}")
        Json.fromFields(Vector("name" -> Json.fromString(path.mkString("."))) ++ descriptions.headOption.map(value => "description" -> Json.fromString(value)))
      })
    )
