package sprout

import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import scala.compiletime.{constValue, erasedValue, summonFrom}
import scala.deriving.Mirror
import java.util.UUID

trait JsonModel[A]:
  def typeId: String
  def encoder: Encoder[A]
  def decoder: Decoder[A]
  def schema: Schema[A]
  def missing: Option[A] = None
  def isSingleton: Boolean = false

object JsonModel:
  def apply[A](using model: JsonModel[A]): JsonModel[A] = model

  def instance[A](id: String, shape: => SchemaNode)(enc: Encoder[A], dec: Decoder[A]): JsonModel[A] =
    new JsonModel[A]:
      val typeId = id
      val encoder = enc
      val decoder = dec
      val schema = Schema[A](() => shape)

  private def numeric[A](decoder: Decoder[A]): Decoder[A] = Decoder.instance { cursor =>
    if cursor.value.isNumber then decoder(cursor)
    else Left(DecodingFailure("Expected a JSON number", cursor.history))
  }

  given JsonModel[String] = instance("scala.String", SchemaNode.Primitive("string"))(Encoder.encodeString, Decoder.decodeString)
  given JsonModel[Boolean] = instance("scala.Boolean", SchemaNode.Primitive("boolean"))(Encoder.encodeBoolean, Decoder.decodeBoolean)
  given JsonModel[Int] = instance("scala.Int", SchemaNode.Primitive("integer", Some("int32")))(Encoder.encodeInt, numeric(Decoder.decodeInt))
  given JsonModel[Long] = instance("scala.Long", SchemaNode.Primitive("integer", Some("int64")))(Encoder.encodeLong, numeric(Decoder.decodeLong))
  given JsonModel[Double] = instance("scala.Double", SchemaNode.Primitive("number", Some("double")))(
    Encoder.instance { value =>
      require(value.isFinite, "JSON numbers must be finite")
      Json.fromDoubleOrNull(value)
    },
    numeric(Decoder.decodeDouble).emap(value => Either.cond(value.isFinite, value, "Expected a finite number"))
  )
  given JsonModel[BigDecimal] = instance("scala.BigDecimal", SchemaNode.Primitive("number"))(Encoder.encodeBigDecimal, numeric(Decoder.decodeBigDecimal))
  given JsonModel[UUID] = instance("java.util.UUID", SchemaNode.Primitive("string", Some("uuid")))(Encoder.encodeUUID, Decoder.decodeUUID)

  given [A](using element: => JsonModel[A]): JsonModel[Option[A]] = new JsonModel[Option[A]]:
    def typeId = s"scala.Option[${element.typeId}]"
    lazy val encoder: Encoder[Option[A]] = Encoder.instance(_.fold(Json.Null)(element.encoder.apply))
    lazy val decoder: Decoder[Option[A]] = Decoder.decodeOption(element.decoder)
    val schema = Schema[Option[A]](() => SchemaNode.Nullable(() => element.schema.node()))
    override val missing = Some(None)

  given [A](using element: => JsonModel[A]): JsonModel[List[A]] = new JsonModel[List[A]]:
    def typeId = s"scala.List[${element.typeId}]"
    lazy val encoder: Encoder[List[A]] = Encoder.encodeList(element.encoder)
    lazy val decoder: Decoder[List[A]] = Decoder.decodeList(element.decoder)
    val schema = Schema[List[A]](() => SchemaNode.ArrayOf(() => element.schema.node()))

  given [A](using element: => JsonModel[A]): JsonModel[Vector[A]] = new JsonModel[Vector[A]]:
    def typeId = s"scala.Vector[${element.typeId}]"
    lazy val encoder: Encoder[Vector[A]] = Encoder.encodeVector(element.encoder)
    lazy val decoder: Decoder[Vector[A]] = Decoder.decodeVector(element.decoder)
    val schema = Schema[Vector[A]](() => SchemaNode.ArrayOf(() => element.schema.node()))

  given [A](using element: => JsonModel[A]): JsonModel[Map[String, A]] = new JsonModel[Map[String, A]]:
    def typeId = s"scala.Map[scala.String,${element.typeId}]"
    lazy val encoder: Encoder[Map[String, A]] = Encoder.encodeMap[String, A](io.circe.KeyEncoder.encodeKeyString, element.encoder)
    lazy val decoder: Decoder[Map[String, A]] = Decoder.decodeMap[String, A](io.circe.KeyDecoder.decodeKeyString, element.decoder)
    val schema = Schema[Map[String, A]](() => SchemaNode.Dictionary(() => element.schema.node()))

  inline def derived[A](using mirror: Mirror.Of[A]): JsonModel[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] =>
        productModel[A](
          () => derivedTypeId[A],
          labels[product.MirroredElemLabels].toVector,
          () => models[product.MirroredElemTypes].toVector,
          constructorDefaults[A],
          product
        )
      case sum: Mirror.SumOf[A] =>
        sumModel[A](
          () => derivedTypeId[A],
          labels[sum.MirroredElemLabels].toVector,
          () => models[sum.MirroredElemTypes].toVector,
          sum
        )

  inline def derivedTypeId[A]: String = ${ ModelMacros.typeId[A] }
  inline def constructorDefaults[A]: Vector[Option[() => Any]] = ${ ModelMacros.defaults[A] }

  inline def labels[T <: Tuple]: List[String] = inline erasedValue[T] match
    case _: EmptyTuple => Nil
    case _: (head *: tail) => constValue[head].asInstanceOf[String] :: labels[tail]

  inline def models[T <: Tuple]: List[() => JsonModel[Any]] = inline erasedValue[T] match
    case _: EmptyTuple => Nil
    case _: (head *: tail) =>
      (() => modelFor[head].asInstanceOf[JsonModel[Any]]) :: models[tail]

  inline def modelFor[A]: JsonModel[A] = summonFrom {
    case model: JsonModel[A] => model
    case mirror: Mirror.Of[A] => derived[A](using mirror)
  }

  def productModel[A](
      id: () => String,
      names: Vector[String],
      modelFactories: () => Vector[() => JsonModel[Any]],
      defaults: Vector[Option[() => Any]],
      mirror: Mirror.ProductOf[A]
  ): JsonModel[A] = new JsonModel[A]:
    lazy val typeId = id()
    private lazy val fields = modelFactories().map(_())
    override def isSingleton = names.isEmpty

    lazy val encoder: Encoder[A] = Encoder.instance { value =>
      val product = value.asInstanceOf[Product]
      Json.fromFields(names.indices.map(i => names(i) -> fields(i).encoder(product.productElement(i))))
    }

    lazy val decoder: Decoder[A] = Decoder.instance { cursor =>
      if !cursor.value.isObject then Left(DecodingFailure("Expected an object", cursor.history))
      else
        val values = names.indices.foldLeft[Decoder.Result[Vector[Any]]](Right(Vector.empty)) { (acc, i) =>
          acc.flatMap { decoded =>
            val field = cursor.downField(names(i))
            val result = if field.succeeded then fields(i).decoder.tryDecode(field)
                         else defaults(i).map(f => Right(f())).orElse(fields(i).missing.map(Right(_)))
                           .getOrElse(Left(DecodingFailure(s"Missing required field: ${names(i)}", field.history)))
            result.map(decoded :+ _)
          }
        }
        values.map(v => mirror.fromProduct(Tuple.fromArray(v.toArray)))
    }

    val schema: Schema[A] = Schema(() => SchemaNode.Product(typeId, () => names.indices.map { i =>
      SchemaField(
        names(i),
        () => fields(i).schema.node(),
        required = defaults(i).isEmpty && fields(i).missing.isEmpty,
        default = defaults(i).map(f => () => fields(i).encoder(f()))
      )
    }.toVector))

  def sumModel[A](
      id: () => String,
      names: Vector[String],
      modelFactories: () => Vector[() => JsonModel[Any]],
      mirror: Mirror.SumOf[A]
  ): JsonModel[A] = new JsonModel[A]:
    lazy val typeId = id()
    private lazy val variants = modelFactories().map(_())
    private lazy val stringEnum = variants.forall(_.isSingleton)

    lazy val encoder: Encoder[A] = Encoder.instance { value =>
      val index = mirror.ordinal(value)
      if stringEnum then Json.fromString(names(index))
      else Json.obj("type" -> Json.fromString(names(index)), "value" -> variants(index).encoder(value))
    }

    lazy val decoder: Decoder[A] = Decoder.instance { cursor =>
      val label = if stringEnum then cursor.as[String] else cursor.get[String]("type")
      label.flatMap { name =>
        val index = names.indexOf(name)
        if index < 0 then Left(DecodingFailure(s"Unknown variant: $name", cursor.history))
        else
          val result = if stringEnum then variants(index).decoder.decodeJson(Json.obj())
                       else variants(index).decoder.tryDecode(cursor.downField("value"))
          result.map(_.asInstanceOf[A])
      }
    }

    val schema: Schema[A] = Schema(() =>
      if stringEnum then SchemaNode.Enumeration(typeId, names)
      else SchemaNode.Sum(typeId, () => names.indices.map(i => names(i) -> variants(i).schema.node()).toVector)
    )
