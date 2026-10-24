//> using dep dev.zio::zio::2.1.24
package sprout.sdk.zio

import _root_.zio.ZIO
import sprout.sdk.*

/** R and typed errors survive; the caller owns the transport's Scope. */
final class ZioExecutor[R](send: HttpRequest => ZIO[R, Throwable, HttpResponse])
    extends Executor[[A] =>> ZIO[R, ClientError, A]]:
  def execute[A](call: Call[A]): ZIO[R, ClientError, A] =
    ZIO.suspendSucceed(send(call.request)).mapError(ClientError.fromThrowable)
      .flatMap(response => ZIO.fromEither(call.decode(response)))
