package framework.validation

import com.comcast.ip4s.Host
import org.http4s.Uri
import scala.concurrent.duration.*

/** Reusable boolean predicates for `@contract[F](...)`. */
object Contracts:
  private val positiveDurationPattern = "^(\\d+)(ms|s|m|h|d)$".r
  private val durationUnits = Map("ms" -> 1L, "s" -> 1000L, "m" -> 60000L, "h" -> 3600000L, "d" -> 86400000L)

  val nonBlank: String => Boolean =
    s => s != null && s.trim.nonEmpty && !s.contains('\u0000')

  val isHost: String => Boolean =
    s => nonBlank(s) && Host.fromString(s.trim).isDefined

  val isHttpUrl: String => Boolean = s =>
    nonBlank(s) && Uri.fromString(s.trim).exists { u =>
      u.scheme.exists(sc => sc == Uri.Scheme.http || sc == Uri.Scheme.https) && u.host.nonEmpty
    }

  val isDuration: String => Boolean = s =>
    s != null && (s.trim match
      case positiveDurationPattern(amount, unit) =>
        val millis = BigInt(amount) * durationUnits(unit)
        millis > 0 && millis <= Long.MaxValue / 1_000_000L
      case _ => false)

  def parseDuration(raw: String): FiniteDuration =
    raw.trim match
      case positiveDurationPattern(amount, unit) =>
        val millis = BigInt(amount) * durationUnits(unit)
        require(millis > 0 && millis <= Long.MaxValue / 1_000_000L, s"Duration out of range: $raw")
        millis.toLong.millis
      case _ =>
        throw new IllegalArgumentException(s"Invalid duration '$raw': expected positive integer followed by ms, s, m, h or d")

  def formatBytes(bytes: Long): String =
    if bytes >= 1024L * 1024L && bytes % (1024L * 1024L) == 0L then s"${bytes / (1024L * 1024L)} MiB"
    else if bytes >= 1024L && bytes % 1024L == 0L then s"${bytes / 1024L} KiB"
    else s"$bytes B"
