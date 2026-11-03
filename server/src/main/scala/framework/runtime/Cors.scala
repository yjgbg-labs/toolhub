package framework.runtime

import cats.effect.IO
import framework.http.CorsSettings
import org.http4s.{HttpApp, Method}
import org.http4s.server.middleware.CORS
import org.http4s.syntax.header.*
import org.typelevel.ci.CIString

private[runtime] object Cors:
  def apply(settings: CorsSettings, app: HttpApp[IO]): HttpApp[IO] =
    val origins = if settings.allowedOrigins.contains("*") then CORS.policy.withAllowOriginAll
      else CORS.policy.withAllowOriginHeader(origin => settings.allowedOrigins.contains(origin.value))
    val methods = if settings.allowedMethods.contains("*") then origins.withAllowMethodsAll
      else origins.withAllowMethodsIn(settings.allowedMethods.map(value => Method.fromString(value).fold(throw _, identity)))
    // Reflect names so Authorization and credentialed requests also work with allowHeaders("*").
    val headers = if settings.allowedHeaders.contains("*") then methods.withAllowHeadersReflect
      else methods.withAllowHeadersIn(settings.allowedHeaders.map(CIString(_)))
    val exposed = if settings.exposedHeaders.contains("*") then headers.withExposeHeadersAll
      else if settings.exposedHeaders.isEmpty then headers.withExposeHeadersNone
      else headers.withExposeHeadersIn(settings.exposedHeaders.map(CIString(_)))
    exposed.withAllowCredentials(settings.allowCredentials).withMaxAge(settings.maxAge).httpApp(app)
