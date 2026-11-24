package framework.validation

import io.circe.Json
import java.util.UUID
import scala.annotation.StaticAnnotation
import scala.compiletime.erasedValue
import scala.deriving.Mirror

/** Base typed boolean contract annotation; concrete constraint annotations extend `contract[F]`. */
open class contract[F](
    val predicate: F => Boolean,
    val message: String = ""
) extends StaticAnnotation

final class min(value: Long) extends contract[Long](_ >= value, s"must be >= $value")
final class max(value: Long) extends contract[Long](_ <= value, s"must be <= $value")
final class range(low: Long, high: Long) extends contract[Long](v => v >= low && v <= high, s"must be in $low..$high")
final class positive extends contract[Long](_ > 0, "must be > 0")
final class nonNegative extends contract[Long](_ >= 0, "must be >= 0")
final class minDouble(value: Double) extends contract[Double](v => v.isFinite && v >= value, s"must be >= $value")
final class maxDouble(value: Double) extends contract[Double](v => v.isFinite && v <= value, s"must be <= $value")
final class nonBlank extends contract[String](Contracts.nonBlank, "must not be blank")
final class pattern(regex: String) extends contract[String](s => s != null && s.matches(regex), s"must match /$regex/")
final class duration extends contract[String](Contracts.isDuration, "must be a positive duration (e.g. 500ms, 30s, 1h)")
final class hostAddress extends contract[String](Contracts.isHost, "must be a valid IP or hostname")
final class httpUrl extends contract[String](Contracts.isHttpUrl, "must be an absolute http(s) URL")

final case class ValidationIssue(path: String, message: String):
  def render: String = if path.isEmpty then message else s"$path: $message"

trait Validator[A]:
  def validateIssues(value: A, path: String = ""): Vector[ValidationIssue]

  final def validate(value: A): Either[Vector[ValidationIssue], A] =
    val issues = validateIssues(value)
    if issues.isEmpty then Right(value) else Left(issues)

  final def validateMessage(value: A): Either[String, A] =
    validate(value).left.map(_.map(_.render).mkString("; "))

object Validator:
  def apply[A](using v: Validator[A]): Validator[A] = v

  def noop[A]: Validator[A] = (_: A, _: String) => Vector.empty

  private[validation] def evalContract[F](
      instance: contract[F],
      arg: F,
      path: String,
      sourceExpr: String
  ): Option[ValidationIssue] =
    val passed =
      try instance.predicate(arg)
      catch case scala.util.control.NonFatal(_) => false
    if passed then None
    else
      val detail =
        if instance.message.nonEmpty then s"${instance.message} (got $arg)"
        else s"failed contract ($sourceExpr), got $arg"
      Some(ValidationIssue(path, detail))

  given Validator[String] = noop
  given Validator[Boolean] = noop
  given Validator[Int] = noop
  given Validator[Long] = noop
  given Validator[Double] = noop
  given Validator[BigDecimal] = noop
  given Validator[UUID] = noop
  given Validator[Json] = noop

  given [A](using elem: => Validator[A]): Validator[Option[A]] = new Validator[Option[A]]:
    def validateIssues(value: Option[A], path: String): Vector[ValidationIssue] =
      value.fold(Vector.empty)(elem.validateIssues(_, path))

  given [A](using elem: => Validator[A]): Validator[Vector[A]] = new Validator[Vector[A]]:
    def validateIssues(value: Vector[A], path: String): Vector[ValidationIssue] =
      value.zipWithIndex.flatMap { (item, idx) =>
        val sub = if path.isEmpty then s"[$idx]" else s"$path[$idx]"
        elem.validateIssues(item, sub)
      }

  given [A](using elem: => Validator[A]): Validator[List[A]] = new Validator[List[A]]:
    def validateIssues(value: List[A], path: String): Vector[ValidationIssue] =
      value.toVector.zipWithIndex.flatMap { (item, idx) =>
        val sub = if path.isEmpty then s"[$idx]" else s"$path[$idx]"
        elem.validateIssues(item, sub)
      }

  given [A](using elem: => Validator[A]): Validator[Set[A]] = new Validator[Set[A]]:
    def validateIssues(value: Set[A], path: String): Vector[ValidationIssue] =
      value.toVector.zipWithIndex.flatMap { (item, idx) =>
        val sub = if path.isEmpty then s"[$idx]" else s"$path[$idx]"
        elem.validateIssues(item, sub)
      }

  given [A](using elem: => Validator[A]): Validator[Map[String, A]] = new Validator[Map[String, A]]:
    def validateIssues(value: Map[String, A], path: String): Vector[ValidationIssue] =
      value.toVector.sortBy(_._1).flatMap { (k, v) =>
        val sub = if path.isEmpty then k else s"$path.$k"
        elem.validateIssues(v, sub)
      }

  inline def derived[A](using mirror: Mirror.Of[A]): Validator[A] =
    inline mirror match
      case _: Mirror.ProductOf[A] => deriveProductMacro[A]
      case sum: Mirror.SumOf[A]   => deriveSum[A](sum, sumValidators[sum.MirroredElemTypes].toVector)

  private inline def deriveProductMacro[A]: Validator[A] = ${ ValidationMacros.deriveProduct[A] }

  private inline def sumValidators[T <: Tuple]: List[() => Validator[Any]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (head *: tail) =>
        (() => summonOrDerive[head].asInstanceOf[Validator[Any]]) :: sumValidators[tail]

  private inline def summonOrDerive[A]: Validator[A] =
    scala.compiletime.summonFrom {
      case v: Validator[A] => v
      case m: Mirror.Of[A] => derived[A](using m)
    }

  private def deriveSum[A](mirror: Mirror.SumOf[A], branches: Vector[() => Validator[Any]]): Validator[A] =
    new Validator[A]:
      private lazy val resolved = branches.map(_())
      def validateIssues(value: A, path: String): Vector[ValidationIssue] =
        resolved(mirror.ordinal(value)).validateIssues(value, path)
