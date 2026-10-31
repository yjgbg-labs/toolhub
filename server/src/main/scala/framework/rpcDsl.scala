package framework

import cats.effect.IO
import framework.http.{AppScope, DescriptionScope, EndpointScope, ResponseScope, RoutesScope}
import framework.rpc.{Command, CommandGroup, CommandScope, CommandsScope, Metadata}
import framework.runtime.Application

object rpcDsl:
  export framework.Client
  export framework.Client.{cli, codemode, graaljs}
  export framework.model.JsonModel
  export framework.rpc.{PlainText, Shell, SSE, SuccessResponse}
  export framework.http.{ParamCodec, RequestContext, EndpointResult, Handler, Input, Output}
  export httpDsl.{server, host, port, maxBodyBytes, openapi, endpoint, info, title, version,
    default, constraint, minimum, maximum, minLength, maxLength, pattern, value}

  private def group(scope: CommandsScope): CommandGroup = scope match
    case group: CommandGroup => group
    case _ => throw new IllegalArgumentException("Declare commands inside httpApp or a commands block")

  def httpApp(init: (AppScope, CommandsScope) ?=> Unit): Application =
    httpDsl.httpApp {
      val app = summon[AppScope]
      val metadata = new Metadata
      httpDsl.routes {
        init(using app, new CommandGroup(summon[RoutesScope], Vector.empty, Vector.empty, metadata))
      }
      app.extensions("x-rpc") = () => metadata.freeze()
    }

  def commands(name: String)(init: CommandsScope ?=> Unit)(using scope: CommandsScope): Unit =
    require(name.matches("[A-Za-z][A-Za-z0-9_-]*"), s"Invalid commands name: $name")
    val parent = group(scope)
    val names = parent.namespace :+ name
    val child = new CommandGroup(parent.routes, names, parent.ancestors :+ parent.http, parent.metadata)
    init(using child)

  def command[A](name: String)(init: CommandScope[A] ?=> Unit)(using scope: CommandsScope, success: SuccessResponse[A]): Unit =
    require(name.matches("[A-Za-z][A-Za-z0-9_-]*"), s"Invalid command name: $name")
    val parent = group(scope)
    val names = parent.namespace :+ name
    val ancestors = parent.ancestors :+ parent.http
    // Choose the method when the HTTP declaration freezes, including late inherited bodies.
    httpDsl.register(http => if (ancestors :+ http).exists(_.hasBody) then "POST" else "GET", names.mkString("/", "/", ""), ancestors) {
      val http = summon[EndpointScope]
      httpDsl.operationId(names.mkString("."))
      init(using new Command(http, success.declare(http)))
    }(using parent.routes)

  def param[A](using codec: ParamCodec[A], scope: CommandsScope)(name: String, description: String): Input[A] =
    httpDsl.query[A](using codec, scope.http)(name) { httpDsl.description(description) }

  object body:
    def apply[A](using model: JsonModel[A], scope: CommandsScope)(description: String): Input[A] =
      httpDsl.body.json[A](using model, scope.http).apply { httpDsl.description(description) }

    def text(description: String)(using scope: CommandsScope): Input[String] =
      httpDsl.body.text(using scope.http).apply { httpDsl.description(description) }

  def description(value: String)(using scope: CommandsScope | DescriptionScope): Unit = scope match
    case commands: CommandsScope => httpDsl.description(value)(using commands.http)
    case description: DescriptionScope => httpDsl.description(value)(using description)

  /** Ancestor declarations are upper bounds; a child may further narrow exposure. */
  def exposeOn(first: Client, rest: Client*)(using scope: CommandsScope): Unit =
    scope.http.gate.check()
    require(scope.http.exposureValue.isEmpty, "Duplicate exposeOn declaration")
    val clients = (first +: rest).toSet
    require(clients.subsetOf(Client.all), "Invalid exposure client")
    scope.http.exposureValue = Some(clients)

  object response:
    def json[A](status: Int)(init: ResponseScope ?=> Unit)(using model: JsonModel[A], scope: CommandsScope): Output[A] =
      httpDsl.response.json[A](status)(init)(using model, scope.http)

    def text(status: Int)(init: ResponseScope ?=> Unit)(using scope: CommandsScope): Output[String] =
      httpDsl.response.text(status)(init)(using scope.http)

    def empty(status: Int)(init: ResponseScope ?=> Unit)(using scope: CommandsScope): Output[Unit] =
      httpDsl.response.empty(status)(init)(using scope.http)

  def middleware(wrap: Handler => Handler)(using scope: CommandsScope): Unit =
    httpDsl.middleware(wrap)(using scope.http)

  def recover(handler: RequestContext ?=> PartialFunction[Throwable, EndpointResult])(using scope: CommandsScope): Unit =
    middleware { next => next.recover(handler) }

  def handle[A](using scope: CommandScope[A])(logic: RequestContext ?=> IO[A]): Unit =
    httpDsl.handle(using scope.http) { logic.map(value => scope.success(value)) }
