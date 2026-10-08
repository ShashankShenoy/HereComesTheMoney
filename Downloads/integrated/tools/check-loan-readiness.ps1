$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$settings = Join-Path $projectRoot '.env.ps1'
if (-not (Test-Path -LiteralPath $settings)) { throw 'Oracle .env.ps1 is missing.' }
. $settings

$driverRoot = Join-Path $env:USERPROFILE '.m2\repository\com\oracle\database\jdbc\ojdbc17'
$driver = Get-ChildItem -LiteralPath $driverRoot -Filter 'ojdbc17-*.jar' -Recurse |
  Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
  Sort-Object FullName -Descending |
  Select-Object -First 1
if (-not $driver) { throw 'Oracle JDBC driver is missing from the Maven cache.' }

java -cp $driver.FullName (Join-Path $PSScriptRoot 'LoanReadinessAudit.java')
if ($LASTEXITCODE -ne 0) { throw "Loan readiness check failed (exit $LASTEXITCODE)." }
