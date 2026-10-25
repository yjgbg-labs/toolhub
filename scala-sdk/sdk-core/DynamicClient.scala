package sprout.sdk

import io.circe.Json

/** Discover once with executor.execute(Catalog.discover()), then bind any effect. */
final class DynamicClient[F[_]](val catalog: Catalog, executor: Executor[F]):
  def call(operationId: String, input: Json = Json.obj()): F[HttpResponse] =
    executor.execute(catalog.call(operationId, input))
