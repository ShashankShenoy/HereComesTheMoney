$ErrorActionPreference = 'Stop'
$record = Join-Path $PSScriptRoot '.runtime\oracle-alpha-processes.json'
if (-not (Test-Path -LiteralPath $record)) { Write-Host 'No Oracle demo run record.'; return }
$run = Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
foreach ($name in @('backend', 'frontend', 'backendLauncher')) {
  if ($null -eq $run.$name) { continue }
  $id = [int]$run.$name
  $process = Get-Process -Id $id -ErrorAction SilentlyContinue
  $expected = [long]$run.($name + 'StartTicks')
  if ($process) {
    if ($process.StartTime.ToUniversalTime().Ticks -ne $expected) { throw "The recorded $name PID now belongs to a different process. Run record retained." }
    Stop-Process -Id $id -Force -ErrorAction Stop
  }
}
Remove-Item -LiteralPath $record
Write-Host 'Stopped the recorded Oracle ALPHA demo processes.'
