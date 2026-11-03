package groot.wine

import framework.rpc.CommandsScope
import framework.rpcDsl.*

object WineRoutes:
  def register(service: WineService)(using CommandsScope): Unit = commands("wine") {
    description("Execute Windows programs or call native, .NET and COM libraries in one shot")
    val invalid = response.json[WineError](400) { description("Invalid invocation or signature") }
    val failed = response.json[WineError](502) { description("Library call or Wine process failed; includes captured logs") }
    val unavailable = response.json[WineError](503) { description("Wine, helper or user systemd unavailable") }
    val timeout = response.json[WineError](504) { description("Execution timed out; this invocation's processes were stopped") }
    recover { case error: WineFailure => error.error.code match
      case "invalid_wine" => invalid(error.error)
      case "wine_unavailable" => unavailable(error.error)
      case "wine_timeout" => timeout(error.error)
      case _ => failed(error.error)
    }
    command[WineExecResult]("exec") {
      description("Run an EXE synchronously through Wine; capture output and clean up its process tree on completion/cancellation")
      val request = body[WineExec]("exe, literal args, optional Linux cwd/env/stdinBase64; runtime wine/dotnet/framework, architecture x64/x86, timeoutMs includes setup")
      handle { service.execute(request.value) }
    }
    command[WineCallResult]("call") {
      description("Load a library, execute a typed call or batch in a fresh helper, return JSON and exit")
      val request = body[WineCall]("kind native/dotnet/com; path or classId; single member/args or steps with saveAs/target/argument ref scoped to this request; runtime dotnet/framework")
      handle { service.call(request.value) }
    }
  }
