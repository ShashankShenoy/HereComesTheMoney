$ErrorActionPreference = 'Stop'
$runtime = Join-Path $PSScriptRoot '.runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$key = Read-Host 'Paste the new OpenRouter API key' -AsSecureString
$protected = ConvertFrom-SecureString $key
Set-Content -LiteralPath (Join-Path $runtime 'openrouter-api-key.dpapi') -Value $protected -NoNewline
Remove-Variable key, protected
Write-Host 'OpenRouter key saved for this Windows user. Restart the Oracle demo to use it.'
