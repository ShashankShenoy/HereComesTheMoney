function Invoke-OracleBuild {
param([Parameter(Mandatory)][string]$ProjectRoot, [switch]$CheckOnly, [switch]$Offline, [switch]$OracleTestsOnly)
$ErrorActionPreference = 'Stop'
$taskRoot = $ProjectRoot
$mavenRepository = Join-Path $env:USERPROFILE '.m2\repository'
$runtime = Join-Path $taskRoot '.runtime'
$buildRecord = Join-Path $runtime 'verified-build.json'
$serviceModules = @('identity-service','reporting-service','banking-service','payments-service','loan-service','audit-service')
$jars = @($serviceModules | ForEach-Object { Join-Path $taskRoot "backend/$_/target/$_-1.0.0-exec.jar" })
$frontendDist = Join-Path $taskRoot 'frontend\dist'

function Get-FileSetHash {
  param([System.IO.FileInfo[]]$Files)
  [string[]]$paths = @($Files | ForEach-Object { $_.FullName })
  [Array]::Sort($paths, [StringComparer]::Ordinal)
  $lines = foreach ($path in $paths) {
    $relative = $path.Substring($taskRoot.Length + 1).Replace('\', '/')
    # Hash directly so Windows PowerShell does not depend on an inherited
    # PowerShell 7 module search path to find Get-FileHash.
    $fileSha = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($path)
    try { $fileHash = ([System.BitConverter]::ToString($fileSha.ComputeHash($stream))).Replace('-', '') }
    finally { $stream.Dispose(); $fileSha.Dispose() }
    "$relative $fileHash"
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
    foreach ($module in ([xml](Get-Content -LiteralPath (Join-Path $taskRoot 'backend/pom.xml') -Raw)).project.modules.module) {
      Get-Item -LiteralPath (Join-Path $taskRoot "backend/$module/pom.xml")
      $sources = Join-Path $taskRoot "backend/$module/src"
      if (Test-Path -LiteralPath $sources) { Get-ChildItem -LiteralPath $sources -Recurse -File }
    }
    Get-ChildItem -LiteralPath (Join-Path $taskRoot 'frontend') -File | Where-Object { $_.Extension -in @('.js', '.mjs', '.css', '.html', '.svg', '.json') }
    Get-ChildItem -LiteralPath (Join-Path $taskRoot 'frontend\tools') -Recurse -File
    Get-ChildItem -LiteralPath (Join-Path $taskRoot 'frontend/assets') -Recurse -File
    Get-ChildItem -LiteralPath (Join-Path $taskRoot 'gateway') -File
  )
  Get-FileSetHash $files
}

function Get-FrontendHash {
  $files = @(Get-ChildItem -LiteralPath $frontendDist -Recurse -File)
  if (-not $files) { throw 'The frontend build is missing from frontend/dist.' }
  Get-FileSetHash $files
}

if ($CheckOnly) {
  if (-not (Test-Path -LiteralPath $buildRecord)) { throw 'No verified build record exists. Disconnect the VPN, run start-alpha-team.ps1 -BuildOnly, then reconnect and start with -SkipBuild.' }
  foreach ($jar in $jars) { if (-not (Test-Path -LiteralPath $jar)) { throw "Missing service JAR: $jar. Run start-alpha-team.ps1 -BuildOnly." } }
  $record = Get-Content -LiteralPath $buildRecord -Raw | ConvertFrom-Json
  if ($record.sourceHash -ne (Get-SourceHash)) { throw 'Source files changed since the last build. Run start-alpha-team.ps1 -BuildOnly before connecting the VPN.' }
  if ($record.servicesHash -ne (Get-FileSetHash @( $jars | ForEach-Object { Get-Item -LiteralPath $_ } ))) { throw 'The service JARs changed since the last verified build. Rebuild before starting.' }
  if ($record.frontendHash -ne (Get-FrontendHash)) { throw 'The frontend output changed since the last verified build. Rebuild before starting.' }
  Write-Host 'Verified build matches all service sources, executable JARs, gateway and frontend output.'
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

$mavenArguments = @('-f', (Join-Path $taskRoot 'backend\pom.xml'), "-Dmaven.repo.local=$mavenRepository", '-pl', (($serviceModules + 'acceptance-tests') -join ','), '-am', 'clean', 'verify')
if ($OracleTestsOnly) {
  # Maven tracks changed sources; retain compiled test artifacts and Oracle evidence.
  $mavenArguments = @($mavenArguments | Where-Object { $_ -ne 'clean' })
  $mavenArguments += '-DskipTests'
  Write-Host 'Oracle-only build: compiling backend tests without executing local-database regressions. Run the opt-in Oracle suite separately.'
}
$npmArguments = @('--prefix', (Join-Path $taskRoot 'frontend'), 'ci')
if ($Offline) {
  $mavenArguments = @('-o') + $mavenArguments
  $npmArguments += @('--offline', '--no-audit')
  Write-Host 'Offline build: using cached Maven/npm dependencies. Missing dependencies require an online build.'
}
Invoke-BuildStep $(if ($OracleTestsOnly) { 'backend compilation and packaging for Oracle acceptance' } else { 'backend (Maven clean verify)' }) 'mvn' $mavenArguments
Invoke-BuildStep 'gateway syntax' 'node' @('--check', (Join-Path $taskRoot 'gateway/server.mjs'))
Invoke-BuildStep 'gateway and architecture tests' 'node' @('--test', (Join-Path $taskRoot 'gateway/gateway.test.mjs'), (Join-Path $taskRoot 'gateway/architecture.test.mjs'))
Invoke-BuildStep 'frontend dependencies (npm ci)' 'npm' $npmArguments
Invoke-BuildStep 'frontend checks' 'npm' @('--prefix', (Join-Path $taskRoot 'frontend'), 'run', 'check')
Invoke-BuildStep 'frontend tests' 'npm' @('--prefix', (Join-Path $taskRoot 'frontend'), 'test')
Invoke-BuildStep 'frontend bundle' 'npm' @('--prefix', (Join-Path $taskRoot 'frontend'), 'run', 'build')
foreach ($jar in $jars) { if (-not (Test-Path -LiteralPath $jar)) { throw "Maven verify did not create $jar." } }
@{
  sourceHash = Get-SourceHash
  servicesHash = Get-FileSetHash @( $jars | ForEach-Object { Get-Item -LiteralPath $_ } )
  frontendHash = Get-FrontendHash
  backendTestsExecuted = (-not $OracleTestsOnly)
} | ConvertTo-Json | Set-Content -LiteralPath $buildRecord
Write-Host 'Build complete and recorded for VPN launch.'

}
Export-ModuleMember -Function Invoke-OracleBuild
