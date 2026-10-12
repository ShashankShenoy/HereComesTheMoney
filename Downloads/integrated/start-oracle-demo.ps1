param([int]$BackendPort = 8091, [int]$FrontendPort = 5174, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$runtime = Join-Path $taskRoot '.runtime'
$credentialsFile = Join-Path $runtime 'oracle-demo.env'
$composeFile = Join-Path $taskRoot 'compose.oracle-demo.yaml'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw 'Docker Desktop is required for the self-contained Oracle demo. Install/start Docker Desktop, then retry.'
}
& docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker is installed but its engine is not running.' }

New-Item -ItemType Directory -Force -Path $runtime | Out-Null
if (-not (Test-Path -LiteralPath $credentialsFile)) {
  & docker volume inspect moneybags-oracle-demo-data *> $null
  if ($LASTEXITCODE -eq 0) {
    throw 'The Oracle demo data volume exists but .runtime/oracle-demo.env is missing. Restore its original credentials before starting; do not create new passwords for existing data.'
  }
  $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
  function New-DemoPassword {
    $bytes = [byte[]]::new(32)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return -join ($bytes | ForEach-Object { $alphabet[([int]$_ % $alphabet.Length)] })
  }
  @(
    "ORACLE_PASSWORD=$(New-DemoPassword)"
    "APP_USER_PASSWORD=$(New-DemoPassword)"
    "BOOTSTRAP_PASSWORD=$(New-DemoPassword)"
  ) | Set-Content -LiteralPath $credentialsFile -Encoding ascii
}
$values = @{}
foreach ($line in Get-Content -LiteralPath $credentialsFile) {
  if ($line -match '^([A-Z_]+)=([A-Za-z0-9]+)$') { $values[$Matches[1]] = $Matches[2] }
}
foreach ($name in @('ORACLE_PASSWORD','APP_USER_PASSWORD','BOOTSTRAP_PASSWORD')) {
  if (-not $values.ContainsKey($name)) { throw "Missing $name in the private Oracle demo credentials file." }
}

& docker compose --env-file $credentialsFile -f $composeFile up -d
if ($LASTEXITCODE -ne 0) { throw 'Oracle container could not start. Check Docker Desktop and image access.' }
$deadline = [datetime]::UtcNow.AddMinutes(12)
Write-Host 'Waiting for the Oracle Free database (the first start can take several minutes)...'
do {
  $health = & docker inspect --format '{{.State.Health.Status}}' moneybags-oracle-demo 2>$null
  if ($health -eq 'healthy') { break }
  if ($health -eq 'unhealthy') { throw 'Oracle container reported unhealthy. Inspect: docker logs moneybags-oracle-demo' }
  Start-Sleep -Seconds 5
} while ([datetime]::UtcNow -lt $deadline)
if ($health -ne 'healthy') { throw 'Oracle was not ready within 12 minutes. Inspect: docker logs moneybags-oracle-demo' }

function Invoke-DemoSql {
  param([string[]]$Commands)
  $sql = @(
    'CONNECT MONEYBAGS/' + $values.APP_USER_PASSWORD + '@//localhost:1521/FREEPDB1'
    'SET DEFINE OFF'
    'SET HEADING OFF'
    'SET FEEDBACK OFF'
    'SET PAGESIZE 0'
    'WHENEVER SQLERROR EXIT FAILURE ROLLBACK'
  ) + $Commands + @('EXIT')
  $output = $sql | & docker exec -i moneybags-oracle-demo sqlplus -s /nolog
  if ($LASTEXITCODE -ne 0) { throw "Oracle SQL installation failed: $($output -join ' ')" }
  if (($output -join "`n") -match '(?m)^(?:SP2-|ORA-|PLS-)') { throw "Oracle SQL installation failed: $($output -join ' ')" }
  return @($output)
}

$marker = Invoke-DemoSql -Commands @("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='MBX_DEMO_INSTALL';")
if (-not (@($marker | Where-Object { "$_".Trim() -eq '1' }).Count)) {
  $existing = Invoke-DemoSql -Commands @("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='M01_IAM_USER';")
  if (-not (@($existing | Where-Object { "$_".Trim() -eq '0' }).Count)) {
    throw 'Oracle schema is partially installed. Inspect its SQL errors before retrying; the first installer must not be rerun over existing objects.'
  }
  $install = @(
    '001-original-oracle.sql',
    '002-statement-extension.sql',
    '003-integration-extension.sql',
    '004-override-approvals.sql',
    '005-permission-catalog.sql',
    '006-audit-immutability.sql',
    '007-assistant-intents.sql',
    '008-assistant-internal-transfer.sql',
    '011-teller-vault-allocation.sql',
    '012-rbi-cash-delivery.sql',
    '013-teller-replenishment.sql',
    '014-simulated-reserve-opening.sql',
    '015-credit-cards.sql',
    '016-customer-loan-apply.sql',
    '018-deposit-interest.sql',
    '019-customer-account-requests.sql'
  )
  foreach ($file in $install) {
    Write-Host "Installing $file"
    [void](Invoke-DemoSql -Commands @("@/opt/moneybags/database/$file"))
  }
  [void](Invoke-DemoSql -Commands @('CREATE TABLE MBX_DEMO_INSTALL (INSTALL_ID NUMBER(1) PRIMARY KEY, INSTALLED_AT TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL);', 'INSERT INTO MBX_DEMO_INSTALL (INSTALL_ID) VALUES (1);', 'COMMIT;'))
}

$env:DB_URL = 'jdbc:oracle:thin:@127.0.0.1:1529/FREEPDB1'
$env:DB_USERNAME = 'MONEYBAGS'
$env:DB_PASSWORD = $values.APP_USER_PASSWORD
$env:BOOTSTRAP_ADMIN = 'true'
$env:BOOTSTRAP_USERNAME = 'admin'
$env:BOOTSTRAP_PASSWORD = $values.BOOTSTRAP_PASSWORD
try {
  & (Join-Path $taskRoot 'start-oracle-alpha.ps1') -BackendPort $BackendPort -FrontendPort $FrontendPort -SkipBuild:$SkipBuild -UseInheritedSettings
  Write-Host 'Demo administrator username: admin'
  Write-Host 'Demo administrator password: stored in .runtime/oracle-demo.env as BOOTSTRAP_PASSWORD'
  Write-Host 'Customer accounts can be created through the demo signup form.'
} finally {
  foreach ($name in @('DB_URL','DB_USERNAME','DB_PASSWORD','BOOTSTRAP_ADMIN','BOOTSTRAP_USERNAME','BOOTSTRAP_PASSWORD')) {
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
  }
}
