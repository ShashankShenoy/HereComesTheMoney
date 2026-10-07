param([switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$mavenRepository = Join-Path $env:USERPROFILE '.m2\repository'
$runtime = Join-Path $taskRoot '.runtime'
$buildRecord = Join-Path $runtime 'verified-build.json'
$jar = Join-Path $taskRoot 'backend\target\moneybags-integrated-1.0.0.jar'
$frontendDist = Join-Path $taskRoot 'frontend\dist'

function Get-FileSetHash {
  param([System.IO.FileInfo[]]$Files)
  $lines = foreach ($file in ($Files | Sort-Object FullName)) {
    $relative = $file.FullName.Substring($taskRoot.Length + 1).Replace('\', '/')
    "$relative $((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash)"
  }
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))
    return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '')
  } finally { $sha.Dispose() }
}

function Get-SourceHash {
  $files = @(
    Get-Item -LiteralPath (Join-Path $taskRoot 'backend\pom.xml')
    Get-ChildItem -LiteralPath (Join-Path $taskRoot 'backend\src') -Recurse -File
    Get-ChildItem -LiteralPath (Join-Path $taskRoot 'frontend') -File | Where-Object { $_.Extension -in @('.js', '.mjs', '.css', '.html', '.svg', '.json') }
    Get-ChildItem -LiteralPath (Join-Path $taskRoot 'frontend\tools') -Recurse -File
  )
  Get-FileSetHash $files
}

function Get-FrontendHash {
  $files = @(Get-ChildItem -LiteralPath $frontendDist -File | Where-Object { $_.Extension -in @('.js', '.mjs', '.css', '.html', '.svg', '.json') })
  if (-not $files) { throw 'The frontend build is missing from frontend/dist.' }
  Get-FileSetHash $files
}

if ($CheckOnly) {
  if (-not (Test-Path -LiteralPath $buildRecord)) { throw 'No verified build record exists. Disconnect the VPN, run build-before-start.ps1, then reconnect and start with -SkipBuild.' }
  if (-not (Test-Path -LiteralPath $jar)) { throw 'The verified backend JAR is missing. Run build-before-start.ps1 before connecting the VPN.' }
  $record = Get-Content -LiteralPath $buildRecord -Raw | ConvertFrom-Json
  if ($record.sourceHash -ne (Get-SourceHash)) { throw 'Source files changed since the last build. Run build-before-start.ps1 before connecting the VPN.' }
  if ($record.jarHash -ne (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash) { throw 'The backend JAR changed since the last verified build. Rebuild before starting.' }
  if ($record.frontendHash -ne (Get-FrontendHash)) { throw 'The frontend output changed since the last verified build. Rebuild before starting.' }
  Write-Host 'Verified build matches the current source, backend JAR, and frontend output.'
  return
}

New-Item -ItemType Directory -Force -Path $runtime | Out-Null
Remove-Item -LiteralPath $buildRecord -ErrorAction SilentlyContinue

function Invoke-BuildStep {
  param([string]$Label, [string]$Command, [string[]]$Arguments)
  Write-Host "Building: $Label"
  & $Command @Arguments
  if ($LASTEXITCODE -ne 0) { throw "$Label failed with exit code $LASTEXITCODE. Startup stopped." }
}

Invoke-BuildStep 'backend (Maven clean verify)' 'mvn' @('-f', (Join-Path $taskRoot 'backend\pom.xml'), "-Dmaven.repo.local=$mavenRepository", 'clean', 'verify')
Invoke-BuildStep 'frontend dependencies (npm ci)' 'npm' @('--prefix', (Join-Path $taskRoot 'frontend'), 'ci')
Invoke-BuildStep 'frontend checks' 'npm' @('--prefix', (Join-Path $taskRoot 'frontend'), 'run', 'check')
Invoke-BuildStep 'frontend bundle' 'npm' @('--prefix', (Join-Path $taskRoot 'frontend'), 'run', 'build')
if (-not (Test-Path -LiteralPath $jar)) { throw 'Maven verify finished without creating the backend JAR.' }
@{
  sourceHash = Get-SourceHash
  jarHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
  frontendHash = Get-FrontendHash
} | ConvertTo-Json | Set-Content -LiteralPath $buildRecord
Write-Host 'Build complete and recorded for VPN launch.'
