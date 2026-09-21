package sprout

import cats.effect.IO
import io.circe.Json
import scala.util.matching.Regex

object dsl:
  export sprout.{JsonModel, ParamCodec, RequestContext, EndpointResult, Handler, Input, Output}

  def httpApp(init: AppScope ?=> Unit): Application =
    val app = new AppScope
    try
      init(using app)
      Application.build(Compiler.resolve(app.freeze()))
    finally app.gate.close()

  def server(init: ServerScope ?=> Unit)(using app: AppScope): Unit =
    app.gate.check()
    init(using app.serverScope)

  def port(value: Int)(using scope: ServerScope): Unit =
    scope.gate.check()
    require(value >= 0 && value <= 65535, "Invalid port")
    scope.portValue = value

  def host(value: String)(using scope: ServerScope): Unit =
    scope.gate.check()
    require(com.comcast.ip4s.Host.fromString(value).nonEmpty, "Invalid host")
    scope.hostValue = value

  def maxBodyBytes(value: Long)(using scope: ServerScope): Unit =
    scope.gate.check()
    scope.maxBodyBytesValue = value

  def openapi(init: OpenApiScope ?=> Unit)(using app: AppScope): Unit =
    app.gate.check()
    init(using app.openApiScope)

  def endpoint(path: String)(using scope: OpenApiScope): Unit =
    scope.gate.check()
    scope.endpointValue = path

  def info(init: InfoScope ?=> Unit)(using scope: OpenApiScope): Unit =
    scope.gate.check()
    init(using scope.infoScope)

  def title(value: String)(using scope: InfoScope): Unit =
    scope.gate.check()
    scope.titleValue = value

  def version(value: String)(using scope: InfoScope): Unit =
    scope.gate.check()
    scope.versionValue = value

  def description(value: String)(using scope: DescriptionScope): Unit =
    scope.gate.check()
    scope.descriptionValue = Some(value)

  def routes(init: RoutesScope ?=> Unit)(using app: AppScope): Unit =
    app.gate.check()
    val scope = new RoutesScope(app.gate, "")
    init(using scope)
    app.routes += scope

  def group(name: String)(init: RoutesScope ?=> Unit)(using scope: RoutesScope): Unit =
    scope.gate.check()
    val child = new RoutesScope(scope.gate, name)
    init(using child)
    scope.children += (() => child.freeze())

  def tag(value: String)(using scope: TagScope): Unit =
    scope.gate.check()
    scope.tags += value

  private def register(method: String, path: String)(init: EndpointScope[EndpointResult] ?=> Unit)(using scope: RoutesScope): Unit =
    scope.gate.check()
    val endpoint = new EndpointScope[EndpointResult](scope.gate, method, path)
    init(using endpoint)
    scope.children += (() => endpoint.freeze())

  def operation[A](id: String)(init: EndpointScope[A] ?=> Unit)(using scope: RoutesScope, success: SuccessResponse[A]): Unit =
    scope.gate.check()
    val endpoint = new EndpointScope[A](scope.gate, "POST", "", Some(id))
    endpoint.successValue = Some(success.declare(endpoint))
    init(using endpoint)
    scope.children += (() => endpoint.freeze())

  def get(path: String = "")(init: EndpointScope[EndpointResult] ?=> Unit)(using RoutesScope): Unit = register("GET", path)(init)
  def post(path: String)(init: EndpointScope[EndpointResult] ?=> Unit)(using RoutesScope): Unit = register("POST", path)(init)
  def put(path: String)(init: EndpointScope[EndpointResult] ?=> Unit)(using RoutesScope): Unit = register("PUT", path)(init)
  def patch(path: String)(init: EndpointScope[EndpointResult] ?=> Unit)(using RoutesScope): Unit = register("PATCH", path)(init)
  def delete(path: String)(init: EndpointScope[EndpointResult] ?=> Unit)(using RoutesScope): Unit = register("DELETE", path)(init)
  def head(path: String)(init: EndpointScope[EndpointResult] ?=> Unit)(using RoutesScope): Unit = register("HEAD", path)(init)
  def options(path: String)(init: EndpointScope[EndpointResult] ?=> Unit)(using RoutesScope): Unit = register("OPTIONS", path)(init)

  def operationId(value: String)(using scope: EndpointScope[?]): Unit =
    scope.gate.check()
    scope.operationIdValue = Some(value)

  def summary(value: String)(using scope: EndpointScope[?]): Unit =
    scope.gate.check()
    scope.summaryValue = Some(value)

  def path[A](using codec: ParamCodec[A], scope: DeclarationScope)(name: String): Input[A] = scope.parameter[A](name, InputLocation.Path)
  def query[A](using codec: ParamCodec[A], scope: DeclarationScope)(name: String): Input[A] = scope.parameter[A](name, InputLocation.Query)
  def header[A](using codec: ParamCodec[A], scope: DeclarationScope)(name: String): Input[A] = scope.parameter[A](name, InputLocation.Header)
  def cookie[A](using codec: ParamCodec[A], scope: DeclarationScope)(name: String): Input[A] = scope.parameter[A](name, InputLocation.Cookie)

  def default[A](value: A)(using scope: InputScope[A]): Unit =
    scope.gate.check()
    scope.defaultValue = Some(value)

  def constraint[A](message: String)(accepts: A => Boolean)(using scope: InputScope[A]): Unit =
    scope.gate.check()
    scope.constraints += Constraint(accepts, message)

  def minimum[A](value: A)(using scope: InputScope[A], number: Numeric[A]): Unit =
    scope.gate.check()
    scope.constraints += Constraint(v => number.gteq(v, value), s"Must be at least $value",
      Some(SchemaConstraint("minimum", Json.fromBigDecimal(BigDecimal(value.toString)))))

  def maximum[A](value: A)(using scope: InputScope[A], number: Numeric[A]): Unit =
    scope.gate.check()
    scope.constraints += Constraint(v => number.lteq(v, value), s"Must be at most $value",
      Some(SchemaConstraint("maximum", Json.fromBigDecimal(BigDecimal(value.toString)))))

  def minLength(value: Int)(using scope: InputScope[String]): Unit =
    scope.gate.check()
    require(value >= 0, "Negative minimum length")
    scope.constraints += Constraint(v => v.codePointCount(0, v.length) >= value, s"Must contain at least $value characters",
      Some(SchemaConstraint("minLength", Json.fromInt(value))))

  def maxLength(value: Int)(using scope: InputScope[String]): Unit =
    scope.gate.check()
    require(value >= 0, "Negative maximum length")
    scope.constraints += Constraint(v => v.codePointCount(0, v.length) <= value, s"Must contain at most $value characters",
      Some(SchemaConstraint("maxLength", Json.fromInt(value))))

  def pattern(value: String)(using scope: InputScope[String]): Unit =
    scope.gate.check()
    val regex = new Regex(value)
    scope.constraints += Constraint(v => regex.findFirstIn(v).nonEmpty, s"Must match $value",
      Some(SchemaConstraint("pattern", Json.fromString(value))))

  object body:
    def json[A: JsonModel](using scope: DeclarationScope): Input[A] = scope.jsonBody[A]
    def json[A: JsonModel](init: InputScope[A] ?=> Unit)(using scope: DeclarationScope): Input[A] = scope.jsonBody[A].apply(init)

  object response:
    def json[A](status: Int)(init: ResponseScope ?=> Unit)(using model: JsonModel[A], scope: DeclarationScope): Output[A] =
      scope.output[A](status, Some("application/json"), Some(model.schema), value => Some(model.encoder(value).noSpaces)).configure(init)

    def text(status: Int)(init: ResponseScope ?=> Unit)(using scope: DeclarationScope): Output[String] =
      scope.output[String](status, Some("text/plain"), Some(JsonModel[String].schema), value => Some(value)).configure(init)

    def empty(status: Int)(init: ResponseScope ?=> Unit)(using scope: DeclarationScope): Output[Unit] =
      scope.output[Unit](status, None, None, _ => None).configure(init)

  extension [A](input: Input[A])
    def value(using context: RequestContext): A = context.read(input)

  def middleware(wrap: Handler => Handler)(using scope: DeclarationScope): Unit =
    scope.gate.check()
    scope.middlewareValue += wrap

  def ok[A, B](using scope: EndpointScope[A])(value: B)(using conforms: B <:< A): EndpointResult =
    scope.successValue.getOrElse(throw new IllegalStateException("No automatic success response declared")).apply(conforms(value))

  def handle(logic: RequestContext ?=> IO[EndpointResult])(using scope: EndpointScope[?]): Unit =
    scope.setHandler(context => logic(using context))
