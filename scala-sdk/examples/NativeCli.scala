package sprout.sdk.examples

import sprout.sdk.*
import sprout.sdk.native.UnixSocketBackend
import sprout.sdk.sttp.{SttpExecutor, SttpTransport}
import sprout.generated.{Client, ScheduleEntry, ScheduleJob}
import _root_.sttp.client4.{DefaultSyncBackend, SyncBackend}
import _root_.sttp.shared.Identity

/** A small executable example of a native CLI consuming the generated SDK. */
object NativeCli:
  def main(args: Array[String]): Unit =
    if args.isEmpty || args.sameElements(Array("--help")) then
      println("sprout-native health | schedule list | schedule log <name> | schedule new <name> <job-json>\nSPROUT_BASE_URL=http(s)://host or unix:///absolute/socket")
    else
      val base = sys.env.getOrElse("SPROUT_BASE_URL", "unix://" + sys.env.getOrElse("XDG_RUNTIME_DIR", sys.props("user.home") + "/.local/run") + "/sprout-http/http.sock")
      val backend: SyncBackend = if base.startsWith("unix://") then new UnixSocketBackend(base.stripPrefix("unix://")) else DefaultSyncBackend()
      val transport = new SttpTransport[Identity](backend, if base.startsWith("unix://") then "http://localhost" else base)
      val client = new Client[Identity](new SttpExecutor(backend, transport))
      try
        val result = args.toList match
          case List("health") => client.health()
          case List("schedule", "list") => JsonCodec.vector(ScheduleEntry.codec).write(client.schedule.list()).spaces2
          case List("schedule", "log", name) => client.schedule.log(name)
          case List("schedule", "new", name, payload) =>
            val body = io.circe.jawn.parse(payload).left.map(_.message).flatMap(ScheduleJob.codec.read).fold(message => throw new IllegalArgumentException(message), identity)
            ScheduleJob.codec.write(client.schedule.`new`(name, body)).spaces2
          case _ => throw new IllegalArgumentException("Unknown command; use --help")
        println(result)
      catch
        case error: Exception =>
          System.err.println(error.getMessage)
          sys.exit(1)
      finally backend.close()
