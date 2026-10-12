param([int]$BackendPort = 8091, [int]$FrontendPort = 5174, [switch]$SkipBuild,
      [switch]$CheckOnly, [string]$PassphraseFile)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or newer is required for the encrypted ALPHA team bundle.' }
$taskRoot = $PSScriptRoot
. (Join-Path $taskRoot 'tools\alpha-team-crypto.ps1')
$bundlePath = Join-Path $taskRoot 'alpha-team.bundle.json'
if (-not (Test-Path -LiteralPath $bundlePath)) { throw 'The encrypted ALPHA team bundle is missing.' }

if ($PassphraseFile) {
  $passphrase = (Get-Content -LiteralPath $PassphraseFile -Raw).Trim()
} else {
  $securePassphrase = Read-Host 'ALPHA team bundle passphrase' -AsSecureString
  try { $passphrase = [System.Net.NetworkCredential]::new('', $securePassphrase).Password }
  finally { $securePassphrase.Dispose() }
}
if (-not $passphrase) { throw 'The team passphrase is required.' }
$bundle = Get-Content -LiteralPath $bundlePath -Raw | ConvertFrom-Json
$payload = (Unprotect-AlphaTeamPayload $bundle $passphrase) | ConvertFrom-Json
$passphrase = $null
if ($payload.format -ne 'moneybags-alpha-settings-v1' -or
    $payload.dbUrl -notmatch '^jdbc:oracle:' -or
    -not $payload.dbUsername -or -not $payload.dbPassword -or
    -not $payload.demoCredentials) {
  throw 'The decrypted ALPHA team settings are incomplete.'
}
$keys = @{
  'cif.key' = [Convert]::FromBase64String($payload.cifKey)
  'mfa.key' = [Convert]::FromBase64String($payload.mfaKey)
}
foreach ($name in $keys.Keys) {
  $target = Join-Path $taskRoot "runtime\$name"
  if (Test-Path -LiteralPath $target) {
    $current = [IO.File]::ReadAllBytes($target)
    if (-not [Security.Cryptography.CryptographicOperations]::FixedTimeEquals($current, $keys[$name])) {
      throw "Existing runtime/$name does not match the ALPHA bundle. Back up and resolve the installation key mismatch before starting."
    }
  }
}
if ($CheckOnly) {
  Write-Host 'The ALPHA team bundle decrypts and its installation keys match any local copies.'
  return
}

$runtime = Join-Path $taskRoot 'runtime'
$privateRuntime = Join-Path $taskRoot '.runtime'
New-Item -ItemType Directory -Force -Path $runtime, $privateRuntime | Out-Null
foreach ($name in $keys.Keys) {
  $target = Join-Path $runtime $name
  if (-not (Test-Path -LiteralPath $target)) { [IO.File]::WriteAllBytes($target, $keys[$name]) }
}
$demoSecure = ConvertTo-SecureString -String $payload.demoCredentials -AsPlainText -Force
try {
  $demoSecure | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $privateRuntime 'mcp-demo-credentials.dpapi') -NoNewline
} finally { $demoSecure.Dispose() }
if ($payload.staffCredentials) {
  $staffSecure = ConvertTo-SecureString -String $payload.staffCredentials -AsPlainText -Force
  try {
    $staffSecure | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $privateRuntime 'alpha-team-staff-credentials.dpapi') -NoNewline
  } finally { $staffSecure.Dispose() }
}

$names = @('DB_URL','DB_USERNAME','DB_PASSWORD','OPENROUTER_API_KEY','AI_PROVIDER','AI_MODEL','AI_FALLBACK_MODEL','DEPOSIT_INTEREST_ENABLED')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
  $env:DB_URL = $payload.dbUrl
  $env:DB_USERNAME = $payload.dbUsername
  $env:DB_PASSWORD = $payload.dbPassword
  $env:OPENROUTER_API_KEY = $payload.openrouterApiKey
  $env:AI_PROVIDER = $payload.aiProvider
  $env:AI_MODEL = $payload.aiModel
  $env:AI_FALLBACK_MODEL = $payload.aiFallbackModel
  $env:DEPOSIT_INTEREST_ENABLED = $payload.depositInterestEnabled
  & (Join-Path $taskRoot 'start-oracle-alpha.ps1') -BackendPort $BackendPort -FrontendPort $FrontendPort -SkipBuild:$SkipBuild -UseInheritedSettings
  Write-Host 'ALPHA team workspace is ready. Use ./show-alpha-team-credentials.ps1 to display the team demo logins privately.'
} finally {
  foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
