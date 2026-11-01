const script = String.raw`$ErrorActionPreference = 'Stop'
$root = 'HKCU:\Control Panel\NotifyIconSettings'
$changed = @()
if (Test-Path $root) {
  foreach ($key in Get-ChildItem $root) {
    $item = Get-ItemProperty $key.PSPath
    if ($item.IsPromoted -ne 1) {
      New-ItemProperty -Path $key.PSPath -Name IsPromoted -PropertyType DWord -Value 1 -Force | Out-Null
      $changed += $item.ExecutablePath
    }
  }
}
$explorer = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer'
$autoTrayChanged = (Get-ItemProperty $explorer).EnableAutoTray -ne 0
if ($autoTrayChanged) {
  New-ItemProperty -Path $explorer -Name EnableAutoTray -PropertyType DWord -Value 0 -Force | Out-Null
}
[pscustomobject]@{
  CheckedAt = (Get-Date -Format o)
  PromotedCount = $changed.Count
  PromotedApps = $changed
  AutoTrayChanged = $autoTrayChanged
} | ConvertTo-Json -Depth 3 -Compress`;
const result = (await tools.powershell({body: script, timeout: "10s"})).body;
if (result.exitCode !== 0) throw Error(result.stderr || "PowerShell exit " + result.exitCode);
const report = JSON.parse(result.stdout);
if (!Number.isInteger(report.PromotedCount) || report.PromotedCount < 0) throw Error("Invalid tray report");
console.log(JSON.stringify(report));
