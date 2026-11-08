package groot.shell

import java.nio.charset.StandardCharsets.{UTF_8, UTF_16LE}
import java.util.Base64

/** Platform-specific syntax and framing only; connection and execution lifecycle live in ShellExecutor. */
private[shell] object ShellScripts:
  def quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

  def payload(kind: ShellKind, script: String): Array[Byte] =
    val source = script.stripPrefix("\uFEFF") + "\n"
    (if kind == ShellKind.powershell then "\uFEFF" + source else source).getBytes(UTF_8)

  def command(kind: ShellKind, source: String): String = kind match
    case ShellKind.bash => "exec /usr/bin/env -u BASH_ENV /bin/bash --noprofile --norc -c " + quote(source)
    case ShellKind.zsh => "exec /bin/zsh -f -c " + quote(source)
    case ShellKind.powershell =>
      "powershell.exe -NoLogo -NoProfile -NonInteractive -OutputFormat Text -EncodedCommand " +
        Base64.getEncoder.encodeToString(source.getBytes(UTF_16LE))

  def bootstrap(kind: ShellKind, length: Int, token: String): String =
    val script = kind match
      case ShellKind.powershell => powershellBootstrap
      case _ => unixBootstrap
        .replace("__DIRECTORY__", unixDirectory(kind))
        .replace("__READ__", if kind == ShellKind.bash then
          "/bin/dd bs=65536 iflag=count_bytes,fullblock count=\"$length\" of=\"$directory/script\" status=none"
        else "/bin/dd bs=1 count=$length of=\"$directory/script\" 2>/dev/null")
        .replace("__EXECUTE__", if kind == ShellKind.bash then "/bin/bash --noprofile --norc" else "/bin/zsh -f")
    script.replace("__LENGTH__", length.toString).replace("__TOKEN__", token)

  def cancellation(kind: ShellKind, token: String): String =
    val script = kind match
      case ShellKind.powershell => powershellCancellation
      case _ => unixCancellation.replace("__DIRECTORY__", unixDirectory(kind))
    script.replace("__TOKEN__", token)

  private def unixDirectory(kind: ShellKind): String =
    if kind == ShellKind.bash then "/tmp/groot-shell-bash-$token"
    else "$(/usr/bin/getconf DARWIN_USER_TEMP_DIR)/groot-shell-zsh-$token"

  // sshd gives each non-PTY command its own session. Keep the leader alive, publish its identity
  // atomically and frame stdin by byte count rather than relying on EOF from SSH.
  private val unixBootstrap = """
    umask 077
    token=__TOKEN__
    length=__LENGTH__
    directory=__DIRECTORY__
    /bin/mkdir -p "$directory" || exit 1
    pgid=$(/bin/ps -p $$ -o pgid=)
    if [[ ${pgid//[[:space:]]/} != $$ ]]; then
      printf '%s\n' 'SSH session does not own an isolated process group' >&2
      /bin/rm -rf "$directory"
      exit 1
    fi
    trap '/bin/rm -rf "$directory"' EXIT
    trap 'kill -KILL -- -$$' HUP INT TERM
    [[ -f "$directory/cancel" ]] && exit 125
    started=$(LC_ALL=C /bin/ps -p $$ -o lstart=)
    printf '%s\n%s\n' "$$" "$started" > "$directory/process.stage"
    /bin/mv "$directory/process.stage" "$directory/process" || exit 1
    [[ -f "$directory/cancel" ]] && exit 125
    __READ__ || exit 1
    if [[ $(/usr/bin/wc -c < "$directory/script") -ne $length ]]; then
      printf '%s\n' 'Unexpected end of Shell script input' >&2
      exit 1
    fi
    [[ -f "$directory/cancel" ]] && exit 125
    __EXECUTE__ "$directory/script" < /dev/null
    code=$?
    exit $code
    """

  // A marker handles cancellation before PID publication; verify start time, token and group
  // ownership before signalling so a recycled PID cannot select an unrelated process.
  private val unixCancellation = """
    umask 077
    token=__TOKEN__
    directory=__DIRECTORY__
    /bin/mkdir -p "$directory" || exit 1
    : > "$directory/cancel"
    if [[ -f "$directory/process" ]]; then
      { IFS= read -r pid; IFS= read -r started; } < "$directory/process"
      case "$pid" in ''|*[!0-9]*) exit 1 ;; esac
      [[ $pid -gt 1 ]] || exit 1
      current=$(LC_ALL=C /bin/ps -p "$pid" -o lstart=)
      pgid=$(/bin/ps -p "$pid" -o pgid=)
      args=$(/bin/ps -ww -p "$pid" -o command=)
      if [[ "$current" == "$started" && ${pgid//[[:space:]]/} == $pid && "$args" == *"$token"* ]]; then
        kill -TERM -- -$pid 2>/dev/null || true
        /bin/sleep 0.2
        kill -KILL -- -$pid 2>/dev/null || true
      fi
      /bin/rm -rf "$directory"
    fi
    """

  // Windows OpenSSH may leave stdin open. UTF-8 BOM plus an exact byte count preserves Unicode
  // in Windows PowerShell 5.1 without putting the user script in command-line arguments.
  private val powershellBootstrap = """
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $OutputEncoding = [Console]::OutputEncoding
    $directory = Join-Path ([IO.Path]::GetTempPath()) 'groot-shell-powershell-__TOKEN__'
    $path = Join-Path $directory 'script.ps1'
    $pidPath = Join-Path $directory 'process.json'
    $cancelPath = Join-Path $directory 'cancel'
    $code = 1
    try {
      [IO.Directory]::CreateDirectory($directory) | Out-Null
      if (Test-Path $cancelPath) { throw 'PowerShell request cancelled' }
      $identity = @{pid=$PID; started=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks.ToString()} | ConvertTo-Json -Compress
      [IO.File]::WriteAllText($pidPath + '.stage', $identity)
      [IO.File]::Move($pidPath + '.stage', $pidPath)
      if (Test-Path $cancelPath) { throw 'PowerShell request cancelled' }
      $inputStream = [Console]::OpenStandardInput()
      $bytes = New-Object byte[] __LENGTH__
      $offset = 0
      while ($offset -lt $bytes.Length) {
        $read = $inputStream.Read($bytes, $offset, $bytes.Length - $offset)
        if ($read -eq 0) { throw 'Unexpected end of PowerShell script input' }
        $offset += $read
      }
      [IO.File]::WriteAllBytes($path, $bytes)
      if (Test-Path $cancelPath) { throw 'PowerShell request cancelled' }
      $global:LASTEXITCODE = 0
      & $path
      $ok = $?
      if ($LASTEXITCODE -ne 0) { $code = $LASTEXITCODE } elseif ($ok) { $code = 0 }
    } catch {
      [Console]::Error.WriteLine($_.ToString())
      $code = 1
    } finally {
      Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue
    }
    exit $code
    """

  private val powershellCancellation = """
    $ErrorActionPreference = 'Stop'
    $directory = Join-Path ([IO.Path]::GetTempPath()) 'groot-shell-powershell-__TOKEN__'
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    [IO.File]::WriteAllText((Join-Path $directory 'cancel'), '')
    $pidPath = Join-Path $directory 'process.json'
    if (Test-Path $pidPath) {
      $identity = Get-Content -Raw $pidPath | ConvertFrom-Json
      $process = Get-Process -Id ([int]$identity.pid) -ErrorAction SilentlyContinue
      if ($null -ne $process -and $process.StartTime.ToUniversalTime().Ticks.ToString() -eq $identity.started) {
        & "$env:SystemRoot\System32\taskkill.exe" /PID $identity.pid /T /F | Out-Null
        if ($LASTEXITCODE -ne 0 -and -not $process.HasExited) { throw 'Cannot stop remote PowerShell process tree' }
      }
      Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue
    }
    """
