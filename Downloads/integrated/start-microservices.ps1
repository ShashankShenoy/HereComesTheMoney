param(
  [ValidateSet('Local','Oracle')][string]$Database = 'Local',
  [ValidateRange(1,65535)][int]$GatewayPort = 8090,
  [ValidateRange(1,65535)][int]$IdentityPort = 8092,
  [ValidateRange(1,65535)][int]$ReportingPort = 8093,
  [ValidateRange(1,65535)][int]$BankingPort = 8094,
  [ValidateRange(1,65535)][int]$PaymentsPort = 8095,
  [ValidateRange(1,65535)][int]$LoanPort = 8096,
  [ValidateRange(1,65535)][int]$AuditPort = 8097,
  [ValidateRange(1,65535)][int]$DatabasePort = 9095,
  [ValidateRange(1,65535)][int]$FrontendPort = 5173,
  [ValidateRange(15,600)][int]$StartupTimeoutSeconds = 120,
  [switch]$SkipBuild, [switch]$Offline,
  [string]$CifKeyFile, [string]$MfaKeyFile
)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
. (Join-Path $taskRoot 'tools/service-runtime.ps1')
$runtime = Join-Path $taskRoot '.runtime'
$record = Join-Path $runtime 'microservices-processes.json'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$lockPath = Join-Path $runtime 'microservices-start.lock'
# An OS-held lock serializes concurrent starts and is released even on errors.
try { $startupLock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
catch { throw 'Another service startup or shutdown is in progress.' }
$run = @{database=$Database; frontendUrl="http://localhost:$FrontendPort"; processes=@()}
$environmentNames = @('BACKEND_URL','PORT','GATEWAY_PORT','IDENTITY_URL','REPORTING_URL','BANKING_URL','PAYMENTS_URL','LOAN_URL','AUDIT_URL','LOCAL_DB_URL','FRONTEND_ORIGINS')
$previousEnvironment = @{}
foreach ($name in $environmentNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
function Start-ServiceProcess {
  param([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$Directory, [int]$Port)
  Write-Host "Starting $Name on port $Port"
  $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WindowStyle Hidden -WorkingDirectory $Directory -RedirectStandardOutput (Join-Path $runtime "$Name.log") -RedirectStandardError (Join-Path $runtime "$Name.err.log") -PassThru
  $run.processes += @{name=$Name; id=$process.Id; startTicks=$process.StartTime.ToUniversalTime().Ticks.ToString(); port=$Port}
  Save-ServiceRun $record $run
  return $process
}
function Wait-ServiceHealth {
  param([string]$Name, [System.Diagnostics.Process]$Process, [string]$Url)
  $deadline = [datetime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
  while ([datetime]::UtcNow -lt $deadline) {
    $Process.Refresh()
    if ($Process.HasExited) { throw "$Name exited. See .runtime/$Name.log and $Name.err.log." }
    try { $health = Invoke-RestMethod -Uri $Url -TimeoutSec 3; if ($health.status -eq 'UP') { return } } catch {}
    Start-Sleep -Milliseconds 500
  }
  throw "$Name did not become ready within $StartupTimeoutSeconds seconds. See .runtime/$Name.log."
}
function Start-JavaService {
  param([string]$Module, [int]$Port, [string[]]$ExtraArguments)
  $jar = Join-Path $taskRoot "backend/$Module/target/$Module-1.0.0-exec.jar"
  if (-not (Test-Path -LiteralPath $jar)) { throw "Missing $Module JAR. Run build-before-start.ps1." }
  $arguments = @('-Xms32m','-Xmx512m','-Djava.net.preferIPv4Stack=true','-Djava.net.useSystemProxies=true','-jar',('"'+$jar+'"'),"--server.port=$Port",'--server.address=127.0.0.1',"--moneybags.services.identity-url=http://127.0.0.1:$IdentityPort","--moneybags.services.reporting-url=http://127.0.0.1:$ReportingPort","--moneybags.services.payments-url=http://127.0.0.1:$PaymentsPort") + $ExtraArguments
  Start-ServiceProcess $Module $javaExecutable $arguments $taskRoot $Port
}
try {
  foreach ($oldRecord in @($record, (Join-Path $runtime 'processes.json'), (Join-Path $runtime 'oracle-alpha-processes.json'))) {
    if (Test-Path -LiteralPath $oldRecord) { throw 'A Moneybags run record exists. Stop that run before starting another instance.' }
  }
  $ports = @($GatewayPort,$IdentityPort,$ReportingPort,$BankingPort,$PaymentsPort,$LoanPort,$AuditPort,$FrontendPort)
  if ($Database -eq 'Local') { $ports += $DatabasePort }
  if (@($ports | Select-Object -Unique).Count -ne $ports.Count) { throw 'Each service must use a different port.' }
  foreach ($port in $ports) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $listener.Start() } catch { throw "Port $port is already in use. No services were started." } finally { $listener.Stop() }
  }
  $javaExecutable = Get-ServiceJava
  $nodeExecutable = (Get-Command node -ErrorAction Stop).Source
  if ($Database -eq 'Oracle') {
    if ($env:DB_URL -notmatch '^jdbc:oracle:' -or [string]::IsNullOrWhiteSpace($env:DB_USERNAME) -or [string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) { throw 'Oracle startup requires DB_URL, DB_USERNAME and DB_PASSWORD in the process environment.' }
  }
  foreach ($keyFile in @($CifKeyFile,$MfaKeyFile)) { if ($keyFile -and -not (Test-Path -LiteralPath $keyFile)) { throw "Missing key file: $keyFile" } }
  & (Join-Path $taskRoot 'build-before-start.ps1') -CheckOnly:$SkipBuild -Offline:$Offline
  $env:FRONTEND_ORIGINS = "http://localhost:$FrontendPort,http://127.0.0.1:$FrontendPort"
  $env:IDENTITY_URL = "http://127.0.0.1:$IdentityPort"
  $env:REPORTING_URL = "http://127.0.0.1:$ReportingPort"
  $env:BANKING_URL = "http://127.0.0.1:$BankingPort"
  $env:PAYMENTS_URL = "http://127.0.0.1:$PaymentsPort"
  $env:LOAN_URL = "http://127.0.0.1:$LoanPort"
  $env:AUDIT_URL = "http://127.0.0.1:$AuditPort"
  $env:GATEWAY_PORT = "$GatewayPort"
  $env:BACKEND_URL = "http://127.0.0.1:$GatewayPort"
  $env:PORT = "$FrontendPort"
  $bankingArguments = @('--spring.profiles.active=oracle-microservices','--spring.sql.init.mode=never')
  $clientArguments = @('--spring.profiles.active=oracle-microservices','--spring.sql.init.mode=never')
  if ($Database -eq 'Local') {
    $env:LOCAL_DB_URL = "jdbc:h2:tcp://127.0.0.1:$DatabasePort/mem:moneybags;MODE=Oracle;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH"
    $databaseJar = Join-Path $taskRoot 'backend/local-database/target/local-database-1.0.0-exec.jar'
    $databaseProcess = Start-ServiceProcess 'local-database' $javaExecutable @('-Xms16m','-Xmx256m','-jar',('"'+$databaseJar+'"'),"$DatabasePort") $taskRoot $DatabasePort
    $deadline = [datetime]::UtcNow.AddSeconds(15)
    $databaseReady = $false
    while ([datetime]::UtcNow -lt $deadline) {
      $databaseProcess.Refresh(); if ($databaseProcess.HasExited) { throw 'Local database exited. See .runtime/local-database.err.log.' }
      $probe = [Net.Sockets.TcpClient]::new()
      try { $probe.Connect('127.0.0.1',$DatabasePort); $databaseReady=$true; break } catch { Start-Sleep -Milliseconds 200 } finally { $probe.Dispose() }
    }
    if (-not $databaseReady) { throw 'Local database did not start.' }
    # Only banking initializes and seeds the shared ephemeral database.
    $localDatasourceArguments = @("--spring.datasource.url=$env:LOCAL_DB_URL",'--spring.datasource.username=sa','--spring.datasource.password=','--spring.datasource.driver-class-name=org.h2.Driver')
    $bankingArguments = @('--spring.profiles.active=local,local-microservices','--spring.sql.init.mode=always') + $localDatasourceArguments
    $clientArguments = @('--spring.profiles.active=local-microservices','--spring.sql.init.mode=never') + $localDatasourceArguments
  }
  if ($CifKeyFile) {
    $cifArgument='"--moneybags.cif.key-file='+(Resolve-Path -LiteralPath $CifKeyFile).Path+'"'
    $bankingArguments += $cifArgument
    $clientArguments += $cifArgument
  }
  $identityArguments = $clientArguments
  if ($MfaKeyFile) { $identityArguments += ('"--moneybags.auth.mfa-key-file='+(Resolve-Path -LiteralPath $MfaKeyFile).Path+'"') }
  $banking = Start-JavaService 'banking-service' $BankingPort $bankingArguments
  Wait-ServiceHealth 'banking-service' $banking "http://127.0.0.1:$BankingPort/actuator/health/readiness"
  $identity = Start-JavaService 'identity-service' $IdentityPort $identityArguments
  Wait-ServiceHealth 'identity-service' $identity "http://127.0.0.1:$IdentityPort/actuator/health/readiness"
  foreach($domain in @(@{module='payments-service';port=$PaymentsPort},@{module='loan-service';port=$LoanPort},@{module='audit-service';port=$AuditPort})) {
    $process=Start-JavaService $domain.module $domain.port $clientArguments
    Wait-ServiceHealth $domain.module $process "http://127.0.0.1:$($domain.port)/actuator/health/readiness"
  }
  $reporting = Start-JavaService 'reporting-service' $ReportingPort $clientArguments
  Wait-ServiceHealth 'reporting-service' $reporting "http://127.0.0.1:$ReportingPort/actuator/health/readiness"
  $gateway = Start-ServiceProcess 'gateway' $nodeExecutable @('server.mjs') (Join-Path $taskRoot 'gateway') $GatewayPort
  Wait-ServiceHealth 'gateway' $gateway "http://127.0.0.1:$GatewayPort/actuator/health"
  $frontend = Start-ServiceProcess 'frontend' $nodeExecutable @('server.mjs') (Join-Path $taskRoot 'frontend/dist') $FrontendPort
  Wait-ServiceHealth 'frontend' $frontend "http://127.0.0.1:$FrontendPort/actuator/health"
  $page = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$FrontendPort/" -TimeoutSec 5
  if ($page.StatusCode -ne 200) { throw 'Frontend did not serve the workspace.' }
  foreach ($entry in $run.processes) {
    $process = Get-Process -Id $entry.id -ErrorAction Stop
    if ($process.StartTime.ToUniversalTime().Ticks -ne [long]$entry.startTicks) { throw 'A service exited during startup.' }
  }
  Write-Host "Moneybags microservices ready: http://localhost:$FrontendPort"
  Write-Host "Gateway health: http://127.0.0.1:$GatewayPort/actuator/health"
  Write-Host 'Stop all services with ./stop-microservices.ps1. Logs: .runtime/<service>.log'
} catch {
  $startupFailure = $_
  # A preflight failure must never stop somebody else's recorded run.
  if ($run.processes.Count -gt 0) {
    try { Stop-ServiceRun $record } catch { Write-Warning "Cleanup failed: $($_.Exception.Message)" }
  }
  throw $startupFailure
} finally {
  foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name,$previousEnvironment[$name],'Process') }
  $startupLock.Dispose()
}
