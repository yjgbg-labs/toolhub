package sprout.cli

import io.circe.{Json, JsonObject}
import scala.collection.mutable

/** Command discovery and invocation depend only on the live OpenAPI contract. */
object CommandLine:
  enum Result:
    case Text(value: String)
    case Shell(script: String)

  private val reserved = Set("body", "body-text", "input", "help")
  private final class Node:
    val children = mutable.LinkedHashMap.empty[String, Node]
    var operation: Option[Operation] = None
    var description: String = ""

  def run(program: String, args: Vector[String], send: HttpRequest => HttpResponse, readText: String => String, cwd: String): Result =
    // Consume option values before recognizing help: --name -h is a literal value.
    val rest = Vector.newBuilder[String]
    var showHelp = false
    var index = 0
    while index < args.size do
      val arg = args(index)
      if arg == "--help" || arg == "-h" then
        showHelp = true
        index += 1
      else if arg.startsWith("--") then
        val (_, _, consumed) = takeOption(args, index)
        rest ++= args.slice(index, index + consumed)
        index += consumed
      else
        rest += arg
        index += 1

    val catalog = new Catalog(Catalog.parse(successful(send(HttpRequest("GET", "/openapi.json"))).body))
    val root = new Node
    catalog.operations.foreach { operation =>
      var node = root
      var path = Vector.empty[String]
      operation.segments.foreach { segment =>
        path :+= segment
        node = node.children.getOrElseUpdate(segment, new Node)
        node.description = catalog.descriptions.getOrElse(path.mkString("."), "")
      }
      node.operation = Some(operation)
    }
    val remaining = rest.result()
    var node = root
    var path = Vector.empty[String]
    var offset = 0
    while offset < remaining.size && !remaining(offset).startsWith("--") &&
        (node.operation.isEmpty || node.children.contains(remaining(offset))) do
      val segment = remaining(offset)
      val next = node.children.getOrElse(segment, throw new IllegalArgumentException(s"Unknown command: ${(path :+ segment).mkString(" ")}\n${help(node, path, program)}"))
      node = next
      path :+= segment
      offset += 1
    if showHelp || node.operation.isEmpty then
      require(showHelp || remaining.size == offset, "Choose an operation before passing parameters")
      Result.Text(help(node, path, program))
    else
      val operation = node.operation.get
      val request = try catalog.request(operation, input(catalog, operation, remaining.drop(offset), readText, cwd))
      catch
        case error: IllegalArgumentException =>
          throw new IllegalArgumentException(s"${error.getMessage}\n${usage(node, path, program)}\nRun '$program ${path.mkString(" ")} --help' for details.")
      render(successful(send(request.copy(allowStreaming = true))))

  private def successful(response: HttpResponse): HttpResponse =
    if response.status < 200 || response.status >= 300 then
      throw new RuntimeException(s"HTTP ${response.status}: ${response.body}")
    response

  private def render(response: HttpResponse): Result =
    if response.mediaType.contains("application/x-sh") then Result.Shell(response.body)
    else Result.Text {
      if response.body.isEmpty then ""
      else if response.mediaType.exists(t => t == "application/json" || t.endsWith("+json")) then
        val json = Catalog.parse(response.body)
        if json.isNull then "" else json.asString.getOrElse(json.spaces2)
      else response.body
    }

  private def flagName(operation: Operation, param: Parameter): String =
    if reserved(param.name) || operation.parameters.count(_.name == param.name) > 1 then s"${param.location}.${param.name}"
    else param.name

  private def usage(node: Node, path: Vector[String], program: String): String =
    val positional = node.operation.toVector.flatMap(_.parameters.filter(p => p.location == "path" && p.name != "cwd").map(p => s"<${p.name}>"))
    val suffix = if node.children.nonEmpty then Vector("<command>") else positional
    s"Usage: ${(Vector(program) ++ path ++ suffix).mkString(" ")} [options]"

  private def help(node: Node, path: Vector[String], program: String): String =
    val lines = mutable.ArrayBuffer(usage(node, path, program))
    if node.description.nonEmpty then lines ++= Vector("", node.description)
    node.operation.foreach { operation =>
      lines ++= Vector("", operation.description, s"${operation.method} ${operation.path}", "", "Parameters (location-qualified flags are always available):")
      operation.parameters.filterNot(_.name == "cwd").foreach { param =>
        val default = Catalog.field(param.schema, "default").fold("")(v => s"; default ${v.noSpaces}")
        val description = if param.description.isEmpty then "" else s"; ${param.description}"
        lines += s"  --${flagName(operation, param)} <${Catalog.types(param.schema).mkString("|")}>  ${param.location}; ${if param.required then "required" else "optional"}$default$description"
      }
      operation.body.foreach { body =>
        lines += s"  --body <json|@file|->  JSON body; ${if body.required then "required" else "optional"}"
        lines += "  --body-text <text|@file|->  Text encoded as a JSON string body"
      }
      lines += "  --input <json|@file|->  Complete {path, query, header, cookie, body} input"
    }
    if node.children.nonEmpty then
      lines ++= Vector("", "Commands:")
      node.children.foreach { (name, child) =>
        val description = if child.description.nonEmpty then child.description else child.operation.fold("")(_.description)
        lines += s"  $name  ${description.takeWhile(_ != '\n')}".stripTrailing()
      }
    lines ++= Vector("", "  --help, -h  Show help")
    lines.mkString("\n")

  private def takeOption(args: Vector[String], index: Int): (String, String, Int) =
    val arg = args(index).drop(2)
    val equals = arg.indexOf('=')
    if equals >= 0 then (arg.take(equals), arg.drop(equals + 1), 1)
    else
      require(index + 1 < args.size && !args(index + 1).startsWith("--"), s"Missing value for --$arg")
      (arg, args(index + 1), 2)

  private def scalar(raw: String, schema: Json): Json =
    Catalog.types(schema).find(_ != "null") match
      case Some("string") => Json.fromString(raw)
      case Some("boolean") =>
        require(raw == "true" || raw == "false", s"Expected true or false, got $raw")
        Json.fromBoolean(raw == "true")
      case Some("integer" | "number") =>
        val value = Catalog.parse(raw)
        require(value.isNumber, s"Expected number, got $raw")
        value
      case _ => throw new IllegalArgumentException("Unsupported flag type; use --input")

  private def input(catalog: Catalog, operation: Operation, args: Vector[String], readText: String => String, cwd: String): Json =
    val (implicitParams, explicitParams) = operation.parameters.partition(_.name == "cwd")
    val flags = explicitParams.map(p => flagName(operation, p) -> p).toMap ++
      explicitParams.map(p => s"${p.location}.${p.name}" -> p).toMap
    val positional = explicitParams.filter(_.location == "path")
    var position = 0
    var values = JsonObject.empty
    var index = 0
    def put(param: Parameter, value: Json, append: Boolean = false): Unit =
      val group = values(param.location).fold(JsonObject.empty)(Catalog.obj(_, param.location))
      require(append || !group.contains(param.name), s"Duplicate --${param.location}.${param.name}")
      val next = if append then Json.fromValues(group(param.name).toVector.flatMap(_.asArray.toVector.flatten) ++ value.asArray.get) else value
      values = values.add(param.location, Json.fromJsonObject(group.add(param.name, next)))
    while index < args.size do
      if !args(index).startsWith("--") then
        require(position < positional.size, s"Unexpected argument: ${args(index)}")
        val param = positional(position)
        put(param, scalar(args(index), param.schema))
        position += 1
        index += 1
      else
        val (name, raw, consumed) = takeOption(args, index)
        index += consumed
        name match
          case "input" =>
            require(values.isEmpty && index == args.size, "--input must be used alone")
            values = Catalog.obj(Catalog.parse(readText(raw)), "--input")
          case "body" | "body-text" =>
            require(operation.body.nonEmpty, s"${operation.id} has no body")
            require(!values.contains("body"), "Duplicate --body")
            values = values.add("body", if name == "body-text" then Json.fromString(readText(raw)) else Catalog.parse(readText(raw)))
          case _ =>
            require(!implicitParams.exists(p => name == p.name || name == s"${p.location}.${p.name}"), "cwd is supplied automatically by the CLI")
            val param = flags.getOrElse(name, throw new IllegalArgumentException(s"Unknown option --$name for ${operation.id}"))
            if Catalog.types(param.schema).contains("array") then
              val itemSchema = catalog.resolve(Catalog.field(param.schema, "items").get)
              val items = if raw.startsWith("[") then Catalog.parse(raw) else Json.arr(scalar(raw, itemSchema))
              require(items.isArray, s"Expected array for --$name")
              put(param, items, append = true)
            else put(param, scalar(raw, param.schema))
    implicitParams.foreach { param =>
      require(values(param.location).flatMap(_.asObject).forall(!_.contains(param.name)), "cwd is supplied automatically by the CLI")
      put(param, Json.fromString(cwd))
    }
    Json.fromJsonObject(values)
