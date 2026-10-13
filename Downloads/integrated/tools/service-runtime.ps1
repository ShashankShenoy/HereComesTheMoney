# Shared process ownership and cleanup for the service launchers.
function Save-ServiceRun {
  param([string]$Record, [object]$Run)
  $temporaryRecord = $Record + '.tmp'
  $Run | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $temporaryRecord -Encoding UTF8
  Move-Item -LiteralPath $temporaryRecord -Destination $Record -Force
}

function Stop-ServiceRun {
  param([string]$Record)
  if (-not (Test-Path -LiteralPath $Record)) { return }
  $run = Get-Content -LiteralPath $Record -Raw | ConvertFrom-Json
  $entries = @($run.processes)
  [array]::Reverse($entries)
  foreach ($entry in $entries) {
    $process = Get-Process -Id ([int]$entry.id) -ErrorAction SilentlyContinue
    if ($null -eq $process) { continue }
    if ($process.StartTime.ToUniversalTime().Ticks -ne [long]$entry.startTicks) {
      throw "PID $($entry.id) for $($entry.name) belongs to another process. Run record retained."
    }
    Stop-Process -Id $process.Id -Force -ErrorAction Stop
    if (-not $process.WaitForExit(10000)) { throw "Could not stop $($entry.name). Run record retained." }
  }
  Remove-Item -LiteralPath $Record
}

function Get-ServiceJava {
  $command = (Get-Command java -ErrorAction Stop).Source
  $previousPreference = $ErrorActionPreference
  try { $ErrorActionPreference = 'Continue'; $settings = & $command -XshowSettings:properties -version 2>&1 }
  finally { $ErrorActionPreference = $previousPreference }
  if ($LASTEXITCODE -ne 0) { throw 'Java could not run. Install JDK 17 or newer.' }
  $javaHomeLine = $settings | ForEach-Object { "$_" } | Where-Object { $_ -match '^\s*java.home\s*=' } | Select-Object -First 1
  if (-not $javaHomeLine) { throw 'Could not identify the Java installation.' }
  $javaHomePath = ($javaHomeLine -split '=', 2)[1].Trim()
  $executable = Join-Path $javaHomePath 'bin/java.exe'
  if (-not (Test-Path -LiteralPath $executable)) { throw 'The Java executable is missing from java.home.' }
  return $executable
}
