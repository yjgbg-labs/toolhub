package framework.rpc

import cats.effect.IO
import fs2.Stream

/** Each stream element becomes one SSE data event. Consumption owns resource cleanup. */
final case class SSE[A](events: Stream[IO, A])

object SSE:
  private[framework] def frame(data: String): String =
    data.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)
      .map(line => s"data: $line\n").mkString + "\n"
