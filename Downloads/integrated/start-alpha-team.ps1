param([int]$BackendPort = 8091, [int]$FrontendPort = 5174, [switch]$SkipBuild,
      [switch]$CheckOnly, [switch]$UnlockOnly, [string]$PassphraseFile)
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
    -not $payload.demoCredentials -or -not $payload.staffCredentials) {
  throw 'The decrypted ALPHA team settings are incomplete.'
}
$keys = @{
  'cif.key' = [Convert]::FromBase64String($payload.cifKey)
  'mfa.key' = [Convert]::FromBase64String($payload.mfaKey)
}
$alphaKeys = Join-Path $taskRoot '.runtime\alpha-keys'
foreach ($name in $keys.Keys) {
  $target = Join-Path $alphaKeys $name
  if (Test-Path -LiteralPath $target) {
    $current = [IO.File]::ReadAllBytes($target)
    if (-not [Security.Cryptography.CryptographicOperations]::FixedTimeEquals($current, $keys[$name])) {
      throw "Existing ALPHA key .runtime/alpha-keys/$name does not match the team bundle. Back up and resolve that ALPHA key mismatch before starting."
    }
  }
}
if ($CheckOnly) {
  Write-Host 'The ALPHA team bundle decrypts and its dedicated keys match any local ALPHA copies.'
  return
}

$privateRuntime = Join-Path $taskRoot '.runtime'
New-Item -ItemType Directory -Force -Path $alphaKeys, $privateRuntime | Out-Null
$openRouterKey = $payload.openrouterApiKey
$openRouterOverride = Join-Path $privateRuntime 'openrouter-api-key.dpapi'
if (Test-Path -LiteralPath $openRouterOverride) {
  $protectedOpenRouterKey = Get-Content -LiteralPath $openRouterOverride -Raw | ConvertTo-SecureString
  try { $openRouterKey = [System.Net.NetworkCredential]::new('', $protectedOpenRouterKey).Password }
  finally { $protectedOpenRouterKey.Dispose() }
  if ([string]::IsNullOrWhiteSpace($openRouterKey)) { throw 'The Windows-protected OpenRouter key override is empty.' }
}
foreach ($name in $keys.Keys) {
  $target = Join-Path $alphaKeys $name
  if (-not (Test-Path -LiteralPath $target)) { [IO.File]::WriteAllBytes($target, $keys[$name]) }
}
$rows = [System.Collections.Generic.List[string]]::new()
foreach ($line in ($payload.staffCredentials -split "`n")) {
  $parts = $line.TrimEnd("`r") -split "`t"
  if ($parts.Count -ne 3) { throw 'Invalid staff credential entry in the team bundle.' }
  $rows.Add("$($parts[0])`t$($parts[1])`t$($parts[2])`t")
}
$mcpRoles = @{
  mcp_demo_customer_a = 'MCP_DEMO_CUSTOMER'
  mcp_demo_customer_b = 'MCP_DEMO_CUSTOMER'
  mcp_demo_officer = 'MCP_DEMO_OFFICER'
  mcp_demo_checker = 'MCP_DEMO_CHECKER'
}
foreach ($line in ($payload.demoCredentials -split "`n")) {
  $parts = $line.TrimEnd("`r") -split "`t"
  if ($parts.Count -lt 2 -or -not $mcpRoles.ContainsKey($parts[0])) { throw 'Invalid MCP credential entry in the team bundle.' }
  $accessKey = if ($parts.Count -ge 3) { $parts[2] } else { '' }
  $rows.Add("$($parts[0])`t$($mcpRoles[$parts[0]])`t$($parts[1])`t$accessKey")
}
if ($rows.Count -ne 7) { throw 'The team bundle must contain seven demo logins.' }
$allSecure = ConvertTo-SecureString -String ($rows -join "`n") -AsPlainText -Force
try {
  $allSecure | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $privateRuntime 'alpha-team-credentials.dpapi') -NoNewline
} finally { $allSecure.Dispose() }
if ($UnlockOnly) {
  Write-Host 'All seven demo logins are unlocked in .runtime/alpha-team-credentials.dpapi.'
  return
}

$names = @('DB_URL','DB_USERNAME','DB_PASSWORD','OPENROUTER_API_KEY','AI_PROVIDER','AI_MODEL','AI_FALLBACK_MODEL','DEPOSIT_INTEREST_ENABLED')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
  $env:DB_URL = $payload.dbUrl
  $env:DB_USERNAME = $payload.dbUsername
  $env:DB_PASSWORD = $payload.dbPassword
  $env:OPENROUTER_API_KEY = $openRouterKey
  $env:AI_PROVIDER = $payload.aiProvider
  $env:AI_MODEL = $payload.aiModel
  $env:AI_FALLBACK_MODEL = $payload.aiFallbackModel
  $env:DEPOSIT_INTEREST_ENABLED = $payload.depositInterestEnabled
  & (Join-Path $taskRoot 'start-oracle-alpha.ps1') -BackendPort $BackendPort -FrontendPort $FrontendPort -SkipBuild:$SkipBuild -UseInheritedSettings -CifKeyFile (Join-Path $alphaKeys 'cif.key') -MfaKeyFile (Join-Path $alphaKeys 'mfa.key')
  Write-Host 'ALPHA team workspace is ready. Use ./show-alpha-team-credentials.ps1 to display the team demo logins privately.'
} finally {
  foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
  $openRouterKey = $null
}
