package groot.shell

import java.nio.charset.StandardCharsets.UTF_16LE
import java.util.Base64

/** Invoke the user's script directly, without remote files, process tracking or cleanup scripts. */
private[shell] object ShellCommand:
  private def quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

  def remote(kind: ShellKind, script: String): String =
    val source = script.stripPrefix("\uFEFF")
    kind match
      case ShellKind.bash => "exec /usr/bin/env -u BASH_ENV /bin/bash --noprofile --norc -c " + quote(source)
      case ShellKind.zsh => "exec /bin/zsh -f -c " + quote(source)
      case ShellKind.powershell =>
        "powershell.exe -NoLogo -NoProfile -NonInteractive -OutputFormat Text -EncodedCommand " +
          Base64.getEncoder.encodeToString(source.getBytes(UTF_16LE))
