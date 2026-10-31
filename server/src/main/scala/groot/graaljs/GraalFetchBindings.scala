package groot.graaljs

import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Base64, Map as JavaMap}
import framework.model.JsonModel
import groot.fetch.{BodyEncoding, FetchFailure, FetchRequest, FetchResponse, FetchService}
import org.graalvm.polyglot.{Context, PolyglotException, Value}
import org.graalvm.polyglot.proxy.{ProxyArray, ProxyObject}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Code Mode-shaped adapter over the declared tools.fetch command; owns no transport or IO. */
private[graaljs] final class GraalFetchBindings(context: Context):
  private val global = context.getBindings("js").getMember("globalThis")
  private val promise = global.getMember("Promise")
  private val string = global.getMember("String")
  private val array = global.getMember("Array")
  private val uint8 = global.getMember("Uint8Array")
  private val json = global.getMember("JSON")
  private val obj = global.getMember("Object")
  private val command = global.getMember("tools").getMember("fetch")
  private def callback(run: Seq[Value] => AnyRef) = GraalSdkSession.callback(run)
  private def text(value: Value): String = string.execute(value).asString()
  private def member(value: Value, name: String): Option[Value] =
    Option(value).filterNot(_.isNull).flatMap(v => Option(v.getMember(name))).filterNot(_.isNull)
  private def fail(message: String): Nothing = throw FetchFailure("TypeError", message)

  private def rejectWith(reject: Value, cause: Throwable): Unit = cause match
    case error: PolyglotException if error.isGuestException && !error.isResourceExhausted => reject.execute(error.getGuestObject); ()
    case error: PolyglotException => throw error
    case _ =>
      val name = cause match
        case error: FetchFailure => error.name
        case _: IllegalArgumentException => "TypeError"
        case _ => "Error"
      val error = global.getMember(if name == "TimeoutError" then "Error" else name)
        .newInstance(Option(cause.getMessage).getOrElse(name).take(8192))
      error.putMember("name", name)
      reject.execute(error)
      ()

  private def async(run: (Value, Value) => Unit): Value = promise.newInstance(callback { callbacks =>
    try run(callbacks(0), callbacks(1))
    catch case NonFatal(error) => rejectWith(callbacks(1), error)
    null
  })

  private def readHeaders(value: Value): Vector[(String, String)] =
    if value == null || value.isNull then Vector.empty
    else
      val pairs = if value.hasArrayElements then value
        else if member(value, "entries").exists(_.canExecute) then array.invokeMember("from", value.invokeMember("entries"))
        else if value.hasMembers then obj.invokeMember("entries", value)
        else fail("fetch() headers must be a record or [name, value] pairs")
      if pairs.getArraySize > 1024 then fail("fetch() accepts at most 1024 headers")
      val headers = (0L until pairs.getArraySize).map { index =>
        val pair = pairs.getArrayElement(index)
        if !pair.hasArrayElements || pair.getArraySize != 2 then fail("fetch() headers require [name, value] pairs")
        val name = text(pair.getArrayElement(0)).toLowerCase(java.util.Locale.ROOT)
        val value = text(pair.getArrayElement(1)).dropWhile(c => c == ' ' || c == '\t' || c == '\r' || c == '\n')
          .reverse.dropWhile(c => c == ' ' || c == '\t' || c == '\r' || c == '\n').reverse
        if !name.matches("[!#$%&'*+.^_`|~0-9a-z-]+") || value.exists(c => c == 0 || c == '\r' || c == '\n' || c > 255) then
          fail("fetch() has an invalid header name or value")
        name -> value
      }.toVector
      headers.groupMap(_._1)(_._2).toVector.sortBy(_._1).map { (name, pairs) => name -> pairs.mkString(", ") }

  private def input(arguments: Seq[Value]): FetchRequest =
    if arguments.isEmpty then fail("fetch() requires a URL")
    val url = text(arguments.head)
    val init = arguments.lift(1).orNull
    if init != null && !init.isNull && !init.hasMembers then fail("fetch() init must be an object")
    val rawMethod = member(init, "method").fold("GET")(text)
    val upper = rawMethod.toUpperCase(java.util.Locale.ROOT)
    val method = if Set("DELETE", "GET", "HEAD", "OPTIONS", "POST", "PUT").contains(upper) then upper else rawMethod
    val headers = readHeaders(member(init, "headers").orNull)
    var contentType: Option[String] = None
    var encoding = BodyEncoding.utf8
    val body = member(init, "body").map { value =>
      val tag = obj.getMember("prototype").getMember("toString").invokeMember("call", value).asString()
      if tag == "[object Uint8Array]" && value.hasArrayElements then
        if value.getArraySize > FetchService.maxBytes then throw FetchFailure("RangeError", "fetch() request exceeds 1 MiB")
        encoding = BodyEncoding.base64
        Base64.getEncoder.encodeToString(Array.tabulate(value.getArraySize.toInt)(index => value.getArrayElement(index.toLong).asInt().toByte))
      else
        contentType = Some(if tag == "[object URLSearchParams]" then "application/x-www-form-urlencoded;charset=UTF-8" else "text/plain;charset=UTF-8")
        text(value)
    }
    val complete = if headers.exists(_._1 == "content-type") then headers else headers ++ contentType.map("content-type" -> _)
    val request = FetchRequest(url, method, complete.toMap, body, encoding)
    if JsonModel[FetchRequest].encoder(request).noSpaces.getBytes(UTF_8).length > FetchService.maxBytes then
      throw FetchFailure("RangeError", "fetch() request exceeds the 1 MiB SDK limit")
    request

  def install(): Unit =
    val fetch = callback { arguments => async { (resolve, reject) =>
      val request = input(arguments)
      resolve.pin()
      reject.pin()
      val argumentsJson = io.circe.Json.obj("body" -> JsonModel[FetchRequest].encoder(request))
      val pending = command.execute(json.invokeMember("parse", argumentsJson.noSpaces))
      pending.invokeMember("then", callback { values =>
        try
          val body = json.invokeMember("stringify", values(0).getMember("body")).asString()
          val response = io.circe.parser.parse(body).flatMap(JsonModel[FetchResponse].decoder.decodeJson).fold(throw _, identity)
          deliver(resolve, response)
        catch case NonFatal(error) => rejectWith(reject, error)
        null
      }, callback { values =>
        val error = values(0)
        val response = member(error, "response")
        val body = response.flatMap(v => member(v, "body"))
        val message = body.flatMap(v => member(v, "message")).orElse(member(error, "message")).fold("fetch failed")(text)
        val name = body.flatMap(v => member(v, "name")).fold(if message.contains("1 MiB") then "RangeError" else "TypeError")(text)
        rejectWith(reject, FetchFailure(name, message))
        null
      })
      ()
    } }
    val descriptor = ProxyObject.fromMap(JavaMap.of[String, Object](
      "value", fetch, "enumerable", java.lang.Boolean.TRUE,
      "writable", java.lang.Boolean.FALSE, "configurable", java.lang.Boolean.FALSE))
    obj.invokeMember("defineProperty", global, "fetch", descriptor)
    ()

  /** Only invoked by the task's context thread. Body methods can be called repeatedly, like Code Mode. */
  private def deliver(resolve: Value, response: FetchResponse): Unit =
      def objectOf(fields: (String, AnyRef)*): ProxyObject = ProxyObject.fromMap(java.util.Collections.unmodifiableMap(fields.toMap.asJava))
      def nativeJson(value: io.circe.Json): Value = json.invokeMember("parse", value.noSpaces)
      val body = Base64.getDecoder.decode(response.bodyBase64)
      def decoded(): String = new String(body, UTF_8).stripPrefix("\ufeff")
      val headers = response.headers
      val headerObject = objectOf(
        "get" -> callback(args => headers.getOrElse(args.headOption.fold("undefined")(text).toLowerCase(java.util.Locale.ROOT), null)),
        "has" -> callback(args => java.lang.Boolean.valueOf(headers.contains(args.headOption.fold("undefined")(text).toLowerCase(java.util.Locale.ROOT)))),
        "entries" -> callback(_ => nativeJson(io.circe.Json.fromValues(response.headers.toVector.sortBy(_._1).map { (k, v) =>
          io.circe.Json.arr(io.circe.Json.fromString(k), io.circe.Json.fromString(v))
        }))))
      val value = objectOf(
        "url" -> response.url, "status" -> java.lang.Integer.valueOf(response.status), "statusText" -> response.statusText,
        "ok" -> java.lang.Boolean.valueOf(response.ok),
        "redirected" -> java.lang.Boolean.valueOf(response.redirected), "headers" -> headerObject,
        "text" -> callback(_ => async((resolve, _) => { resolve.execute(decoded()); () })),
        "json" -> callback(_ => async((resolve, _) => { resolve.execute(json.invokeMember("parse", decoded())); () })),
        "bytes" -> callback(_ => async { (resolve, _) =>
          val bytes = if uint8.hasMember("fromBase64") then uint8.invokeMember("fromBase64", response.bodyBase64)
            else uint8.newInstance(ProxyArray.fromArray(body.map(b => java.lang.Integer.valueOf(b & 0xff): AnyRef)*))
          resolve.execute(bytes)
          ()
        }))
      resolve.execute(value)
      ()
