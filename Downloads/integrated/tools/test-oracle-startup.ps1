#requires -Version 7.0
# Exercise lifecycle behavior with synthetic settings and mocked processes/network.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $projectRoot ('.runtime/startup-tests/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$saved = @{}
foreach ($name in @('DB_URL','DB_USERNAME','DB_PASSWORD','SPRING_PROFILES_ACTIVE','BACKEND_URL','FRONTEND_ORIGINS','OPENROUTER_API_KEY','AI_PROVIDER','AI_MODEL','AI_FALLBACK_MODEL','DEPOSIT_INTEREST_ENABLED','INTERNAL_BANK_CODES','CUSTOMER_SIGNUP_ENABLED','PAYMENT_SIMULATION_ENABLED')) {
  $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$passed = 0
function Assert-True([bool]$Value, [string]$Message) { if (-not $Value) { throw $Message } }
function Assert-Throws([scriptblock]$Action, [string]$Pattern) {
  try { & $Action } catch { if ($_.Exception.Message -like $Pattern) { return }; throw }
  throw "Expected failure: $Pattern"
}
function Test-Case([string]$Name, [scriptblock]$Action) {
  & $Action
  $script:passed++
  Write-Host "PASS: $Name"
}
function New-FakeProcess([int]$Id, [datetime]$Started) {
  $process = [pscustomobject]@{Id=$Id; StartTime=$Started}
  $process | Add-Member ScriptMethod WaitForExit { param($Timeout); return $true }
  return $process
}
try {
  Import-Module (Join-Path $PSScriptRoot 'oracle-runtime.psm1') -Force
  $module = Get-Module oracle-runtime
  & $module {
    $script:realStop = (Get-Command Stop-ServiceRun).ScriptBlock
    $script:started = [Collections.Generic.List[object]]::new()
    $script:stopped = [Collections.Generic.List[int]]::new()
    $script:cleanupCalls = 0
    $script:buildCalls = 0
    $script:buildFailure = $false
    $script:failAtProcess = 0
    $script:mockCleanup = $true
    $script:fakeProcesses = $null
    function script:Get-ServiceJava { return 'synthetic-java.exe' }
    function script:Test-OracleListener { param($DbUrl) }
    function script:Invoke-OracleBuild {
      param($ProjectRoot, [switch]$CheckOnly, [switch]$Offline)
      $script:buildCalls++
      $script:buildCheckOnly = [bool]$CheckOnly
      $script:buildOffline = [bool]$Offline
      if ($script:buildFailure) { throw 'Synthetic build failure.' }
    }
    function script:Start-Process {
      param($FilePath, $ArgumentList, $WindowStyle, $WorkingDirectory, $RedirectStandardOutput, $RedirectStandardError, [switch]$PassThru)
      if ($script:failAtProcess -eq $script:started.Count + 1) { throw 'Synthetic child startup failure.' }
      $script:started.Add(@{name=[IO.Path]::GetFileNameWithoutExtension($RedirectStandardOutput); arguments=$ArgumentList; window=$WindowStyle})
      return Microsoft.PowerShell.Management\Get-Process -Id $PID
    }
    function script:Invoke-RestMethod { param($Uri, $TimeoutSec); return @{status='UP'} }
    function script:Invoke-WebRequest { param([switch]$UseBasicParsing, $Uri, $TimeoutSec); return @{StatusCode=200} }
    function script:Get-Process {
      param($Id, $ErrorAction)
      if ($null -ne $script:fakeProcesses) { return $script:fakeProcesses[[int]$Id] }
      return Microsoft.PowerShell.Management\Get-Process -Id $Id
    }
    function script:Stop-Process { param($Id, [switch]$Force, $ErrorAction); $script:stopped.Add([int]$Id) }
    function script:Stop-ServiceRun {
      param($Record, $Run)
      if (-not $script:mockCleanup) { & $script:realStop $Record $Run; return }
      $script:cleanupCalls++
      $script:cleanedProcessCount = @( (Get-Content -LiteralPath $Record -Raw | ConvertFrom-Json).processes ).Count
      Remove-Item -LiteralPath $Record
    }
  }
  $listeners = @(1..8 | ForEach-Object { $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0); $listener.Start(); $listener })
  try { $ports = @($listeners | ForEach-Object { $_.LocalEndpoint.Port }) } finally { $listeners | ForEach-Object { $_.Stop() } }
  $options = @{ProjectRoot=$testRoot; GatewayPort=$ports[0]; IdentityPort=$ports[1]; ReportingPort=$ports[2]; BankingPort=$ports[3]; PaymentsPort=$ports[4]; LoanPort=$ports[5]; AuditPort=$ports[6]; FrontendPort=$ports[7]; SkipBuild=$true}
  $record = Join-Path $testRoot '.runtime/microservices-processes.json'
  $lockPath = Join-Path $testRoot '.runtime/microservices-start.lock'
  foreach ($name in @('identity-service','banking-service','payments-service','loan-service','audit-service','reporting-service')) {
    $target = Join-Path $testRoot "backend/$name/target"
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    'synthetic artifact' | Set-Content -LiteralPath (Join-Path $target "$name-1.0.0-exec.jar")
  }
  $env:SPRING_PROFILES_ACTIVE = $null
  $env:DB_URL = 'jdbc:h2:mem:test'
  $env:DB_USERNAME = 'synthetic'
  $env:DB_PASSWORD = 'synthetic'
  Test-Case 'H2 URLs are rejected before building or starting a process' {
    Assert-Throws { Start-OracleServices @options } '*requires DB_URL*'
    Assert-True (& $module { $script:started.Count -eq 0 -and $script:buildCalls -eq 0 }) 'Preflight started work.'
  }
  $env:DB_URL = 'jdbc:oracle:thin:@synthetic'
  Test-Case 'missing Oracle credentials are rejected' {
    $env:DB_PASSWORD = $null
    try { Assert-Throws { Start-OracleServices @options } '*requires DB_URL*' } finally { $env:DB_PASSWORD='synthetic' }
  }
  Test-Case 'inherited local profiles are rejected' {
    $env:SPRING_PROFILES_ACTIVE = 'local-microservices'
    try { Assert-Throws { Start-OracleServices @options } '*local H2*' } finally { $env:SPRING_PROFILES_ACTIVE=$null }
  }
  Test-Case 'duplicate ports are rejected' {
    $duplicates = $options.Clone(); $duplicates.IdentityPort=$duplicates.GatewayPort
    Assert-Throws { Start-OracleServices @duplicates } '*different port*'
  }
  Test-Case 'occupied ports are rejected' {
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$options.GatewayPort)
    try { $listener.Start(); Assert-Throws { Start-OracleServices @options } '*already in use*' } finally { $listener.Stop() }
  }
  Test-Case 'concurrent startup and shutdown respect the same lock' {
    $lock = [IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try {
      Assert-Throws { Start-OracleServices @options } '*in progress*'
      '{"processes":[]}' | Set-Content -LiteralPath $record
      Assert-Throws { Stop-OracleServices -ProjectRoot $testRoot } '*in progress*'
    } finally { $lock.Dispose(); Remove-Item -LiteralPath $record -ErrorAction SilentlyContinue }
  }
  Test-Case 'an existing run and its record survive a rejected start or build-only' {
    '{"processes":[]}' | Set-Content -LiteralPath $record
    try {
      $before = Get-Content -LiteralPath $record -Raw
      Assert-Throws { Start-OracleServices @options } '*run record exists*'
      Assert-Throws { Start-OracleServices -ProjectRoot $testRoot -BuildOnly } '*run record exists*'
      Assert-True ((Get-Content -LiteralPath $record -Raw) -eq $before) 'Existing record changed.'
      Assert-True (& $module { $script:cleanupCalls -eq 0 }) 'Existing run was cleaned up.'
    } finally { Remove-Item -LiteralPath $record }
  }
  Test-Case 'build-only needs no database settings and forwards offline mode' {
    $env:DB_URL = $null; $env:DB_PASSWORD = $null
    try {
      Start-OracleServices -ProjectRoot $testRoot -BuildOnly -Offline
      Assert-True (& $module { $script:buildOffline -and -not $script:buildCheckOnly -and $script:started.Count -eq 0 }) 'Build-only launched services or lost options.'
    } finally { $env:DB_URL='jdbc:oracle:thin:@synthetic'; $env:DB_PASSWORD='synthetic' }
  }
  Test-Case 'build failures leave no process record and release the startup lock' {
    & $module { $script:buildFailure=$true }
    try { Assert-Throws { Start-OracleServices @options } '*Synthetic build failure*' } finally { & $module { $script:buildFailure=$false } }
    Assert-True (-not (Test-Path -LiteralPath $record)) 'Failed build created a run.'
    $lock=[IO.File]::Open($lockPath,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None); $lock.Dispose()
  }
  Test-Case 'partial startup cleans up only its own children and restores the environment' {
    $env:BACKEND_URL='synthetic-parent'; $env:FRONTEND_ORIGINS='synthetic-parent-origin'
    & $module { $script:failAtProcess=3 }
    try { Assert-Throws { Start-OracleServices @options } '*Synthetic child startup failure*' } finally { & $module { $script:failAtProcess=0 } }
    Assert-True (& $module { $script:cleanupCalls -eq 1 -and $script:cleanedProcessCount -eq 2 }) 'Partial children were not cleaned up.'
    Assert-True (-not (Test-Path -LiteralPath $record)) 'Failed startup retained its record.'
    Assert-True ($env:BACKEND_URL -eq 'synthetic-parent' -and $env:FRONTEND_ORIGINS -eq 'synthetic-parent-origin') 'Parent environment changed.'
  }
  Test-Case 'successful startup records eight hidden components with Oracle-only arguments' {
    & $module { $script:started.Clear() }
    Start-OracleServices @options
    $run=Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
    Assert-True ($run.database -eq 'Oracle' -and $run.processes.Count -eq 8) 'Wrong component count or database.'
    Assert-True (& $module {
      $java=@($script:started | Where-Object { $_.name -like '*-service' })
      $java.Count -eq 6 -and @($java | Where-Object { $_.arguments -notcontains '--spring.profiles.active=oracle-microservices' -or $_.arguments -notcontains '--spring.sql.init.mode=never' }).Count -eq 0 -and @($script:started | Where-Object { $_.name -eq 'local-database' -or $_.window -ne 'Hidden' }).Count -eq 0 -and $script:buildCheckOnly
    }) 'Unexpected profile, SQL initialization, H2 process, window, or build option.'
    Assert-True ($env:BACKEND_URL -eq 'synthetic-parent') 'Successful startup changed parent environment.'
    Remove-Item -LiteralPath $record
  }
  $started=[datetime]::UtcNow.AddMinutes(-1)
  & $module {
    param($Started)
    $script:mockCleanup=$false
    $script:fakeProcesses=@{101=(New-FakeProcess 101 $Started);102=(New-FakeProcess 102 $Started);103=(New-FakeProcess 103 $Started)}
  } $started
  $entries=@(101..103 | ForEach-Object { @{name="synthetic-$_";id=$_;startTicks=$started.Ticks.ToString()} })
  Test-Case 'shutdown stops owned processes in reverse order and removes the record' {
    @{processes=$entries}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $record
    Stop-OracleServices -ProjectRoot $testRoot
    Assert-True (& $module { ($script:stopped -join ',') -eq '103,102,101' }) 'Shutdown order changed.'
    Assert-True (-not (Test-Path -LiteralPath $record)) 'Shutdown retained the record.'
  }
  Test-Case 'a reused PID is never stopped and its ownership record survives retries' {
    & $module { $script:stopped.Clear() }
    @{processes=@(@{name='synthetic';id=101;startTicks=($started.Ticks-1).ToString()})}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $record
    $before=Get-Content -LiteralPath $record -Raw
    1..2|ForEach-Object { Assert-Throws { Stop-OracleServices -ProjectRoot $testRoot } '*belongs to another process*' }
    Assert-True (& $module { $script:stopped.Count -eq 0 }) 'Reused PID was stopped.'
    Assert-True ((Get-Content -LiteralPath $record -Raw) -eq $before) 'Ownership record changed.'
    Remove-Item -LiteralPath $record
  }
  Test-Case 'legacy Oracle records still stop using process creation times' {
    $legacy=Join-Path $testRoot '.runtime/oracle-alpha-processes.json'
    @{backend=101;backendStartTicks=$started.Ticks.ToString();frontend=102;frontendStartTicks=$started.Ticks.ToString()}|ConvertTo-Json|Set-Content -LiteralPath $legacy
    Stop-OracleServices -ProjectRoot $testRoot
    Assert-True (-not (Test-Path -LiteralPath $legacy)) 'Legacy run survived shutdown.'
  }
  Test-Case 'reused legacy PIDs retain their original record on repeated shutdown attempts' {
    $legacy=Join-Path $testRoot '.runtime/oracle-alpha-processes.json'
    @{backend=101;backendStartTicks=($started.Ticks-1).ToString()}|ConvertTo-Json|Set-Content -LiteralPath $legacy
    $before=Get-Content -LiteralPath $legacy -Raw
    1..2|ForEach-Object { Assert-Throws { Stop-OracleServices -ProjectRoot $testRoot } '*belongs to another process*' }
    Assert-True ((Get-Content -LiteralPath $legacy -Raw) -eq $before) 'Legacy ownership record changed.'
    Remove-Item -LiteralPath $legacy
  }
  Test-Case 'stopping an already stopped stack is harmless' { Stop-OracleServices -ProjectRoot $testRoot }

  # Check the real public script with a synthetic encrypted bundle in an isolated checkout.
  $publicRoot=Join-Path $testRoot 'public'
  $publicTools=Join-Path $publicRoot 'tools'
  New-Item -ItemType Directory -Force -Path $publicTools|Out-Null
  Copy-Item -LiteralPath (Join-Path $projectRoot 'start-alpha-team.ps1') -Destination $publicRoot
  Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'alpha-team-crypto.psm1') -Destination $publicTools
  @'
