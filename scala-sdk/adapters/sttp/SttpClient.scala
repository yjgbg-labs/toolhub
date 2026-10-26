package sprout.sdk.sttp

import _root_.sttp.client4.Backend
import scala.concurrent.duration.*
import sprout.sdk.{Catalog, DynamicClient}

object SttpClient:
  /** Discover once using the backend's effect. The caller retains ownership of the backend. */
  def connect[F[_]](
      backend: Backend[F],
      baseUrl: String,
      headers: Vector[(String, String)] = Vector.empty,
      timeout: FiniteDuration = 30.seconds,
      schemaPath: String = "/openapi.json"
  ): F[DynamicClient[F]] =
    val transport = new SttpTransport(backend, baseUrl, headers, timeout)
    val executor = new SttpExecutor(backend, transport)
    executor.execute(Catalog.discover(schemaPath).map(catalog => new DynamicClient(catalog, executor)))
