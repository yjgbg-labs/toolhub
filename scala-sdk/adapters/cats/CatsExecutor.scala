//> using dep org.typelevel::cats-effect::3.7.1
package sprout.sdk.cats

import _root_.cats.effect.kernel.Async
import _root_.cats.syntax.all.*
import sprout.sdk.*

/** The caller owns the transport's Resource, cancellation and connection policy. */
final class CatsExecutor[F[_]](send: HttpRequest => F[HttpResponse])(using F: Async[F]) extends Executor[F]:
  def execute[A](call: Call[A]): F[A] =
    F.defer(send(call.request)).adaptError { case error => ClientError.fromThrowable(error) }
      .flatMap(response => F.fromEither(call.decode(response)))
