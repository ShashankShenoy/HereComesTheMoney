param([int]$BackendPort = 8091, [int]$FrontendPort = 5174)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$runtime = Join-Path $taskRoot '.runtime'
$record = Join-Path $runtime 'oracle-alpha-processes.json'
if (Test-Path -LiteralPath $record) { throw 'An Oracle demo run record exists. Run stop-oracle-alpha.ps1 first.' }
$jar = Join-Path $taskRoot 'backend\target\moneybags-integrated-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend JAR first: mvn -f backend/pom.xml verify' }
if (-not (Test-Path -LiteralPath (Join-Path $taskRoot 'frontend\node_modules'))) { throw 'Install frontend dependencies first: npm --prefix frontend ci' }
if (-not (Test-Path -LiteralPath (Join-Path $taskRoot '.env.ps1'))) { throw 'The ignored .env.ps1 Oracle settings are missing.' }
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
. (Join-Path $taskRoot '.env.ps1')
$env:INTERNAL_BANK_CODES = 'MCPB0000002' # Synthetic Demo B bank code; real deployments configure their own.
$env:CUSTOMER_SIGNUP_ENABLED = 'true'
$env:FRONTEND_ORIGINS = "http://localhost:$FrontendPort,http://127.0.0.1:$FrontendPort"
$backend = Start-Process -FilePath 'java.exe' -ArgumentList @(
  '-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp', '-Djava.net.useSystemProxies=true', '-jar', ('"' + $jar + '"'), "--server.port=$BackendPort", '--server.address=127.0.0.1'
) -WindowStyle Hidden -WorkingDirectory $taskRoot -RedirectStandardOutput (Join-Path $runtime 'oracle-alpha-backend.log') -RedirectStandardError (Join-Path $runtime 'oracle-alpha-backend.err.log') -PassThru
$env:BACKEND_URL = "http://127.0.0.1:$BackendPort"
$env:PORT = "$FrontendPort"
$frontend = Start-Process -FilePath 'node.exe' -ArgumentList @('server.mjs') -WindowStyle Hidden -WorkingDirectory (Join-Path $taskRoot 'frontend') -RedirectStandardOutput (Join-Path $runtime 'oracle-alpha-frontend.log') -RedirectStandardError (Join-Path $runtime 'oracle-alpha-frontend.err.log') -PassThru
$deadline = [datetime]::UtcNow.AddSeconds(60)
while ([datetime]::UtcNow -lt $deadline) {
  try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$BackendPort/actuator/health" -TimeoutSec 2
    if ($health.status -eq 'UP') { break }
  } catch { }
  Start-Sleep -Seconds 1
}
if ($health.status -ne 'UP') { throw 'Oracle backend did not become healthy. See .runtime/oracle-alpha-backend.log; then run stop-oracle-alpha.ps1.' }
$frontend.Refresh()
if ($frontend.HasExited) { throw 'Frontend exited. See .runtime/oracle-alpha-frontend.err.log; then run stop-oracle-alpha.ps1.' }
try { $page = Invoke-WebRequest -Uri "http://127.0.0.1:$FrontendPort/" -TimeoutSec 8 }
catch { throw 'Frontend did not become healthy. See .runtime/oracle-alpha-frontend.log.' }
if ($page.StatusCode -ne 200) { throw 'Frontend did not serve the workspace.' }
$backendListener = @(Get-NetTCPConnection -LocalPort $BackendPort -State Listen | Where-Object { $_.LocalAddress -in @('127.0.0.1','::1','0.0.0.0','::') })
$frontendListener = @(Get-NetTCPConnection -LocalPort $FrontendPort -State Listen | Where-Object { $_.LocalAddress -in @('127.0.0.1','::1','0.0.0.0','::') })
if ($backendListener.Count -ne 1 -or $frontendListener.Count -ne 1) { throw 'Could not uniquely identify the demo listener processes.' }
$backendProcess = Get-Process -Id $backendListener[0].OwningProcess
$frontendProcess = Get-Process -Id $frontendListener[0].OwningProcess
@{
  backend = $backendProcess.Id
  frontend = $frontendProcess.Id
  backendStartTicks = $backendProcess.StartTime.ToUniversalTime().Ticks.ToString()
  frontendStartTicks = $frontendProcess.StartTime.ToUniversalTime().Ticks.ToString()
  backendPort = $BackendPort
  frontendPort = $FrontendPort
} | ConvertTo-Json | Set-Content -LiteralPath $record
Write-Host "Oracle ALPHA workspace: http://localhost:$FrontendPort"
Write-Host "Backend health: http://127.0.0.1:$BackendPort/actuator/health"
