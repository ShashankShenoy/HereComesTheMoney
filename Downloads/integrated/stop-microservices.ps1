$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools/service-runtime.ps1')
$runtime = Join-Path $PSScriptRoot '.runtime'
$record = Join-Path $runtime 'microservices-processes.json'
if (-not (Test-Path -LiteralPath $record)) { Write-Host 'No microservices run record.'; return }
try { $shutdownLock = [IO.File]::Open((Join-Path $runtime 'microservices-start.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
catch { throw 'Service startup or shutdown is already in progress.' }
try { Stop-ServiceRun $record; Write-Host 'Stopped all recorded Moneybags services.' }
finally { $shutdownLock.Dispose() }
