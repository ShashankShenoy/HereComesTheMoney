#requires -Version 7.0
param(
    [string]$OracleEnvFile,
    [string]$DependencyDirectory
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if (-not $OracleEnvFile) { $OracleEnvFile = Join-Path $repoRoot '.env' }
if (-not $DependencyDirectory) {
    $DependencyDirectory = Join-Path $repoRoot '.offline-compile\BOOT-INF\lib'
    if (-not (Test-Path -LiteralPath $DependencyDirectory)) {
        $DependencyDirectory = Join-Path $PSScriptRoot 'backend\target\dependency'
    }
}
if (-not (Test-Path -LiteralPath $DependencyDirectory)) {
    throw 'Dependency JARs are missing. See docs/FINANCIAL-AUDIT-TESTING.txt for the Maven dependency command.'
}
if (-not (Get-ChildItem -LiteralPath $DependencyDirectory -Filter 'ojdbc*.jar')) {
    throw 'The dependency directory must include the Oracle JDBC driver.'
}
Get-Command javac,java -ErrorAction Stop | Out-Null
$names = @('DB_URL','DB_USERNAME','DB_PASSWORD')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    # Parse only the three database assignments; never execute the .env file.
    if (Test-Path -LiteralPath $OracleEnvFile) {
        foreach ($line in Get-Content -LiteralPath $OracleEnvFile) {
            if ($line -match '^\s*(?:\$env:)?(DB_URL|DB_USERNAME|DB_PASSWORD)\s*=\s*([''"])(.*?)\2\s*$') {
                [Environment]::SetEnvironmentVariable($Matches[1],$Matches[3],'Process')
            }
        }
    }
    if ($env:DB_URL -notlike 'jdbc:oracle:*' -or -not $env:DB_USERNAME -or -not $env:DB_PASSWORD) {
        throw 'Configure DB_URL, DB_USERNAME and DB_PASSWORD for Oracle. Credentials are never printed.'
    }
    $runtime = Join-Path $PSScriptRoot '.runtime\financial-audit-test'
    $classes = Join-Path $runtime 'classes'
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    $sourceList = Join-Path $runtime 'sources.txt'
    $production = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'backend\src\main\java') -Recurse -Filter '*.java'
    $test = Join-Path $PSScriptRoot 'backend\src\test\java\com\moneybags\privacy\audit\FinancialAuditOracleSmoke.java'
    $sources = @($production.FullName) + @($test)
    $sources | ForEach-Object { '"'+($_ -replace '\\','/')+'"' } | Set-Content -LiteralPath $sourceList -Encoding utf8
    $jars = Join-Path ([IO.Path]::GetFullPath($DependencyDirectory)) '*'
    Write-Host 'Compiling all production sources and the focused Oracle audit test...'
    & javac --release 17 -parameters -cp $jars -d $classes ('@'+$sourceList)
    if ($LASTEXITCODE -ne 0) { throw 'Backend compilation failed; the Oracle test was not run.' }
    Write-Host 'Testing existing synthetic accounts in a rollback-only Oracle transaction...'
    & java -cp ($classes+';'+$jars) com.moneybags.privacy.audit.FinancialAuditOracleSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Oracle financial audit acceptance test failed. Review the assertion above.' }
    Write-Host 'Oracle financial audit acceptance test passed. Test rows, roles, sessions and balances were rolled back.'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
