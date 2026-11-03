package groot.wine

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*
import io.circe.Json

object WineRoutes:
  def register(service: WineService)(using CommandsScope): Unit = commands("wine") {
    description("Run Windows EXEs and native, .NET or COM DLL sessions in named Wine environments")
    val invalid = response.json[ErrorBody](400) { description("Invalid Wine arguments") }
    val missing = response.json[ErrorBody](404) { description("Unknown instance, process or DLL session") }
    val conflict = response.json[ErrorBody](409) { description("Stopped instance, duplicate name or incompatible mode") }
    val limit = response.json[ErrorBody](429) { description("Too many workers") }
    val failed = response.json[ErrorBody](502) { description("Wine or worker call failed") }
    val unavailable = response.json[ErrorBody](503) { description("Wine runtime or helper unavailable") }
    val timeout = response.json[ErrorBody](504) { description("Operation timed out") }
    recover { case error: WineFailure =>
      val body = ErrorBody(error.code, error.getMessage)
      error.code match
        case "invalid_wine" => invalid(body)
        case "wine_not_found" => missing(body)
        case "wine_conflict" | "wine_stopped" => conflict(body)
        case "wine_limit" => limit(body)
        case "wine_unavailable" => unavailable(body)
        case "wine_timeout" | "wine_wait_timeout" => timeout(body)
        case _ => failed(body)
    }
    commands("instance") {
      command[WineInstance]("start") {
        description("Create/start a persistent Wine prefix; headless uses a private invisible Xvfb display")
        val name = param[String]("instanceName", "Persistent instance name")
        val arch = param[String]("arch", "Wine prefix architecture, fixed at creation; win64 supports x64 and x86 with WoW64") { default("win64") }
        val headless = param[Boolean]("headless", "Use an invisible virtual display") { default(true) }
        handle { service.start(name.value, arch.value, headless.value) }
      }
      command[Vector[WineInstance]]("list") {
        description("List managed running and stopped Wine instances")
        handle { service.list }
      }
      command[WineInstance]("stop") {
        description("Stop this instance's EXEs, DLL sessions and wineserver; retain its prefix and logs")
        val name = param[String]("instanceName", "Instance name")
        handle { service.stop(name.value) }
      }
      command[Boolean]("purge") {
        description("Stop and remove an instance and its prefix; completed EXE task records remain")
        val name = param[String]("instanceName", "Instance name")
        handle { service.purge(name.value) }
      }
    }
    commands("process") {
      command[WineTask]("start") {
        description("Start an EXE asynchronously; arguments are passed literally without an implicit shell")
        val name = param[String]("instanceName", "Running instance name")
        val request = body[WineExec]("EXE path, argument array, optional cwd/env/stdinBase64, architecture and execution timeout")
        handle { service.execute(name.value, request.value) }
      }
      command[Vector[WineTask]]("list") {
        description("List the newest 200 EXE tasks, including completed tasks")
        val name = param[Option[String]]("instanceName", "Optional instance filter")
        handle { service.tasks(name.value) }
      }
      command[WineTask]("status") {
        description("Read persisted state, unsigned Windows exit code and log paths")
        val id = param[String]("id", "Process task ID")
        handle { service.status(id.value) }
      }
      command[WineTask]("wait") {
        description("Wait for a task to finish; expiration/disconnection does not cancel execution")
        val id = param[String]("id", "Process task ID")
        val timeout = param[Int]("timeoutMs", "Maximum wait duration") { default(30000); minimum(1); maximum(86400000) }
        handle { service.await(id.value, timeout.value) }
      }
      command[WineTask]("stop") {
        description("Stop one EXE task and its Windows job; other workers remain running")
        val id = param[String]("id", "Process task ID")
        handle { service.cancel(id.value) }
      }
      command[PlainText]("logs") {
        description("Read the last 64 KiB of stdout or stderr as UTF-8; raw log files preserve original bytes")
        val id = param[String]("id", "Process task ID")
        val stream = param[String]("stream", "stdout or stderr") { default("stdout") }
        handle { service.logs(id.value, stream.value).map(PlainText(_)) }
      }
      command[SSE[PlainText]]("logs-follow") {
        description("Replay and follow stdout or stderr until task completion; each log retains up to 16 MiB")
        val id = param[String]("id", "Process task ID")
        val stream = param[String]("stream", "stdout or stderr") { default("stdout") }
        handle { service.follow(id.value, stream.value) }
      }
    }
    commands("dll") {
      val name = param[String]("instanceName", "Running instance name")
      command[WineDll]("load") {
        description("Open a persistent native, .NET or COM session in a separate matching-bitness worker")
        val dll = param[String]("dllName", "New DLL session name")
        val request = body[WineLoad]("kind native/dotnet/com, DLL path or COM classId, architecture x64/x86; runtime dotnet or framework (installs Wine Mono lazily)")
        handle { service.load(name.value, dll.value, request.value) }
      }
      command[Vector[WineDll]]("list") {
        description("List DLL sessions and whether their worker is still alive")
        handle { service.dlls(name.value) }
      }
      command[Json]("call") {
        description("Invoke a typed native export, managed method/constructor, or COM IDispatch method/property")
        val dll = param[String]("dllName", "DLL session name")
        val timeout = param[Int]("timeoutMs", "Call timeout; expiration destroys this worker and invalidates its handles") { default(30000); minimum(1); maximum(86400000) }
        val request = body[WineCall]("member, typed args, operation invoke/create/get/set, managed typeName or target handle, native returnType/callingConvention")
        handle { service.call(name.value, dll.value, request.value, timeout.value) }
      }
      command[Json]("inspect") {
        description("Inspect native named exports or managed public method/constructor signatures")
        val dll = param[String]("dllName", "DLL session name")
        val typ = param[Option[String]]("typeName", "Optional managed type name")
        handle { service.inspect(name.value, dll.value, typ.value) }
      }
      command[Boolean]("release") {
        description("Release a session object/COM handle; native pointer ownership remains with its library")
        val dll = param[String]("dllName", "DLL session name")
        val handleId = param[String]("handle", "Opaque handle returned by this session")
        handle { service.release(name.value, dll.value, handleId.value) }
      }
      command[Boolean]("unload") {
        description("Close a DLL worker and invalidate all its handles")
        val dll = param[String]("dllName", "DLL session name")
        handle { service.unload(name.value, dll.value) }
      }
    }
  }
