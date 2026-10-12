$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '.runtime\alpha-team-staff-credentials.dpapi'
if (-not (Test-Path -LiteralPath $path)) {
  throw 'Team staff credentials are not unlocked here. Run start-alpha-team.ps1 first.'
}
$secure = Get-Content -LiteralPath $path -Raw | ConvertTo-SecureString
try { $plain = [System.Net.NetworkCredential]::new('', $secure).Password }
finally { $secure.Dispose() }
Write-Output 'ALPHA staff demo logins (keep these passwords private):'
foreach ($line in ($plain -split "`n")) {
  $parts = $line.TrimEnd("`r") -split "`t"
  if ($parts.Count -ne 3) { continue }
  Write-Output "Login: $($parts[0])"
  Write-Output "Role: $($parts[1])"
  Write-Output "Password: $($parts[2])"
  Write-Output ''
}
$plain = $null
Write-Output 'Customer and MCP-specific officer/checker logins: run ./show-mcp-demo-credentials.ps1.'