function Start-OracleServices {
  param($ProjectRoot,$GatewayPort,$FrontendPort,[switch]$SkipBuild,[switch]$Offline,[switch]$BuildOnly,$CifKeyFile,$MfaKeyFile)
  Write-Output 'Synthetic Oracle service startup'
  @{buildOnly=[bool]$BuildOnly;offline=[bool]$Offline;gateway=$GatewayPort;frontend=$FrontendPort;skipBuild=[bool]$SkipBuild;dbUrl=$env:DB_URL;openrouter=$env:OPENROUTER_API_KEY;cifExists=(Test-Path -LiteralPath $CifKeyFile -ErrorAction SilentlyContinue);mfaExists=(Test-Path -LiteralPath $MfaKeyFile -ErrorAction SilentlyContinue)} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ProjectRoot 'invocation.json')
}
Export-ModuleMember -Function Start-OracleServices
'@ | Set-Content -LiteralPath (Join-Path $publicTools 'oracle-runtime.psm1')
  Import-Module (Join-Path $PSScriptRoot 'alpha-team-crypto.psm1') -Force
  $payload=@{format='moneybags-alpha-settings-v1';dbUrl='jdbc:oracle:thin:@synthetic';dbUsername='synthetic';dbPassword='synthetic';cifKey=[Convert]::ToBase64String([byte[]](1..32));mfaKey=[Convert]::ToBase64String([byte[]](33..64));staffCredentials="admin`tBANK_ADMIN`tsynthetic`nchecker`tBANK_CHECKER`tsynthetic`nofficer`tDEMO_BRANCH_OFFICER`tsynthetic";demoCredentials="mcp_demo_customer_a`tsynthetic`n"+"mcp_demo_customer_b`tsynthetic`n"+"mcp_demo_officer`tsynthetic`n"+"mcp_demo_checker`tsynthetic";openrouterApiKey='synthetic-bundle-key';aiProvider='openrouter';aiModel='synthetic-model';aiFallbackModel='synthetic-fallback';depositInterestEnabled='false'}
  $payload.demoCredentials = $payload.demoCredentials.Replace("mcp_demo_customer_a`tsynthetic`n", "mcp_demo_customer_a`tsynthetic`tsynthetic-access-key`n")
  $passphraseFile=Join-Path $publicRoot 'synthetic-passphrase.txt'
  'synthetic-passphrase'|Set-Content -LiteralPath $passphraseFile
  Protect-AlphaTeamPayload ($payload|ConvertTo-Json -Compress) 'synthetic-passphrase'|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $publicRoot 'alpha-team.bundle.json')
  $publicStart=Join-Path $publicRoot 'start-alpha-team.ps1'
  Test-Case 'bundle check-only validates without writing keys or credentials' {
    & $publicStart -CheckOnly -PassphraseFile $passphraseFile
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $publicRoot '.runtime'))) 'Check-only wrote private runtime files.'
  }
  Test-Case 'unlock-only and ShowCredentials replace the separate credential scripts' {
    $unlocked=(& $publicStart -UnlockOnly -PassphraseFile $passphraseFile)|Out-String
    Assert-True ($unlocked.Contains('Login: admin') -and $unlocked.Contains('Password: synthetic')) 'Unlock-only did not display team logins.'
    $output=(& $publicStart -ShowCredentials)|Out-String
    Assert-True ($output.Contains('Login: admin') -and $output.Contains('Login: mcp_demo_customer_a')) 'Saved team logins were not displayed.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $publicRoot 'invocation.json'))) 'Unlock-only started services.'
  }
  Test-Case 'public startup displays seven logins, loads the key override, and restores parent secrets' {
    ConvertTo-SecureString 'synthetic-local-key' -AsPlainText -Force|ConvertFrom-SecureString|Set-Content -LiteralPath (Join-Path $publicRoot '.runtime/openrouter-api-key.dpapi') -NoNewline
    $env:DB_URL='synthetic-parent-url';$env:OPENROUTER_API_KEY='synthetic-parent-key'
    $output=(& $publicStart -SkipBuild -Offline -PassphraseFile $passphraseFile)|Out-String
    $logins=@($output -split "`n"|Where-Object{$_ -match '^Login: '})
    Assert-True ($logins.Count -eq 7 -and $output.Contains('Role: BANK_ADMIN') -and $output.Contains('Password: synthetic')) 'Startup did not automatically display all seven credentials.'
    Assert-True ($output.Contains('Customer access key: synthetic-access-key') -and $output.IndexOf('Login: admin') -lt $output.IndexOf('Synthetic Oracle service startup')) 'Startup omitted the access key or displayed credentials after launching services.'
    $invoked=Get-Content -LiteralPath (Join-Path $publicRoot 'invocation.json') -Raw|ConvertFrom-Json
    Assert-True ($invoked.dbUrl -like 'jdbc:oracle:*' -and $invoked.openrouter -eq 'synthetic-local-key' -and $invoked.cifExists -and $invoked.mfaExists -and $invoked.skipBuild -and $invoked.offline) 'Startup lost Oracle settings, protected key, or options.'
    Assert-True ($env:DB_URL -eq 'synthetic-parent-url' -and $env:OPENROUTER_API_KEY -eq 'synthetic-parent-key') 'Parent secrets changed.'
  }
  Test-Case 'a mismatched existing ALPHA key is preserved and prevents startup' {
    $keyPath=Join-Path $publicRoot '.runtime/alpha-keys/cif.key'
    [IO.File]::WriteAllBytes($keyPath,[byte[]](2..33))
    Assert-Throws { & $publicStart -CheckOnly -PassphraseFile $passphraseFile } '*does not match the team bundle*'
    Assert-True ([IO.File]::ReadAllBytes($keyPath)[0] -eq 2) 'Existing ALPHA key was overwritten.'
  }
  Test-Case 'exactly three operational scripts remain in the integrated directory' {
    $names=@(Get-ChildItem -LiteralPath $projectRoot -File -Filter '*.ps1'|Where-Object{$_.Name -notlike '.*'}|Select-Object -ExpandProperty Name|Sort-Object)
    Assert-True (($names -join ',') -eq 'set-openrouter-key.ps1,start-alpha-team.ps1,stop-oracle-alpha.ps1') 'Unexpected public scripts remain.'
  }
  Write-Host "$passed Oracle startup checks passed without connecting to a database."
} finally {
  foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
  # Delete only this test's resolved scratch directory inside the workspace.
  $resolved=[IO.Path]::GetFullPath($testRoot)
  $allowed=[IO.Path]::GetFullPath((Join-Path $projectRoot '.runtime/startup-tests')) + [IO.Path]::DirectorySeparatorChar
  if (-not $resolved.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)) { throw 'Test cleanup path escaped its workspace.' }
  if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
