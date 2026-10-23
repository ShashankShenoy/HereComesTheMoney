# Internal Oracle lifecycle helpers; use the three scripts in the integrated folder.
Import-Module (Join-Path $PSScriptRoot 'oracle-build.psm1') -Force

# Shared process ownership and cleanup for the service launchers.
function Save-ServiceRun {
  param([string]$Record, [object]$Run)
  $temporaryRecord = $Record + '.tmp'
  $Run | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $temporaryRecord -Encoding UTF8
  Move-Item -LiteralPath $temporaryRecord -Destination $Record -Force
}

function Stop-ServiceRun {
  param([string]$Record, [object]$Run)
  if (-not (Test-Path -LiteralPath $Record)) { return }
  if ($null -eq $Run) { $Run = Get-Content -LiteralPath $Record -Raw | ConvertFrom-Json }
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

function Test-OracleListener {
  param([string]$DbUrl)
if ($DbUrl -match '^jdbc:oracle:thin:@(?:\/\/)?(?<dbHost>[^:/()\s]+):(?<dbPort>\d+)[/:]') {
  $oracleNetworkHost = $Matches.dbHost
  $oracleNetworkPort = [int]$Matches.dbPort
  try { [void][System.Net.Dns]::GetHostAddresses($oracleNetworkHost) }
  catch { throw "Oracle hostname cannot be resolved. Connect the database VPN and check the host in the ALPHA bundle. Host: $oracleNetworkHost" }
  $oracleNetworkProbe = [System.Net.Sockets.TcpClient]::new()
  try {
    $oracleConnectionAttempt = $oracleNetworkProbe.ConnectAsync($oracleNetworkHost, $oracleNetworkPort)
    if (-not $oracleConnectionAttempt.Wait(5000) -or -not $oracleNetworkProbe.Connected) { throw 'Connection timed out.' }
  } catch { throw "Oracle port is unreachable. Check the database VPN and listener. Host: $oracleNetworkHost Port: $oracleNetworkPort" }
  finally { $oracleNetworkProbe.Dispose() }
}
}

function Start-OracleServices {
param(
  [Parameter(Mandatory)][string]$ProjectRoot,
  [ValidateRange(1,65535)][int]$GatewayPort = 8091,
  [ValidateRange(1,65535)][int]$IdentityPort = 8092,
  [ValidateRange(1,65535)][int]$ReportingPort = 8093,
  [ValidateRange(1,65535)][int]$BankingPort = 8094,
  [ValidateRange(1,65535)][int]$PaymentsPort = 8095,
  [ValidateRange(1,65535)][int]$LoanPort = 8096,
  [ValidateRange(1,65535)][int]$AuditPort = 8097,
  [ValidateRange(1,65535)][int]$FrontendPort = 5174,
  [ValidateRange(15,600)][int]$StartupTimeoutSeconds = 120,
  [switch]$SkipBuild, [switch]$Offline, [switch]$BuildOnly,
  [string]$CifKeyFile, [string]$MfaKeyFile
)
$ErrorActionPreference = 'Stop'
$taskRoot = $ProjectRoot
$runtime = Join-Path $taskRoot '.runtime'
$record = Join-Path $runtime 'microservices-processes.json'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$lockPath = Join-Path $runtime 'microservices-start.lock'
# An OS-held lock serializes concurrent starts and is released even on errors.
try { $startupLock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
catch { throw 'Another service startup or shutdown is in progress.' }
$run = @{database='Oracle'; frontendUrl="http://localhost:$FrontendPort"; processes=@()}
$environmentNames = @('BACKEND_URL','PORT','GATEWAY_PORT','IDENTITY_URL','REPORTING_URL','BANKING_URL','PAYMENTS_URL','LOAN_URL','AUDIT_URL','FRONTEND_ORIGINS')
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
  if (-not (Test-Path -LiteralPath $jar)) { throw "Missing $Module JAR. Run start-alpha-team.ps1 -BuildOnly." }
  $arguments = @('-Xms32m','-Xmx512m','-Djava.net.preferIPv4Stack=true','-Djava.net.useSystemProxies=true','-jar',('"'+$jar+'"'),"--server.port=$Port",'--server.address=127.0.0.1',"--moneybags.services.identity-url=http://127.0.0.1:$IdentityPort","--moneybags.services.reporting-url=http://127.0.0.1:$ReportingPort","--moneybags.services.payments-url=http://127.0.0.1:$PaymentsPort","--moneybags.services.loans-url=http://127.0.0.1:$LoanPort") + $ExtraArguments
  Start-ServiceProcess $Module $javaExecutable $arguments $taskRoot $Port
}
try {
  foreach ($oldRecord in @($record, (Join-Path $runtime 'processes.json'), (Join-Path $runtime 'oracle-alpha-processes.json'))) {
    if (Test-Path -LiteralPath $oldRecord) { throw 'A Moneybags run record exists. Stop that run before starting another instance.' }
  }
  if ($BuildOnly) {
    Invoke-OracleBuild -ProjectRoot $taskRoot -Offline:$Offline
    return
  }
  $ports = @($GatewayPort,$IdentityPort,$ReportingPort,$BankingPort,$PaymentsPort,$LoanPort,$AuditPort,$FrontendPort)
  if (@($ports | Select-Object -Unique).Count -ne $ports.Count) { throw 'Each service must use a different port.' }
  foreach ($port in $ports) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $listener.Start() } catch { throw "Port $port is already in use. No services were started." } finally { $listener.Stop() }
  }
  $javaExecutable = Get-ServiceJava
  $nodeExecutable = (Get-Command node -ErrorAction Stop).Source
  if ($env:DB_URL -notmatch '^jdbc:oracle:' -or $env:DB_URL.Contains('<') -or
      [string]::IsNullOrWhiteSpace($env:DB_USERNAME) -or [string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    throw 'Oracle startup requires DB_URL, DB_USERNAME and DB_PASSWORD in the process environment.'
  }
  if ($env:SPRING_PROFILES_ACTIVE -match '(^|,)\s*local(?:-microservices)?\s*(,|$)') {
    throw 'Remove the local H2 Spring profile before starting Oracle.'
  }
  Test-OracleListener $env:DB_URL
  foreach ($keyFile in @($CifKeyFile,$MfaKeyFile)) { if ($keyFile -and -not (Test-Path -LiteralPath $keyFile)) { throw "Missing key file: $keyFile" } }
  Invoke-OracleBuild -ProjectRoot $taskRoot -CheckOnly:$SkipBuild -Offline:$Offline
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
  $bankingArguments = @('--spring.profiles.active=oracle-microservices','--spring.sql.init.mode=never','--moneybags.demo-funding.enabled=true')
  $clientArguments = @('--spring.profiles.active=oracle-microservices','--spring.sql.init.mode=never')
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
  Write-Host 'Stop all services with ./stop-oracle-alpha.ps1. Logs: .runtime/<service>.log'
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

}

function Stop-OracleServices {
  param([Parameter(Mandatory)][string]$ProjectRoot)
  $ErrorActionPreference = 'Stop'
  $runtime = Join-Path $ProjectRoot '.runtime'
  $records = @('microservices-processes.json', 'oracle-alpha-processes.json', 'processes.json')
  if (-not @($records | Where-Object { Test-Path -LiteralPath (Join-Path $runtime $_) }).Count) {
    Write-Host 'No recorded Moneybags services are running.'
    return
  }
  try { $shutdownLock = [IO.File]::Open((Join-Path $runtime 'microservices-start.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
  catch { throw 'Service startup or shutdown is already in progress.' }
  try {
    foreach ($name in $records) {
      $record = Join-Path $runtime $name
      if (-not (Test-Path -LiteralPath $record)) { continue }
      if ($name -ne 'microservices-processes.json') {
        # Read older launcher records so a previously running app can still stop.
        $legacy = Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
        $entries = @(foreach ($component in @('backend', 'frontend', 'backendLauncher')) {
          if ($null -eq $legacy.$component) { continue }
          $ticks = $legacy.($component + 'StartTicks')
          if (-not $ticks -and $legacy.($component + 'Start')) {
            $ticks = ([datetime]$legacy.($component + 'Start')).ToUniversalTime().Ticks
          }
          if (-not $ticks) { throw 'An older run record has no process creation time. Record retained.' }
          @{name=$component; id=[int]$legacy.$component; startTicks=$ticks.ToString()}
        })
        Stop-ServiceRun $record @{processes=$entries}
        continue
      }
      Stop-ServiceRun $record
    }
    Write-Host 'Stopped all recorded Moneybags application services.'
  } finally { $shutdownLock.Dispose() }
}

Export-ModuleMember -Function Start-OracleServices, Stop-OracleServices, Invoke-OracleBuild
