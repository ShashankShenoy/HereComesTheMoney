$ErrorActionPreference = 'Stop'
$taskProject = Join-Path (Split-Path -Parent $PSScriptRoot) 'Downloads/integrated'
$taskConfigFile = Join-Path $taskProject '.runtime/oracle-m12/settings.dpapi'
if (-not (Test-Path -LiteralPath $taskConfigFile)) { throw 'Oracle test settings have not been unlocked.' }
$taskSecureSettings = Get-Content -LiteralPath $taskConfigFile -Raw | ConvertTo-SecureString
try {
    $taskOracleSettings = [Net.NetworkCredential]::new('', $taskSecureSettings).Password | ConvertFrom-Json
} finally { $taskSecureSettings.Dispose() }
if ($taskOracleSettings.dbUrl -notlike 'jdbc:oracle:*') { throw 'Oracle-only test guard rejected the configuration.' }
$env:DB_URL = $taskOracleSettings.dbUrl
$env:DB_USERNAME = $taskOracleSettings.dbUsername
$env:DB_PASSWORD = $taskOracleSettings.dbPassword
$env:MONEYBAGS_ORACLE_M12_TEST = 'true'
$env:BOOTSTRAP_ADMIN = 'false'
$env:DEPOSIT_INTEREST_ENABLED = 'false'
$env:KAFKA_ENABLED = 'false'
$env:OPENAI_API_KEY = $null
$env:OPENROUTER_API_KEY = $null
$taskKeyRoot = Join-Path $taskProject '.runtime/alpha-keys'
foreach ($taskKind in @('cif','mfa')) {
    $taskKeyPath = Join-Path $taskKeyRoot ($taskKind + '.key')
    $taskExpectedKey = [Convert]::FromBase64String($taskOracleSettings.($taskKind + 'Key'))
    if (-not (Test-Path -LiteralPath $taskKeyPath)) { throw 'The installed ALPHA encryption key is missing.' }
    if (-not [Security.Cryptography.CryptographicOperations]::FixedTimeEquals([IO.File]::ReadAllBytes($taskKeyPath), $taskExpectedKey)) { throw 'Installed ALPHA encryption key does not match the bundle.' }
    [Array]::Clear($taskExpectedKey, 0, $taskExpectedKey.Length)
}
$env:M12_CIF_KEY_FILE = Join-Path $taskKeyRoot 'cif.key'
$env:M12_MFA_KEY_FILE = Join-Path $taskKeyRoot 'mfa.key'
$env:M12_DOCUMENT_ROOT = Join-Path $taskProject '.runtime/oracle-m12/evidence'
