package framework.validation

import io.circe.Json
import java.util.UUID
import scala.annotation.StaticAnnotation
import scala.compiletime.{erasedValue, summonInline}
import scala.deriving.Mirror

/** Attach a typed boolean contract to a case-class field (including Option[F] / Iterable[F]) or to the case class itself. */
final class contract[F](
    val predicate: F => Boolean,
    val message: String = ""
) extends StaticAnnotation

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

  private[validation] def evalCheck[F](
      pred: F => Boolean,
      arg: F,
      path: String,
      explicitMsg: Option[String],
      sourceExpr: String
  ): Option[ValidationIssue] =
    val passed =
      try pred(arg)
      catch case scala.util.control.NonFatal(_) => false
    if passed then None
    else
      val detail = explicitMsg.filter(_.nonEmpty).getOrElse(s"failed contract ($sourceExpr), got $arg")
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
      case v: Validator[A]  => v
      case m: Mirror.Of[A]  => derived[A](using m)
    }

  private def deriveSum[A](mirror: Mirror.SumOf[A], branches: Vector[() => Validator[Any]]): Validator[A] =
    new Validator[A]:
      private lazy val resolved = branches.map(_())
      def validateIssues(value: A, path: String): Vector[ValidationIssue] =
        resolved(mirror.ordinal(value)).validateIssues(value, path)
