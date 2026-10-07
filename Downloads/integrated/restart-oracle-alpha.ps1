param([int]$BackendPort = 8091, [int]$FrontendPort = 5174)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$settings = Join-Path $taskRoot '.env.ps1'
if (Test-Path -LiteralPath $settings) { . $settings }
foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD')) {
  if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
    throw "Oracle setting $name is missing. Set it privately in this PowerShell session before restarting. The running demo was not stopped."
  }
}
$newJar = Join-Path $taskRoot 'backend\target\moneybags-integrated-gl-ledger.jar'
if (-not (Test-Path -LiteralPath $newJar)) {
  throw 'The tested GL ledger JAR is missing. The running demo was not stopped.'
}
& (Join-Path $taskRoot 'stop-oracle-alpha.ps1')
& (Join-Path $taskRoot 'start-oracle-alpha.ps1') -BackendPort $BackendPort -FrontendPort $FrontendPort
