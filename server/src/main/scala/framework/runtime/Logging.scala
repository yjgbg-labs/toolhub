package framework.runtime

import cats.effect.IO
import framework.model.JsonModel
import framework.validation.Validator

enum LogLevel derives JsonModel, Validator:
  case trace, debug, info, warn, error

final case class LogConfig(level: LogLevel = LogLevel.info) derives JsonModel, Validator

object Logging:
  def applyLevel(level: LogLevel): IO[Unit] = IO {
    val target = level match
      case LogLevel.trace => scribe.Level.Trace
      case LogLevel.debug => scribe.Level.Debug
      case LogLevel.info  => scribe.Level.Info
      case LogLevel.warn  => scribe.Level.Warn
      case LogLevel.error => scribe.Level.Error
    scribe.Logger.root.clearModifiers().withMinimumLevel(target).replace()
    ()
  }
