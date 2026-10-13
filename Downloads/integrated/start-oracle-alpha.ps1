param([int]$BackendPort = 8091, [int]$FrontendPort = 5174, [switch]$SkipBuild, [bool]$PaymentSimulationEnabled=$true,
      [switch]$UseInheritedSettings, [string]$CifKeyFile, [string]$MfaKeyFile)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$runtime = Join-Path $taskRoot '.runtime'
$record = Join-Path $runtime 'oracle-alpha-processes.json'
if (Test-Path -LiteralPath $record) { throw 'An Oracle demo run record exists. Run stop-oracle-alpha.ps1 first.' }
$envFile = Join-Path $taskRoot '.env.ps1'
if (-not $UseInheritedSettings -and -not (Test-Path -LiteralPath $envFile)) {
  throw 'Oracle settings are missing. Copy .env.example.ps1 to .env.ps1 in Downloads/integrated, then edit DB_URL and DB_USERNAME using your SQL Developer connection. The template prompts for your password.'
}
if (-not $UseInheritedSettings) { . $envFile }
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
if ($CifKeyFile -and -not (Test-Path -LiteralPath $CifKeyFile)) { throw 'The selected CIF key file is missing.' }
if ($MfaKeyFile -and -not (Test-Path -LiteralPath $MfaKeyFile)) { throw 'The selected MFA key file is missing.' }
$settingNames = @('INTERNAL_BANK_CODES','CUSTOMER_SIGNUP_ENABLED','PAYMENT_SIMULATION_ENABLED')
$previousSettings = @{}
foreach ($name in $settingNames) { $previousSettings[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
  $env:INTERNAL_BANK_CODES = 'MCPB0000002'
  $env:CUSTOMER_SIGNUP_ENABLED = 'true'
  $env:PAYMENT_SIMULATION_ENABLED = $PaymentSimulationEnabled.ToString().ToLowerInvariant()
  & (Join-Path $taskRoot 'start-microservices.ps1') -Database Oracle -GatewayPort $BackendPort -FrontendPort $FrontendPort -SkipBuild:$SkipBuild -CifKeyFile $CifKeyFile -MfaKeyFile $MfaKeyFile
} finally {
  foreach ($name in $settingNames) { [Environment]::SetEnvironmentVariable($name,$previousSettings[$name],'Process') }
}
