$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '.runtime\mcp-demo-credentials.dpapi'
if (-not (Test-Path -LiteralPath $path)) { throw 'Demo credentials are missing. The Oracle demo seed has not been installed here.' }
$protected = Get-Content -LiteralPath $path -Raw | ConvertTo-SecureString
$plain = [System.Net.NetworkCredential]::new('', $protected).Password
Write-Host 'Synthetic ALPHA demo users (shown only in this console):'
foreach ($line in ($plain -split "`n")) {
  $parts = $line.TrimEnd("`r") -split "`t"
  if ($parts.Count -lt 2) { continue }
  Write-Host "Login: $($parts[0])"
  Write-Host "Password: $($parts[1])"
  if ($parts.Count -ge 3 -and $parts[2]) { Write-Host "Customer access key: $($parts[2])" }
  Write-Host ''
}
$plain = $null
