$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '.runtime\alpha-team-credentials.dpapi'
if (-not (Test-Path -LiteralPath $path)) {
  throw 'Team credentials are not unlocked here. Run start-alpha-team.ps1 first.'
}
$secure = Get-Content -LiteralPath $path -Raw | ConvertTo-SecureString
try { $plain = [System.Net.NetworkCredential]::new('', $secure).Password }
finally { $secure.Dispose() }
Write-Output 'All ALPHA demo logins (keep these passwords and access keys private):'
foreach ($line in ($plain -split "`n")) {
  $parts = $line.TrimEnd("`r") -split "`t"
  if ($parts.Count -ne 4) { continue }
  Write-Output "Login: $($parts[0])"
  Write-Output "Role: $($parts[1])"
  Write-Output "Password: $($parts[2])"
  if ($parts[3]) { Write-Output "Customer access key: $($parts[3])" }
  Write-Output ''
}
$plain = $null
