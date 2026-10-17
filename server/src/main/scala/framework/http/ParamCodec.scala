package framework.http

import cats.syntax.all.*
import framework.model.{JsonModel, Schema, SchemaNode}
import io.circe.Json
import java.util.UUID
import scala.util.Try

trait ParamCodec[A]:
  def schema: Schema[A]
  def encode(value: A): Json
  def decode(values: Vector[String]): Either[String, A]
  def missing: Option[A] = None
  def repeated: Boolean = false

object ParamCodec:
  def scalar[A: JsonModel](parse: String => Either[String, A]): ParamCodec[A] = new ParamCodec[A]:
    val schema = JsonModel[A].schema
    def encode(value: A) = JsonModel[A].encoder(value)
    def decode(values: Vector[String]) = values match
      case Vector(value) => parse(value)
      case _             => Left("Expected exactly one value")

  private def number[A: JsonModel](name: String)(parse: String => A): ParamCodec[A] =
    scalar(value => Try(parse(value)).toEither.left.map(_ => s"Expected $name"))

  given ParamCodec[String] = scalar(Right(_))
  given ParamCodec[Int] = number("a 32-bit integer")(_.toInt)
  given ParamCodec[Long] = number("a 64-bit integer")(_.toLong)
  given ParamCodec[BigDecimal] = number("a decimal number")(BigDecimal(_))
  given ParamCodec[Double] = scalar { value =>
    Try(value.toDouble).toEither.left.map(_ => "Expected a finite number")
      .flatMap(n => Either.cond(n.isFinite, n, "Expected a finite number"))
  }
  given ParamCodec[Boolean] = scalar {
    case "true"  => Right(true)
    case "false" => Right(false)
    case _       => Left("Expected true or false")
  }
  given ParamCodec[UUID] = number("a UUID")(UUID.fromString)

  given [A](using inner: ParamCodec[A]): ParamCodec[Option[A]] = new ParamCodec[Option[A]]:
    val schema = Schema[Option[A]](inner.schema.node)
    def encode(value: Option[A]) = value.fold(Json.Null)(inner.encode)
    def decode(values: Vector[String]) = inner.decode(values).map(Some(_))
    override val missing = Some(None)
    override def repeated = inner.repeated

  given [A](using inner: ParamCodec[A]): ParamCodec[List[A]] = new ParamCodec[List[A]]:
    require(!inner.repeated, "Nested repeated query parameters are not supported")
    val schema = Schema[List[A]](() => SchemaNode.ArrayOf(inner.schema.node))
    def encode(value: List[A]) = Json.fromValues(value.map(inner.encode))
    def decode(values: Vector[String]) = values.toList.traverse(v => inner.decode(Vector(v)))
    override val missing = Some(Nil)
    override val repeated = true
