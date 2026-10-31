package groot.graaljs

import java.util.Map as JavaMap
import org.graalvm.polyglot.{Context, PolyglotException, Value}
import org.graalvm.polyglot.proxy.{ProxyArray, ProxyExecutable, ProxyObject}
import scala.util.control.NonFatal

/** Builds the SDK in Scala; guest built-ins supply native Promises, JSON values and Errors. */
private[graaljs] final class GraalSdkBindings(
    context: Context,
    commands: Vector[String],
    submit: (String, String, Value, Value) => Unit
):
  private val global = context.getBindings("js").getMember("globalThis")
  private val promise = global.getMember("Promise")
  private val error = global.getMember("Error")
  private val json = global.getMember("JSON")
  private val stringify = json.getMember("stringify")
  private val parse = json.getMember("parse")

  private class Namespace(children: Map[String, ProxyObject]) extends ProxyObject:
    def getMember(name: String): AnyRef = children.getOrElse(name, null)
    def hasMember(name: String): Boolean = children.contains(name)
    def getMemberKeys(): AnyRef = ProxyArray.fromArray(children.keys.toVector.sorted.map(name => name: AnyRef)*)
    def putMember(name: String, value: Value): Unit = throw new UnsupportedOperationException("Command tree is read-only")
    override def removeMember(name: String): Boolean = throw new UnsupportedOperationException("Command tree is read-only")

  private class Command(id: String, children: Map[String, ProxyObject]) extends Namespace(children) with ProxyExecutable:
    def execute(arguments: Value*): AnyRef = promise.newInstance(GraalSdkSession.callback { callbacks =>
      val resolve = callbacks(0)
      val reject = callbacks(1)
      try
        val input = arguments.headOption.fold("") { argument =>
          val encoded = stringify.execute(argument)
          // JSON.stringify(undefined) yields undefined, while JSON.stringify(null) yields the string "null".
          if encoded.isNull && argument.isNull then ""
          else
            require(encoded.isString, "Command input must be JSON serializable")
            encoded.asString()
        }
        // ISOLATED host access scopes callback arguments; keep them until the queued reply is delivered.
        resolve.pin()
        reject.pin()
        submit(id, input, resolve, reject)
      catch
        case cause: PolyglotException if cause.isGuestException && !cause.isResourceExhausted =>
          reject.execute(cause.getGuestObject)
        case cause: PolyglotException => throw cause
        case NonFatal(cause) => reject.execute(error.newInstance(message(cause)))
      null
    })

  private def tree(prefix: Vector[String], paths: Vector[Vector[String]]): ProxyObject =
    val children = paths.filter(_.size > prefix.size).groupBy(_(prefix.size)).map { (name, descendants) =>
      name -> tree(prefix :+ name, descendants)
    }
    if paths.exists(_.size == prefix.size) then new Command(prefix.mkString("."), children)
    else new Namespace(children)

  def install(): Unit =
    val descriptor = ProxyObject.fromMap(JavaMap.of[String, Object](
      "value", tree(Vector.empty, commands.map(_.split("\\.").toVector)),
      "enumerable", java.lang.Boolean.TRUE,
      "writable", java.lang.Boolean.FALSE,
      "configurable", java.lang.Boolean.FALSE))
    global.getMember("Object").getMember("defineProperty").execute(global, "tools", descriptor)
    ()

  /** Called on the Context's execution thread, never from an IO completion callback. */
  def deliver(id: String, resolve: Value, reject: Value, result: Either[Throwable, String]): Unit =
    result match
      case Left(cause) => reject.execute(error.newInstance(message(cause)))
      case Right(text) =>
        val response = parse.execute(text)
        val status = response.getMember("status").asInt()
        if status >= 200 && status < 300 then resolve.execute(response)
        else
          val body = stringify.execute(response.getMember("body")).asString()
          val failure = error.newInstance(s"$id: HTTP $status\n$body")
          failure.putMember("name", "ApiError")
          failure.putMember("operationId", id)
          failure.putMember("response", response)
          reject.execute(failure)

  private def message(cause: Throwable): String =
    Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName).take(8192)
