param([switch]$ReplaceBundle, [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or newer is required.' }
$taskRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'alpha-team-crypto.ps1')
$bundlePath = Join-Path $taskRoot 'alpha-team.bundle.json'
$passphrasePath = Join-Path $taskRoot '.runtime\alpha-team-passphrase.txt'
if ((Test-Path -LiteralPath $bundlePath) -and -not $ReplaceBundle -and -not $CheckOnly) {
  throw 'The bundle exists. Use -ReplaceBundle only when intentionally rotating the shared bundle and passphrase.'
}
if (-not (Test-Path -LiteralPath (Join-Path $taskRoot '.env.ps1'))) {
  throw 'This workstation does not have the existing ALPHA .env.ps1 settings.'
}
foreach ($relative in @('runtime\cif.key','runtime\mfa.key')) {
  if (-not (Test-Path -LiteralPath (Join-Path $taskRoot $relative))) { throw "Missing ALPHA installation file: $relative" }
}
Push-Location $taskRoot
try {
  . (Join-Path $taskRoot '.env.ps1')
  if (-not $env:DB_URL -or -not $env:DB_USERNAME -or -not $env:DB_PASSWORD) {
    throw 'The ALPHA connection is incomplete.'
  }
  $unifiedPath = Join-Path $taskRoot '.runtime\alpha-team-credentials.dpapi'
  if (Test-Path -LiteralPath $unifiedPath) {
    $protectedAll = Get-Content -LiteralPath $unifiedPath -Raw | ConvertTo-SecureString
    $allCredentials = [System.Net.NetworkCredential]::new('', $protectedAll).Password
    $demoRows = [System.Collections.Generic.List[string]]::new()
    $staffRows = [System.Collections.Generic.List[string]]::new()
    foreach ($line in ($allCredentials -split "`n")) {
      $parts = $line.TrimEnd("`r") -split "`t"
      if ($parts.Count -ne 4) { throw 'Invalid unified credential entry.' }
      if ($parts[0] -like 'mcp_demo_*') {
        $demoRows.Add("$($parts[0])`t$($parts[2])`t$($parts[3])")
      } else {
        $staffRows.Add("$($parts[0])`t$($parts[1])`t$($parts[2])")
      }
    }
    if ($demoRows.Count -ne 4 -or $staffRows.Count -ne 3) { throw 'Expected four MCP and three staff credentials.' }
    $demoCredentials = $demoRows -join "`n"
    $staffCredentials = $staffRows -join "`n"
  } else {
    $demoPath = Join-Path $taskRoot '.runtime\mcp-demo-credentials.dpapi'
    $staffPath = Join-Path $taskRoot '.runtime\alpha-team-staff-credentials.dpapi'
    if (-not (Test-Path -LiteralPath $demoPath) -or -not (Test-Path -LiteralPath $staffPath)) {
      throw 'The complete local demo credentials are missing. Unlock the current bundle first.'
    }
    $protectedDemo = Get-Content -LiteralPath $demoPath -Raw | ConvertTo-SecureString
    $demoCredentials = [System.Net.NetworkCredential]::new('', $protectedDemo).Password
    $protectedStaff = Get-Content -LiteralPath $staffPath -Raw | ConvertTo-SecureString
    $staffCredentials = [System.Net.NetworkCredential]::new('', $protectedStaff).Password
  }
  $payload = [ordered]@{
    format = 'moneybags-alpha-settings-v1'
    dbUrl = $env:DB_URL
    dbUsername = $env:DB_USERNAME
    dbPassword = $env:DB_PASSWORD
    openrouterApiKey = $env:OPENROUTER_API_KEY
    aiProvider = $env:AI_PROVIDER
    aiModel = $env:AI_MODEL
    aiFallbackModel = $env:AI_FALLBACK_MODEL
    depositInterestEnabled = $env:DEPOSIT_INTEREST_ENABLED
    cifKey = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $taskRoot 'runtime\cif.key')))
    mfaKey = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $taskRoot 'runtime\mfa.key')))
    demoCredentials = $demoCredentials
    staffCredentials = $staffCredentials
  }
  if ($CheckOnly) {
    Write-Host 'The unified credential file contains four MCP and three staff logins; bundle export is ready.'
    return
  }
  $passphrase = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
  $bundle = Protect-AlphaTeamPayload ($payload | ConvertTo-Json -Depth 4 -Compress) $passphrase
  $bundle | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $bundlePath -Encoding utf8
  $passphrase | Set-Content -LiteralPath $passphrasePath -Encoding ascii
  Write-Host 'Encrypted ALPHA bundle created. The passphrase is stored only in .runtime/alpha-team-passphrase.txt.'
  Write-Host 'Share that passphrase with teammates through a separate trusted channel. Do not commit it.'
} finally {
  Pop-Location
  $demoCredentials = $null
  $staffCredentials = $null
  $allCredentials = $null
  $passphrase = $null
  foreach ($name in @('DB_URL','DB_USERNAME','DB_PASSWORD','OPENROUTER_API_KEY','AI_PROVIDER','AI_MODEL','AI_FALLBACK_MODEL','DEPOSIT_INTEREST_ENABLED')) {
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
  }
}
