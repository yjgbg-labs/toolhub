package sprout

import cats.effect.IO

sealed trait CommandsScope:
  private[sprout] def http: DeclarationScope
  private[sprout] def namespace: Vector[String]
  private[sprout] def ancestors: Vector[DeclarationScope]

sealed trait CommandScope[A] extends CommandsScope:
  private[sprout] def http: EndpointScope
  private[sprout] def success: Output[A]

object rpcDsl:
  export sprout.{JsonModel, ParamCodec, RequestContext, EndpointResult, Handler, Input, Output}
  export httpDsl.{server, host, port, socket, maxBodyBytes, openapi, endpoint, info, title, version,
    default, constraint, minimum, maximum, minLength, maxLength, pattern, value}

  private final class Group(
      val http: RoutesScope,
      val namespace: Vector[String],
      val ancestors: Vector[DeclarationScope]
  ) extends CommandsScope

  private final class Command[A](
      val http: EndpointScope,
      val namespace: Vector[String],
      val ancestors: Vector[DeclarationScope],
      val success: Output[A]
  ) extends CommandScope[A]

  private def routes(scope: CommandsScope): RoutesScope = scope.http match
    case routes: RoutesScope => routes
    case _ => throw new IllegalArgumentException("Declare commands inside httpApp or a commands block")

  def httpApp(init: (AppScope, CommandsScope) ?=> Unit): Application =
    httpDsl.httpApp {
      httpDsl.routes {
        init(using summon[AppScope], new Group(summon[RoutesScope], Vector.empty, Vector.empty))
      }
    }

  def commands(name: String)(init: CommandsScope ?=> Unit)(using scope: CommandsScope): Unit =
    require(name.matches("[A-Za-z][A-Za-z0-9_-]*"), s"Invalid commands name: $name")
    val names = scope.namespace :+ name
    httpDsl.group(s"/$name") {
      val http = summon[RoutesScope]
      http.documentedTag = Some(names.mkString("."))
      httpDsl.tag(names.mkString("."))
      init(using new Group(http, names, scope.ancestors :+ scope.http))
    }(using routes(scope))

  def command[A](name: String)(init: CommandScope[A] ?=> Unit)(using scope: CommandsScope, success: SuccessResponse[A]): Unit =
    val segments = name.split("\\.", -1).toVector
    require(segments.forall(_.matches("[A-Za-z][A-Za-z0-9_-]*")), s"Invalid command name: $name")
    val names = scope.namespace ++ segments
    val ancestors = scope.ancestors :+ scope.http
    // Choose the method when the HTTP declaration freezes, including late inherited bodies.
    httpDsl.register(http => if (ancestors :+ http).exists(_.hasBody) then "POST" else "GET", "/" + segments.mkString("/")) {
      val http = summon[EndpointScope]
      httpDsl.operationId(names.mkString("."))
      init(using new Command(http, names, ancestors, success.declare(http)))
    }(using routes(scope))

  def param[A](using codec: ParamCodec[A], scope: CommandsScope)(name: String, description: String): Input[A] =
    httpDsl.query[A](using codec, scope.http)(name) { httpDsl.description(description) }

  def body[A](using model: JsonModel[A], scope: CommandsScope)(description: String): Input[A] =
    httpDsl.body.json[A](using model, scope.http).apply { httpDsl.description(description) }

  def description(value: String)(using scope: CommandsScope | DescriptionScope): Unit = scope match
    case commands: CommandsScope => httpDsl.description(value)(using commands.http)
    case description: DescriptionScope => httpDsl.description(value)(using description)

  def summary(value: String)(using scope: CommandScope[?]): Unit =
    httpDsl.summary(value)(using scope.http)

  def tag(value: String)(using scope: CommandsScope): Unit =
    httpDsl.tag(value)(using scope.http)

  object response:
    def json[A](status: Int)(init: ResponseScope ?=> Unit)(using model: JsonModel[A], scope: CommandsScope): Output[A] =
      httpDsl.response.json[A](status)(init)(using model, scope.http)

    def text(status: Int)(init: ResponseScope ?=> Unit)(using scope: CommandsScope): Output[String] =
      httpDsl.response.text(status)(init)(using scope.http)

    def empty(status: Int)(init: ResponseScope ?=> Unit)(using scope: CommandsScope): Output[Unit] =
      httpDsl.response.empty(status)(init)(using scope.http)

  def middleware(wrap: Handler => Handler)(using scope: CommandsScope): Unit =
    httpDsl.middleware(wrap)(using scope.http)

  def handle[A](using scope: CommandScope[A])(logic: RequestContext ?=> IO[A]): Unit =
    httpDsl.handle(using scope.http) { logic.map(value => scope.success(value)) }
