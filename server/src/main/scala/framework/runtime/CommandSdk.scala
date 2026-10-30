package framework.runtime

import cats.effect.IO
import framework.http.*
import framework.model.SchemaNode
import io.circe.{Json, JsonObject}
import java.nio.charset.StandardCharsets.UTF_8
import org.http4s.{Header, HttpApp, Method, Query, Request, Uri}
import org.typelevel.ci.CIString

/** An in-process client of the frozen command declarations, using the same HTTP validation and middleware. */
final class CommandSdk private[framework] (
    endpoints: Vector[BoundEndpoint],
    httpApp: HttpApp[IO]
):
  private val byId = endpoints.map(endpoint => endpoint.spec.operationId -> endpoint.spec).toMap

  val commands: Vector[String] = byId.keys.toVector.sorted.map { id =>
    require(id.matches("[A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z][A-Za-z0-9_-]*)*"), s"Invalid SDK operationId: $id")
    id
  }

  def call(id: String, input: Json): IO[Json] = IO.defer {
    val spec = byId.getOrElse(id, throw new IllegalArgumentException(s"Unknown command: $id"))
    val args = input.asObject.getOrElse(throw new IllegalArgumentException("Command input must be an object"))
    val locations = spec.inputs.map(_.location.toString.toLowerCase).toSet
    require(args.keys.forall(locations), s"Unknown input group for $id")
    val groups = Vector(InputLocation.Path, InputLocation.Query, InputLocation.Header, InputLocation.Cookie).map { location =>
      val name = location.toString.toLowerCase
      val group = args(name).map(_.asObject.getOrElse(throw new IllegalArgumentException(s"$name must be an object"))).getOrElse(JsonObject.empty)
      val declared = spec.inputs.filter(_.location == location)
      require(group.keys.forall(key => declared.exists(_.name == key)), s"Unknown $name parameter for $id")
      declared.foreach { parameter =>
        group(parameter.name).foreach { value =>
          require(matches(parameter.schema.node(), value), s"Invalid type for $name.${parameter.name}")
        }
      }
      location -> group
    }.toMap
    def scalar(value: Json): String = value.asString.getOrElse(value.noSpaces)
    val path = spec.path.segments.map { segment =>
      if segment.startsWith("{") then
        val name = segment.drop(1).dropRight(1)
        val value = scalar(groups(InputLocation.Path)(name).getOrElse(throw new IllegalArgumentException(s"Missing path.$name")))
        require(value.nonEmpty && value != "." && value != "..", s"Invalid path.$name")
        value
      else segment
    }
    val query = groups(InputLocation.Query).toVector.flatMap { (key, value) =>
      value.asArray.getOrElse(Vector(value)).map(item => key -> Some(scalar(item)))
    }
    val headers = groups(InputLocation.Header).toVector.map { (key, value) =>
      val text = scalar(value)
      require(!text.exists(c => c == '\r' || c == '\n'), s"Invalid header.$key")
      Header.Raw(CIString(key), text)
    }
    val cookies = groups(InputLocation.Cookie).toVector.map { (key, value) =>
      val text = scalar(value)
      require(key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") && text.matches("[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]*"), s"Invalid cookie.$key")
      s"$key=$text"
    }
    val uri = path.foldLeft(Uri(path = Uri.Path.Root))((uri, segment) => uri.addSegment(segment)).copy(query = Query.fromVector(query))
    val allHeaders = headers ++ Option.when(cookies.nonEmpty)(Header.Raw(CIString("Cookie"), cookies.mkString("; ")))
    val base = allHeaders.foldLeft(Request[IO](method = Method.fromString(spec.method).toOption.get, uri = uri)) {
      (request, header) => request.putHeaders(header)
    }
    val request = args("body").fold(base) { value =>
      val media = spec.inputs.find(_.location == InputLocation.Body).get.bodyMediaType.get
      val text = if media == "text/plain" then value.asString.getOrElse(throw new IllegalArgumentException("body must be a string")) else value.noSpaces
      base.withEntity(text).putHeaders(Header.Raw(CIString("Content-Type"), media))
    }
    httpApp(request).flatMap { response =>
      response.body.take(CommandSdk.maxBytes + 1L).compile.to(Array).flatMap { bytes => IO {
        require(bytes.length <= CommandSdk.maxBytes, "SDK response exceeds 1 MiB")
        val text = new String(bytes, UTF_8)
        val media = response.contentType.map(value => s"${value.mediaType.mainType}/${value.mediaType.subType}").getOrElse("")
        val body = if bytes.isEmpty then Json.Null
          else if media == "application/json" || media.endsWith("+json") then io.circe.parser.parse(text).fold(throw _, identity)
          else Json.fromString(text)
        Json.obj("status" -> Json.fromInt(response.status.code),
          "headers" -> Json.fromFields(response.headers.headers.map(header => header.name.toString.toLowerCase -> Json.fromString(header.value))),
          "body" -> body)
      } }
    }
  }

  private def matches(node: SchemaNode, value: Json): Boolean = node match
    case SchemaNode.Primitive("string", _) => value.isString
    case SchemaNode.Primitive("boolean", _) => value.isBoolean
    case SchemaNode.Primitive("integer", _) => value.asNumber.exists(_.toBigDecimal.exists(_.isWhole))
    case SchemaNode.Primitive("number", _) => value.isNumber
    case SchemaNode.Enumeration(_, _) => value.isString
    case SchemaNode.ArrayOf(element) => value.asArray.exists(_.forall(matches(element(), _)))
    case SchemaNode.Nullable(element) => value.isNull || matches(element(), value)
    case _ => false

object CommandSdk:
  val maxBytes = 1024 * 1024
  val empty: CommandSdk = new CommandSdk(Vector.empty, org.http4s.HttpApp.notFound[IO])
