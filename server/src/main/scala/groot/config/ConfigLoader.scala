package groot.config

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.typesafe.config.{
  ConfigException,
  ConfigFactory,
  ConfigList,
  ConfigObject,
  ConfigParseOptions,
  ConfigResolveOptions,
  ConfigSyntax,
  ConfigValue,
  ConfigValueType
}
import framework.model.{JsonModel, SchemaNode}
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.circe.{Json, JsonObject}
import java.nio.file.{Files, NoSuchFileException, Path}
import java.nio.file.attribute.BasicFileAttributes
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

object ConfigLoader:
  final case class FileStamp(path: Path, exists: Boolean, lastModifiedNanos: Long, size: Long, fileKey: Option[AnyRef])

  /** Parse a HOCON string into `A` via Circe `JsonModel[A]` with schema normalization and unknown-field checking. */
  def parseHocon[A](
      rawText: String,
      default: => A,
      validate: A => Either[String, A] = (a: A) => Right(a),
      origin: String = "inline.conf"
  )(using model: JsonModel[A]): Either[String, A] =
    if rawText.trim.isEmpty then validate(default)
    else
      val parsedJson =
        try
          val opts = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF).setOriginDescription(origin)
          val cfg = ConfigFactory.parseString(rawText, opts).resolve(ConfigResolveOptions.defaults())
          Right(configValueToJson(cfg.root()))
        catch case e: ConfigException => Left(s"HOCON parse error: ${e.getMessage}")
      decodeJson(parsedJson, model.schema.node(), validate)

  private def parseHoconFile[A](
      path: Path,
      validate: A => Either[String, A]
  )(using model: JsonModel[A]): Either[String, A] =
    val parsedJson =
      try
        val opts = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF).setAllowMissing(false)
        val cfg = ConfigFactory.parseFile(path.toFile, opts).resolve(ConfigResolveOptions.defaults())
        Right(configValueToJson(cfg.root()))
      catch case e: ConfigException => Left(s"HOCON parse error: ${e.getMessage}")
    decodeJson(parsedJson, model.schema.node(), validate)

  private def decodeJson[A](
      parsedJson: Either[String, Json],
      schemaRoot: SchemaNode,
      validate: A => Either[String, A]
  )(using model: JsonModel[A]): Either[String, A] =
    for
      raw <- parsedJson
      normalized = normalizeForSchema(if raw.isNull then Json.obj() else raw, schemaRoot)
      _ <- checkUnknownKeys(normalized, schemaRoot, "")
      decoded <- model.decoder.decodeJson(normalized).left.map(e => s"Config decode error${formatCursor(e.history)}: ${e.message}")
      validated <- validate(decoded)
    yield validated

  /** Load HOCON into a `SignallingRef[IO, A]` and poll file metadata every `pollInterval` (default 500ms) for hot reload. */
  def resource[A](
      resolvePath: IO[Path],
      default: => A,
      validate: A => Either[String, A] = (a: A) => Right(a),
      pollInterval: FiniteDuration = 500.millis,
      onChange: (A, A) => IO[Unit] = (_: A, _: A) => IO.unit
  )(using model: JsonModel[A]): Resource[IO, SignallingRef[IO, A]] =
    def stat(path: Path): IO[FileStamp] = IO.blocking {
      try
        val attrs = Files.readAttributes(path, classOf[BasicFileAttributes])
        val inst = attrs.lastModifiedTime().toInstant
        val nanos = inst.getEpochSecond * 1_000_000_000L + inst.getNano.toLong
        FileStamp(path, exists = true, lastModifiedNanos = nanos, size = attrs.size(), fileKey = Option(attrs.fileKey()))
      catch case _: NoSuchFileException =>
        FileStamp(path, exists = false, lastModifiedNanos = 0L, size = 0L, fileKey = None)
    }

    def readAndDecode(stamp: FileStamp): IO[Either[String, A]] =
      if !stamp.exists then IO(validate(default))
      else
        IO.blocking(parseHoconFile[A](stamp.path, validate)).attempt.map {
          case Left(ioErr) => Left(s"Cannot read ${stamp.path}: ${Option(ioErr.getMessage).getOrElse(ioErr.getClass.getSimpleName)}")
          case Right(res)  => res
        }

    for
      initialPath <- Resource.eval(resolvePath)
      initialStamp <- Resource.eval(stat(initialPath))
      initialValue <- Resource.eval(readAndDecode(initialStamp).flatMap {
        case Right(v)  => IO.pure(v)
        case Left(err) => IO.raiseError(ConfigFailure("invalid_config", s"Invalid startup config at $initialPath: $err"))
      })
      sigRef <- Resource.eval(SignallingRef[IO, A](initialValue))
      stampRef <- Resource.eval(Ref.of[IO, FileStamp](initialStamp))
      applyStamp = (nextStamp: FileStamp) =>
        readAndDecode(nextStamp).flatMap {
          case Right(nextCfg) =>
            for
              prevCfg <- sigRef.getAndSet(nextCfg)
              changed = prevCfg != nextCfg
              _ <- stampRef.set(nextStamp)
              _ <- if changed then onChange(prevCfg, nextCfg).handleErrorWith(e => IO(scribe.error("Config onChange callback failed", e))) else IO.unit
              _ <- if changed then IO(scribe.info(s"Configuration reloaded from ${nextStamp.path} (exists=${nextStamp.exists})")) else IO.unit
            yield ()
          case Left(err) =>
            stampRef.set(nextStamp) *>
              IO(scribe.error(s"Configuration reload failed for ${nextStamp.path}; keeping last valid config: $err"))
        }
      pollStep = for
        p <- resolvePath
        curStamp <- stat(p)
        prevStamp <- stampRef.get
        _ <- if curStamp != prevStamp then applyStamp(curStamp) else IO.unit
      yield ()
      _ <- Stream.awakeEvery[IO](pollInterval).evalMap(_ => pollStep.handleErrorWith(e => IO(scribe.warn("Config metadata poll error", e)))).compile.drain.background
    yield sigRef

  def applyLogLevel(level: LogLevel): IO[Unit] = IO {
    val target = level match
      case LogLevel.trace => scribe.Level.Trace
      case LogLevel.debug => scribe.Level.Debug
      case LogLevel.info  => scribe.Level.Info
      case LogLevel.warn  => scribe.Level.Warn
      case LogLevel.error => scribe.Level.Error
    scribe.Logger.root.clearModifiers().withMinimumLevel(target).replace()
    ()
  }

  private def configValueToJson(cv: ConfigValue): Json = cv.valueType() match
    case ConfigValueType.NULL    => Json.Null
    case ConfigValueType.BOOLEAN => Json.fromBoolean(cv.unwrapped().asInstanceOf[java.lang.Boolean].booleanValue())
    case ConfigValueType.STRING  => Json.fromString(cv.unwrapped().asInstanceOf[String])
    case ConfigValueType.NUMBER  =>
      cv.unwrapped() match
        case i: java.lang.Integer    => Json.fromInt(i.intValue())
        case l: java.lang.Long       => Json.fromLong(l.longValue())
        case bi: java.math.BigInteger => Json.fromBigInt(BigInt(bi))
        case bd: java.math.BigDecimal => Json.fromBigDecimal(BigDecimal(bd))
        case n: java.lang.Number     => Json.fromDoubleOrString(n.doubleValue())
        case other                   => Json.fromString(other.toString)
    case ConfigValueType.LIST =>
      Json.fromValues(cv.asInstanceOf[ConfigList].asScala.map(configValueToJson))
    case ConfigValueType.OBJECT =>
      val entries = cv.asInstanceOf[ConfigObject].entrySet().asScala.toVector.sortBy(_.getKey).map { e =>
        e.getKey -> configValueToJson(e.getValue)
      }
      Json.fromJsonObject(JsonObject.fromIterable(entries))

  private def normalizeForSchema(json: Json, schema: SchemaNode): Json = schema match
    case SchemaNode.Product(_, fields) =>
      if json.isNull then Json.obj()
      else json.asObject match
        case None => json
        case Some(obj) =>
          val fieldSchemas = fields().map(f => f.name -> f.schema()).toMap
          val mapped = obj.toVector.map { (k, v) =>
            val normKey = if k == "notify" && !fieldSchemas.contains("notify") && fieldSchemas.contains("notifications") then "notifications" else k
            fieldSchemas.get(normKey) match
              case Some(childSchema) => normKey -> normalizeForSchema(v, childSchema)
              case None              => normKey -> v
          }
          Json.fromJsonObject(JsonObject.fromIterable(mapped))
    case SchemaNode.ArrayOf(elem) =>
      json.asArray match
        case Some(arr) => Json.fromValues(arr.map(normalizeForSchema(_, elem())))
        case None      => json
    case SchemaNode.Dictionary(elem) =>
      json.asObject match
        case Some(obj) => Json.fromJsonObject(obj.mapValues(normalizeForSchema(_, elem())))
        case None      => json
    case SchemaNode.Nullable(inner) =>
      if json.isNull then Json.Null else normalizeForSchema(json, inner())
    case SchemaNode.Primitive("integer", _) =>
      json.asString match
        case Some(raw) =>
          try
            val bytes = ConfigFactory.parseString(s"v = ${Json.fromString(raw.trim).noSpaces}").getBytes("v")
            Json.fromLong(bytes)
          catch case _: ConfigException => json
        case None => json
    case _ => json

  private def checkUnknownKeys(json: Json, schema: SchemaNode, prefix: String): Either[String, Unit] = schema match
    case SchemaNode.Product(_, fields) =>
      json.asObject match
        case None => Right(())
        case Some(obj) =>
          val fieldMap = fields().map(f => f.name -> f.schema()).toMap
          obj.toVector.traverse_ { (k, v) =>
            val full = if prefix.isEmpty then k else s"$prefix.$k"
            fieldMap.get(k) match
              case None => Left(s"Unknown config field '$full'")
              case Some(child) => checkUnknownKeys(v, child, full)
          }
    case SchemaNode.ArrayOf(elem) =>
      json.asArray.getOrElse(Vector.empty).zipWithIndex.traverse_ { (item, idx) =>
        checkUnknownKeys(item, elem(), s"$prefix[$idx]")
      }
    case SchemaNode.Dictionary(elem) =>
      json.asObject.map(_.toVector).getOrElse(Vector.empty).traverse_ { (k, v) =>
        checkUnknownKeys(v, elem(), if prefix.isEmpty then k else s"$prefix.$k")
      }
    case SchemaNode.Nullable(inner) =>
      if json.isNull then Right(()) else checkUnknownKeys(json, inner(), prefix)
    case _ => Right(())

  private def formatCursor(ops: List[io.circe.CursorOp]): String =
    val parts = ops.reverse.collect { case io.circe.CursorOp.DownField(k) => k }
    if parts.isEmpty then "" else s" at '${parts.mkString(".")}'"
