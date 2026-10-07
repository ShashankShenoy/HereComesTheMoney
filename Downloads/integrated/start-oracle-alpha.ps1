param([int]$BackendPort = 8091, [int]$FrontendPort = 5174, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$runtime = Join-Path $taskRoot '.runtime'
$record = Join-Path $runtime 'oracle-alpha-processes.json'
if (Test-Path -LiteralPath $record) { throw 'An Oracle demo run record exists. Run stop-oracle-alpha.ps1 first.' }
$envFile = Join-Path $taskRoot '.env.ps1'
if (-not (Test-Path -LiteralPath $envFile)) {
  throw 'Oracle settings are missing. Copy .env.example.ps1 to .env.ps1 in Downloads/integrated, then edit DB_URL and DB_USERNAME using your SQL Developer connection. The template prompts for your password.'
}
. $envFile
if ([string]::IsNullOrWhiteSpace($env:DB_URL) -or $env:DB_URL -notmatch '^jdbc:oracle:' -or $env:DB_URL.Contains('<')) {
  throw 'Set DB_URL in .env.ps1 to your Oracle JDBC connection URL.'
}
if ([string]::IsNullOrWhiteSpace($env:DB_USERNAME) -or $env:DB_USERNAME.Contains('<') -or [string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
  throw 'Set DB_USERNAME in .env.ps1 and provide the password for that Oracle schema.'
}
if ($env:SPRING_PROFILES_ACTIVE -match '(^|,)\s*local\s*(,|$)') {
  throw 'SPRING_PROFILES_ACTIVE includes local (H2). Remove that environment setting before starting Oracle.'
}
# Basic JDBC host/service and host/SID URLs can be checked before a lengthy build.
if ($env:DB_URL -match '^jdbc:oracle:thin:@(?:\/\/)?(?<dbHost>[^:/()\s]+):(?<dbPort>\d+)[/:]') {
  $oracleNetworkHost = $Matches.dbHost
  $oracleNetworkPort = [int]$Matches.dbPort
  try { [void][System.Net.Dns]::GetHostAddresses($oracleNetworkHost) }
  catch { throw "Oracle hostname cannot be resolved. Connect the database VPN and check the host in .env.ps1. Host: $oracleNetworkHost" }
  $oracleNetworkProbe = [System.Net.Sockets.TcpClient]::new()
  try {
    $oracleConnectionAttempt = $oracleNetworkProbe.ConnectAsync($oracleNetworkHost, $oracleNetworkPort)
    if (-not $oracleConnectionAttempt.Wait(5000) -or -not $oracleNetworkProbe.Connected) { throw 'Connection timed out.' }
  } catch { throw "Oracle port is unreachable. Check the database VPN and listener. Host: $oracleNetworkHost Port: $oracleNetworkPort" }
  finally { $oracleNetworkProbe.Dispose() }
}
foreach ($oracleAppPort in @($BackendPort, $FrontendPort)) {
  if (Get-NetTCPConnection -LocalPort $oracleAppPort -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $oracleAppPort is already in use. Stop the previous application before starting again."
  }
}
& (Join-Path $taskRoot 'build-before-start.ps1') -CheckOnly:$SkipBuild
$jar = Join-Path $taskRoot 'backend\target\moneybags-integrated-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Maven verify finished without creating the backend JAR.' }
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$env:INTERNAL_BANK_CODES = 'MCPB0000002' # Synthetic Demo B bank code; real deployments configure their own.
$env:CUSTOMER_SIGNUP_ENABLED = 'true'
$env:FRONTEND_ORIGINS = "http://localhost:$FrontendPort,http://127.0.0.1:$FrontendPort"
$backendLauncher = $null
$frontend = $null
$backendLauncherStartTicks = $null
$frontendStartTicks = $null
function Save-OracleRunRecord {
  if ($null -eq $backendLauncher) { return }
  $backendProcess = $backendLauncher
  # Windows javapath can launch a separate JVM. Track that child as well as
  # the launcher so a failed startup can stop the actual backend safely.
  $backendChildren = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($backendLauncher.Id) AND Name = 'java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*$jar*" })
  if ($backendChildren.Count -eq 1) {
    $backendChild = Get-Process -Id $backendChildren[0].ProcessId -ErrorAction SilentlyContinue
    if ($backendChild -and $backendChild.StartTime.ToUniversalTime().Ticks -ge $backendLauncherStartTicks) { $backendProcess = $backendChild }
  }
  $backendTicks = $backendLauncherStartTicks
  if ($backendProcess.Id -ne $backendLauncher.Id) { $backendTicks = $backendProcess.StartTime.ToUniversalTime().Ticks }
  $oracleRun = @{
    backend = $backendProcess.Id
    backendStartTicks = $backendTicks.ToString()
    backendLauncher = $backendLauncher.Id
    backendLauncherStartTicks = $backendLauncherStartTicks.ToString()
    backendPort = $BackendPort
    frontendPort = $FrontendPort
  }
  if ($null -ne $frontend) {
    $oracleRun.frontend = $frontend.Id
    $oracleRun.frontendStartTicks = $frontendStartTicks.ToString()
  }
  $oracleRun | ConvertTo-Json | Set-Content -LiteralPath $record
}
try {
$backendLauncher = Start-Process -FilePath 'java.exe' -ArgumentList @(
  '-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp', '-Djava.net.useSystemProxies=true', '-jar', ('"' + $jar + '"'), "--server.port=$BackendPort", '--server.address=127.0.0.1'
) -WindowStyle Hidden -WorkingDirectory $taskRoot -RedirectStandardOutput (Join-Path $runtime 'oracle-alpha-backend.log') -RedirectStandardError (Join-Path $runtime 'oracle-alpha-backend.err.log') -PassThru
$backendLauncherStartTicks = $backendLauncher.StartTime.ToUniversalTime().Ticks
Save-OracleRunRecord
$env:BACKEND_URL = "http://127.0.0.1:$BackendPort"
$env:PORT = "$FrontendPort"
$frontend = Start-Process -FilePath 'node.exe' -ArgumentList @('server.mjs') -WindowStyle Hidden -WorkingDirectory (Join-Path $taskRoot 'frontend\dist') -RedirectStandardOutput (Join-Path $runtime 'oracle-alpha-frontend.log') -RedirectStandardError (Join-Path $runtime 'oracle-alpha-frontend.err.log') -PassThru
$frontendStartTicks = $frontend.StartTime.ToUniversalTime().Ticks
Save-OracleRunRecord
$deadline = [datetime]::UtcNow.AddSeconds(60)
$health = $null
while ([datetime]::UtcNow -lt $deadline) {
  Save-OracleRunRecord
  $backendLauncher.Refresh()
  if ($backendLauncher.HasExited) { throw 'Oracle backend exited during startup. See .runtime/oracle-alpha-backend.log and .runtime/oracle-alpha-backend.err.log.' }
  try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$BackendPort/actuator/health" -TimeoutSec 2
    if ($health.status -eq 'UP') { break }
  } catch { }
  Start-Sleep -Seconds 1
}
if ($health.status -ne 'UP') { throw 'Oracle backend did not become healthy. See .runtime/oracle-alpha-backend.log; then run stop-oracle-alpha.ps1.' }
$frontend.Refresh()
if ($frontend.HasExited) { throw 'Frontend exited. See .runtime/oracle-alpha-frontend.err.log; then run stop-oracle-alpha.ps1.' }
try { $page = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$FrontendPort/" -TimeoutSec 8 }
catch { throw "Frontend did not become healthy: $($_.Exception.Message). See .runtime/oracle-alpha-frontend.log." }
if ($page.StatusCode -ne 200) { throw 'Frontend did not serve the workspace.' }
$backendListener = @(Get-NetTCPConnection -LocalPort $BackendPort -State Listen | Where-Object { $_.LocalAddress -in @('127.0.0.1','::1','0.0.0.0','::') })
$frontendListener = @(Get-NetTCPConnection -LocalPort $FrontendPort -State Listen | Where-Object { $_.LocalAddress -in @('127.0.0.1','::1','0.0.0.0','::') })
if ($backendListener.Count -ne 1 -or $frontendListener.Count -ne 1) { throw 'Could not uniquely identify the demo listener processes.' }
Save-OracleRunRecord
Write-Host "Oracle ALPHA workspace: http://localhost:$FrontendPort"
Write-Host "Backend health: http://127.0.0.1:$BackendPort/actuator/health"
} catch {
  $oracleStartupFailure = $_
  try {
    Save-OracleRunRecord
    & (Join-Path $taskRoot 'stop-oracle-alpha.ps1')
  } catch { Write-Warning 'Automatic cleanup could not finish. Run stop-oracle-alpha.ps1; the process record is retained for inspection.' }
  throw $oracleStartupFailure
}
