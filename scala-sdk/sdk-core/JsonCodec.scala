package sprout.sdk

import io.circe.{Decoder, Encoder, Json}

/** Codecs are ordinary values; recursive references are evaluated during encode/decode. */
trait JsonCodec[A]:
  def write(value: A): Json
  def read(value: Json): Either[String, A]

object JsonCodec:
  def apply[A](encode: A => Json, decode: Json => Either[String, A]): JsonCodec[A] = new JsonCodec[A]:
    def write(value: A) = encode(value)
    def read(value: Json) = decode(value)

  private def circe[A](using encoder: Encoder[A], decoder: Decoder[A]): JsonCodec[A] =
    apply(encoder.apply, json => decoder.decodeJson(json).left.map(_.message))

  val string: JsonCodec[String] = circe[String]
  val boolean: JsonCodec[Boolean] = circe[Boolean]
  private def number[A](using Encoder[A], Decoder[A]): JsonCodec[A] =
    val codec = circe[A]
    apply(codec.write, json => if json.isNumber then codec.read(json) else Left("Expected JSON number"))
  val int: JsonCodec[Int] = number[Int]
  val long: JsonCodec[Long] = number[Long]
  val bigInt: JsonCodec[BigInt] = number[BigInt]
  val decimal: JsonCodec[BigDecimal] = number[BigDecimal]
  val double: JsonCodec[Double] = number[Double]
  val json: JsonCodec[Json] = apply(identity, Right(_))

  def constant(value: Json): JsonCodec[Json] = apply(identity, input => Either.cond(input == value, input, s"Expected $value"))
  def nullable[A](codec: => JsonCodec[A]): JsonCodec[Option[A]] =
    apply(_.fold(Json.Null)(codec.write), value => if value.isNull then Right(None) else codec.read(value).map(Some(_)))
  def vector[A](codec: => JsonCodec[A]): JsonCodec[Vector[A]] = apply(
    values => Json.fromValues(values.map(codec.write)),
    value => value.asArray.toRight("Expected array").flatMap(_.foldLeft[Either[String, Vector[A]]](Right(Vector.empty)) {
      (acc, item) => for xs <- acc; x <- codec.read(item) yield xs :+ x
    })
  )
  def dictionary[A](codec: => JsonCodec[A]): JsonCodec[Map[String, A]] = apply(
    values => Json.fromFields(values.toVector.sortBy(_._1).map((name, value) => name -> codec.write(value))),
    value => value.asObject.toRight("Expected object").flatMap(_.toVector.foldLeft[Either[String, Map[String, A]]](Right(Map.empty)) {
      case (acc, (key, item)) => for xs <- acc; x <- codec.read(item) yield xs.updated(key, x)
    })
  )

  def field[A](json: Json, name: String, codec: JsonCodec[A]): Either[String, A] =
    json.asObject.toRight("Expected object").flatMap(_(name).toRight(s"Missing required field: $name"))
      .flatMap(codec.read).left.map(error => s"$name: $error")
  def optional[A](json: Json, name: String, codec: JsonCodec[A]): Either[String, Optional[A]] =
    json.asObject.toRight("Expected object").flatMap { obj =>
      obj(name).fold[Either[String, Optional[A]]](Right(Optional.Missing))(value => codec.read(value).map(Optional.Present(_)))
    }
  def optionalField[A](name: String, value: Optional[A], codec: JsonCodec[A]): Vector[(String, Json)] =
    value.toOption.toVector.map(item => name -> codec.write(item))
