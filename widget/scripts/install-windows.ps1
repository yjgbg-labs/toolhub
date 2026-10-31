param(
  [Parameter(Mandatory = $true)][string]$SourceDir,
  [Parameter(Mandatory = $true)][string]$BaseURL,
  [bool]$Diagnostics = $false
)
$ErrorActionPreference = 'Stop'
$root = Join-Path $env:LOCALAPPDATA 'GrootWidget'
$configDir = Join-Path $env:APPDATA 'GrootWidget'
$configPath = Join-Path $configDir 'connection.json'
$app = Join-Path $root 'app'
$exe = Join-Path $app 'GrootWidget.exe'
$stage = Join-Path $root ('.install-' + [guid]::NewGuid())
$backup = Join-Path $root ('.backup-' + [guid]::NewGuid())
$uri = [uri]$BaseURL
if ($uri.Scheme -notin @('http', 'https') -or $uri.Query -or $uri.Fragment -or $uri.UserInfo) { throw 'Invalid server URL' }
if (-not (Test-Path (Join-Path $SourceDir 'GrootWidget.exe'))) { throw 'Build the Windows app first' }
$oldConfig = if (Test-Path $configPath) { [IO.File]::ReadAllBytes($configPath) } else { $null }
$oldTask = if (Get-ScheduledTask -TaskName GrootWidget -ErrorAction SilentlyContinue) { Export-ScheduledTask -TaskName GrootWidget } else { $null }
New-Item -ItemType Directory -Force -Path $root,$configDir | Out-Null
Copy-Item $SourceDir $stage -Recurse
$movedOld = $false
$installedNew = $false
try {
  $running = @(Get-Process GrootWidget -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe })
  if ($oldTask) { Stop-ScheduledTask -TaskName GrootWidget }
  foreach ($runningProcess in $running) {
    if (-not $runningProcess.HasExited) { $runningProcess | Stop-Process -Force }
    if (-not $runningProcess.WaitForExit(10000)) { throw 'Old widget did not exit' }
  }
  if (Test-Path $app) {
    for ($attempt = 0; ; $attempt++) {
      try { Move-Item $app $backup; break }
      catch { if ($attempt -ge 9) { throw }; Start-Sleep -Milliseconds 300 }
    }
    $movedOld = $true
  }
  Move-Item $stage $app
  $installedNew = $true
  [IO.File]::WriteAllText($configPath, (@{baseURL=$BaseURL} | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
  $args = if ($Diagnostics) { '--diagnostics' } else { '' }
  $action = if ($args) { New-ScheduledTaskAction -Execute $exe -WorkingDirectory $app -Argument $args } else { New-ScheduledTaskAction -Execute $exe -WorkingDirectory $app }
  $user = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
  $trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
  $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
  $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
  Register-ScheduledTask -TaskName GrootWidget -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
  Start-ScheduledTask -TaskName GrootWidget
  Start-Sleep -Seconds 3
  $process = Get-Process GrootWidget -ErrorAction Stop | Where-Object { $_.Path -eq $exe } | Select-Object -First 1
  if (-not $process -or $process.SessionId -eq 0 -or -not $process.Responding) { throw 'Widget did not start in an interactive desktop session' }
  if (Test-Path $backup) { Remove-Item $backup -Recurse -Force }
  [pscustomobject]@{Installed=$true; Exe=$exe; SessionId=$process.SessionId; Responding=$process.Responding; Server=$BaseURL; Diagnostics=$Diagnostics} | ConvertTo-Json
} catch {
  Stop-ScheduledTask -TaskName GrootWidget -ErrorAction SilentlyContinue
  Get-Process GrootWidget -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe } | Stop-Process -Force
  if ($installedNew) { Remove-Item $app -Recurse -Force -ErrorAction SilentlyContinue }
  if ($movedOld) { Move-Item $backup $app }
  if ($null -ne $oldConfig) { [IO.File]::WriteAllBytes($configPath, $oldConfig) }
  else { Remove-Item $configPath -Force -ErrorAction SilentlyContinue }
  if ($oldTask) { Register-ScheduledTask -TaskName GrootWidget -Xml $oldTask -Force | Out-Null; Start-ScheduledTask -TaskName GrootWidget }
  else { Unregister-ScheduledTask -TaskName GrootWidget -Confirm:$false -ErrorAction SilentlyContinue }
  throw
} finally {
  if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
}
