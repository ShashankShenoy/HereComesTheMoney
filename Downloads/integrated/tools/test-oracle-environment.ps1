#requires -Version 7.0
param([switch]$SkipBuild,[switch]$Offline,[securestring]$Passphrase,[string]$PassphraseFile)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$runtime=Join-Path $taskRoot '.runtime'
$resultsRoot=Join-Path $runtime 'environment-tests'
New-Item -ItemType Directory -Force -Path $resultsRoot|Out-Null
if(Test-Path -LiteralPath (Join-Path $runtime 'microservices-processes.json')){throw 'Stop the existing stack before running Oracle environment checks.'}
Import-Module (Join-Path $PSScriptRoot 'alpha-team-crypto.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'oracle-runtime.psm1') -Force
$results=[Collections.Generic.List[object]]::new()
$names=@('DB_URL','DB_USERNAME','DB_PASSWORD','BOOTSTRAP_ADMIN','CUSTOMER_SIGNUP_ENABLED','DEPOSIT_INTEREST_ENABLED','PAYMENT_SIMULATION_ENABLED','OPENROUTER_API_KEY','OPENAI_API_KEY')
$saved=@{};foreach($envName in $names){$saved[$envName]=[Environment]::GetEnvironmentVariable($envName,'Process')}
$owned=$false
function Record-OracleCheck([string]$Name,[bool]$Passed){
  $results.Add(@{category='Oracle runner';name=$Name;status=$(if($Passed){'PASS'}else{'FAIL'})})
  Write-Host "$(if($Passed){'PASS'}else{'FAIL'}): $Name"
}
try{
  if($PassphraseFile){$plainPassphrase=(Get-Content -LiteralPath $PassphraseFile -Raw).Trim()}
  else{
    if(-not $Passphrase){$Passphrase=Read-Host 'ALPHA team bundle passphrase' -AsSecureString}
    $plainPassphrase=[Net.NetworkCredential]::new('',$Passphrase).Password
  }
  $payload=Unprotect-AlphaTeamPayload (Get-Content -LiteralPath (Join-Path $taskRoot 'alpha-team.bundle.json') -Raw|ConvertFrom-Json) $plainPassphrase|ConvertFrom-Json
  $plainPassphrase=$null
  if($payload.format -ne 'moneybags-alpha-settings-v1' -or $payload.dbUrl -notlike 'jdbc:oracle:*'){throw 'Invalid Oracle settings bundle.'}
  Record-OracleCheck 'encrypted Oracle settings unlock without exposing credentials' $true
  $env:DB_URL=$payload.dbUrl;$env:DB_USERNAME=$payload.dbUsername;$env:DB_PASSWORD=$payload.dbPassword
  # Disable autonomous demo writes and external AI calls for this verification.
  $env:BOOTSTRAP_ADMIN='false';$env:CUSTOMER_SIGNUP_ENABLED='false';$env:DEPOSIT_INTEREST_ENABLED='false';$env:OPENROUTER_API_KEY=$null;$env:OPENAI_API_KEY=$null
  $env:PAYMENT_SIMULATION_ENABLED='false'
  $keyRoot=Join-Path $runtime 'alpha-keys';New-Item -ItemType Directory -Force -Path $keyRoot|Out-Null
  foreach($kind in @('cif','mfa')){
    $bytes=[Convert]::FromBase64String($payload.($kind+'Key'));$keyPath=Join-Path $keyRoot ($kind+'.key')
    if(Test-Path -LiteralPath $keyPath){if(-not [Security.Cryptography.CryptographicOperations]::FixedTimeEquals([IO.File]::ReadAllBytes($keyPath),$bytes)){throw 'Existing ALPHA encryption key differs from bundle; refusing to replace it.'}}
    else{[IO.File]::WriteAllBytes($keyPath,$bytes)}
    [Array]::Clear($bytes,0,$bytes.Length)
  }
  $credentials=@(foreach($username in @('mcp_demo_customer_a','mcp_demo_customer_b')){
    $row=@($payload.demoCredentials -split "`n"|Where-Object{($_ -split "`t")[0] -eq $username})
    if($row.Count -ne 1){throw 'Expected synthetic demo credentials are missing.'}
    $parts=$row[0].TrimEnd("`r") -split "`t";@{username=$username;password=$parts[1]}
  })
  $payload=$null
  Invoke-OracleBuild -ProjectRoot $taskRoot -CheckOnly:$SkipBuild -Offline:$Offline
  $dependencies=Join-Path $runtime 'oracle-dependencies';New-Item -ItemType Directory -Force -Path $dependencies|Out-Null
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $archive=[IO.Compression.ZipFile]::OpenRead((Join-Path $taskRoot 'backend/banking-service/target/banking-service-1.0.0-exec.jar'))
  try{foreach($entry in $archive.Entries){if($entry.FullName -like 'BOOT-INF/lib/*.jar'){[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $dependencies $entry.Name),$true)}}}finally{$archive.Dispose()}
  $classes=Join-Path $runtime 'oracle-readiness-classes';New-Item -ItemType Directory -Force -Path $classes|Out-Null
  & javac --release 17 -encoding UTF-8 -d $classes (Join-Path $taskRoot 'tools/OracleReadinessCheck.java') *> (Join-Path $resultsRoot 'oracle-readiness-compile.log')
  if($LASTEXITCODE -ne 0){throw 'Oracle readiness test compilation failed.'}
  & java -cp ($classes+';'+(Join-Path $dependencies '*')) OracleReadinessCheck (Join-Path $resultsRoot 'oracle-schema.json') *> (Join-Path $resultsRoot 'oracle-schema.log')
  $schemaExit=$LASTEXITCODE
  & javac --release 17 -encoding UTF-8 -cp (Join-Path $dependencies '*') -d $classes (Join-Path $taskRoot 'tools/OracleIdentityPreflight.java') *> (Join-Path $resultsRoot 'oracle-identity-compile.log')
  if($LASTEXITCODE -ne 0){throw 'Oracle identity preflight compilation failed.'}
  $credentials|ConvertTo-Json -Compress|& java -cp ($classes+';'+(Join-Path $dependencies '*')) OracleIdentityPreflight (Join-Path $resultsRoot 'oracle-identity.json') *> (Join-Path $resultsRoot 'oracle-identity.log')
  if($LASTEXITCODE -ne 0){throw 'Oracle identity preflight failed.'}
  $identityResults=Get-Content -LiteralPath (Join-Path $resultsRoot 'oracle-identity.json') -Raw|ConvertFrom-Json
  $identityExit=0
  foreach($credential in $credentials){
    $checked=@($identityResults.results|Where-Object{$_.username -eq $credential.username})
    $credential.canLogin=$checked.Count -eq 1 -and $checked[0].status -eq 'PASS'
    if(-not $credential.canLogin){$identityExit=1}
  }
  try{
    & (Join-Path $PSScriptRoot 'test-financial-audit-oracle.ps1') -OracleEnvFile (Join-Path $runtime 'no-env-file') -DependencyDirectory $dependencies *> (Join-Path $resultsRoot 'oracle-audit.log')
    Record-OracleCheck 'financial audit acceptance suite rolls back every test write' $true
  }catch{Record-OracleCheck 'financial audit acceptance suite rolls back every test write' $false}
  Start-OracleServices -ProjectRoot $taskRoot -GatewayPort 18090 -IdentityPort 18092 -ReportingPort 18093 -BankingPort 18094 -PaymentsPort 18095 -LoanPort 18096 -AuditPort 18097 -FrontendPort 15173 -SkipBuild -CifKeyFile (Join-Path $keyRoot 'cif.key') -MfaKeyFile (Join-Path $keyRoot 'mfa.key') *> (Join-Path $resultsRoot 'oracle-startup.log')
  $owned=$true
  $run=Get-Content -LiteralPath (Join-Path $runtime 'microservices-processes.json') -Raw|ConvertFrom-Json
  Record-OracleCheck 'Oracle startup launches eight components and no H2 database process' ($run.database -eq 'Oracle' -and $run.processes.Count -eq 8 -and @($run.processes|Where-Object{$_.name -eq 'local-database'}).Count -eq 0)
  $credentials|ConvertTo-Json -Compress|& node (Join-Path $taskRoot 'tools/oracle-service-checks.mjs') 'http://127.0.0.1:15173' 'http://127.0.0.1:18092' 'http://127.0.0.1:18093' 'http://127.0.0.1:18094' (Join-Path $resultsRoot 'oracle-services.json') *> (Join-Path $resultsRoot 'oracle-services.log')
  $serviceExit=$LASTEXITCODE
}catch{
  Record-OracleCheck 'Oracle runner completed all configured checks' $false
  # Keep errors local; do not echo a decrypted URL, password or response.
  'Oracle runner failed. Inspect the ignored Oracle logs locally.'|Write-Host
}finally{
  $credentials=$null;$payload=$null;$plainPassphrase=$null
  if($owned){
    try{
      Stop-OracleServices -ProjectRoot $taskRoot *> (Join-Path $resultsRoot 'oracle-shutdown.log')
      $released=-not(Test-Path -LiteralPath (Join-Path $runtime 'microservices-processes.json'))
      foreach($port in @(18090,18092,18093,18094,18095,18096,18097,15173)){
        $client=[Net.Sockets.TcpClient]::new();try{$client.Connect('127.0.0.1',$port);$released=$false}catch [Net.Sockets.SocketException]{}finally{$client.Dispose()}
      }
      Record-OracleCheck 'Oracle verification releases all owned processes and ports' $released
    }catch{Record-OracleCheck 'Oracle verification releases all owned processes and ports' $false}
  }
  foreach($envName in $names){[Environment]::SetEnvironmentVariable($envName,$saved[$envName],'Process')}
  @{database='Oracle';generatedAt=[DateTimeOffset]::UtcNow.ToString('o');results=@($results.ToArray())}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $resultsRoot 'oracle-runner.json')
}
if($schemaExit -ne 0 -or $identityExit -ne 0 -or $serviceExit -ne 0 -or @($results|Where-Object{$_.status -eq 'FAIL'}).Count){throw 'Oracle environment checks found failures. See .runtime/environment-tests/oracle-*.json.'}
Write-Host 'Oracle schema, audit and full-stack environment checks passed.'
