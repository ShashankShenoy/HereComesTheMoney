param([switch]$SkipBuild,[switch]$Offline,[switch]$SkipLauncherFailureTests,[switch]$BrowserGate,[switch]$CompatibilityOnly)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Net.Http
$taskRoot=$PSScriptRoot
. (Join-Path $taskRoot 'tools/service-runtime.ps1')
$resultsRoot=Join-Path $taskRoot '.runtime/environment-tests'
New-Item -ItemType Directory -Force -Path $resultsRoot | Out-Null
$record=Join-Path $taskRoot '.runtime/microservices-processes.json'
if(Test-Path -LiteralPath $record){throw 'Stop the existing stack before running disposable environment tests.'}
$cases=[Collections.Generic.List[object]]::new()
if($CompatibilityOnly -and (Test-Path -LiteralPath (Join-Path $resultsRoot 'operations.json'))){
  $previousResults=Get-Content -LiteralPath (Join-Path $resultsRoot 'operations.json') -Raw|ConvertFrom-Json
  foreach($previousCase in $previousResults.results){if($previousCase.category -ne 'Compatibility'){$cases.Add($previousCase)}}
}
$ownedStack=$false
$token=$null
$ports=@{GatewayPort=18090;IdentityPort=18092;ReportingPort=18093;BankingPort=18094;PaymentsPort=18095;LoanPort=18096;AuditPort=18097;DatabasePort=19095;FrontendPort=15173}
function Save-TestResults {
  $rows=@($cases.ToArray())
  $value=@{generatedAt=[DateTimeOffset]::UtcNow.ToString('o');summary=@{total=$rows.Count;passed=@($rows|Where-Object{$_.status -eq 'PASS'}).Count;failed=@($rows|Where-Object{$_.status -eq 'FAIL'}).Count};results=$rows}
  [IO.File]::WriteAllText((Join-Path $resultsRoot 'operations.json'),($value|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
}
function Test-Case {
  param([string]$Category,[string]$Name,[scriptblock]$Action)
  $timer=[Diagnostics.Stopwatch]::StartNew()
  try{& $Action;$cases.Add(@{category=$Category;name=$Name;status='PASS';elapsedMs=$timer.ElapsedMilliseconds});Write-Host "PASS $Category`: $Name"}
  catch{$cases.Add(@{category=$Category;name=$Name;status='FAIL';elapsedMs=$timer.ElapsedMilliseconds;error=$_.Exception.Message});Write-Host "FAIL $Category`: $Name - $($_.Exception.Message)"}
  finally{Save-TestResults}
}
function Assert-Throws {
  param([scriptblock]$Action,[string]$Pattern)
  $caught=$null
  try{& $Action}catch{$caught=$_}
  if(-not $caught -or $caught.Exception.Message -notlike $Pattern){throw "Expected rejection matching '$Pattern'."}
}
function Http-Check {
  param([string]$Url,[int]$Expected,[string]$Bearer=$token,[string]$Body)
  $client=[Net.Http.HttpClient]::new();$client.Timeout=[TimeSpan]::FromSeconds(20)
  try{
    if($Bearer){$client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$Bearer)}
    if($PSBoundParameters.ContainsKey('Body')){$content=[Net.Http.StringContent]::new($Body,[Text.Encoding]::UTF8,'application/json');$response=$client.PostAsync($Url,$content).GetAwaiter().GetResult();$content.Dispose()}
    else{$response=$client.GetAsync($Url).GetAwaiter().GetResult()}
    try{
      $text=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
      if([int]$response.StatusCode -ne $Expected){throw "Expected $Expected, got $([int]$response.StatusCode) at $Url"}
      try{return ($text|ConvertFrom-Json)}catch{return $text}
    }finally{$response.Dispose()}
  }finally{$client.Dispose()}
}
function Wait-Ready {
  param([Diagnostics.Process]$Process,[int]$Port)
  $deadline=[DateTime]::UtcNow.AddSeconds(120)
  while([DateTime]::UtcNow -lt $deadline){
    $Process.Refresh();if($Process.HasExited){throw "Recovered process exited on port $Port"}
    try{$health=Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health/readiness" -TimeoutSec 4;if($health.status -eq 'UP'){return}}catch{}
    Start-Sleep -Milliseconds 400
  }
  throw "Recovery on port $Port timed out."
}
function Stop-TestService {
  param([string]$Name)
  $run=Get-Content -LiteralPath $record -Raw|ConvertFrom-Json
  if($run.database -ne 'Local'){throw 'Outage tests require the local ephemeral database.'}
  $entry=@($run.processes|Where-Object{$_.name -eq $Name})
  if($entry.Count -ne 1){throw "Missing $Name ownership record."}
  $process=Get-Process -Id $entry[0].id -ErrorAction Stop
  if($process.StartTime.ToUniversalTime().Ticks -ne [long]$entry[0].startTicks){throw 'Process ownership changed.'}
  Stop-Process -Id $process.Id -Force;[void]$process.WaitForExit(10000)
}
function Restart-TestService {
  param([string]$Name,[int]$Port)
  $savedEnv=@{}
  foreach($envName in @('PORT','BACKEND_URL','GATEWAY_PORT','IDENTITY_URL','REPORTING_URL','BANKING_URL','PAYMENTS_URL','LOAN_URL','AUDIT_URL')){$savedEnv[$envName]=[Environment]::GetEnvironmentVariable($envName,'Process')}
  try{
    $working=$taskRoot
    if($Name -like '*-service'){
      $exe=Get-ServiceJava
      $jar=Join-Path $taskRoot "backend/$Name/target/$Name-1.0.0-exec.jar"
      $arguments=@('-Xms32m','-Xmx512m','-jar',('"'+$jar+'"'),"--server.port=$Port",'--server.address=127.0.0.1','--spring.profiles.active=local-microservices','--spring.sql.init.mode=never','--spring.datasource.url=jdbc:h2:tcp://127.0.0.1:19095/mem:moneybags;MODE=Oracle;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH','--spring.datasource.username=sa','--spring.datasource.password=','--spring.datasource.driver-class-name=org.h2.Driver','--moneybags.services.identity-url=http://127.0.0.1:18092','--moneybags.services.reporting-url=http://127.0.0.1:18093','--moneybags.services.payments-url=http://127.0.0.1:18095','--moneybags.auth.allowed-origins=http://localhost:15173,http://127.0.0.1:15173')
    }else{
      $exe=(Get-Command node).Source;$arguments=@('server.mjs')
      $env:GATEWAY_PORT='18090';$env:IDENTITY_URL='http://127.0.0.1:18092';$env:REPORTING_URL='http://127.0.0.1:18093';$env:BANKING_URL='http://127.0.0.1:18094';$env:PORT='15173';$env:BACKEND_URL='http://127.0.0.1:18090'
      $env:PAYMENTS_URL='http://127.0.0.1:18095';$env:LOAN_URL='http://127.0.0.1:18096';$env:AUDIT_URL='http://127.0.0.1:18097'
      $working=Join-Path $taskRoot $(if($Name -eq 'gateway'){'gateway'}else{'frontend/dist'})
    }
    $process=Start-Process -FilePath $exe -ArgumentList $arguments -WindowStyle Hidden -WorkingDirectory $working -RedirectStandardOutput (Join-Path $resultsRoot "$Name-recovery.log") -RedirectStandardError (Join-Path $resultsRoot "$Name-recovery.err.log") -PassThru
    $run=Get-Content -LiteralPath $record -Raw|ConvertFrom-Json
    $entry=@($run.processes|Where-Object{$_.name -eq $Name})[0];$entry.id=$process.Id;$entry.startTicks=$process.StartTime.ToUniversalTime().Ticks.ToString()
    Save-ServiceRun $record $run
    Wait-Ready $process $Port
  }catch{if($process){$process.Refresh();if(-not $process.HasExited){Stop-Process -Id $process.Id -Force}};throw}
  finally{foreach($envName in $savedEnv.Keys){[Environment]::SetEnvironmentVariable($envName,$savedEnv[$envName],'Process')}}
}
function Assert-ReleasedPorts {
  if(Test-Path -LiteralPath $record){throw 'The process record remained after shutdown.'}
  foreach($port in $ports.Values){$probe=[Net.Sockets.TcpClient]::new();try{$probe.Connect('127.0.0.1',$port);throw "Port $port remained open."}catch [Net.Sockets.SocketException]{}finally{$probe.Dispose()}}
}

if(-not $SkipBuild){& (Join-Path $taskRoot 'build-before-start.ps1') -Offline:$Offline}
if(-not $CompatibilityOnly){
Test-Case 'Launcher' 'duplicate ports rejected before any process starts' {Assert-Throws {& (Join-Path $taskRoot 'start-microservices.ps1') -GatewayPort 18090 -IdentityPort 18090 -SkipBuild} '*different port*';if(Test-Path $record){throw 'Unexpected run record'}}
Test-Case 'Launcher' 'occupied port rejected without taking over listener' {
  $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,18090)
  try{$listener.Start();Assert-Throws {& (Join-Path $taskRoot 'start-microservices.ps1') @ports -SkipBuild} '*already in use*'}finally{$listener.Stop()}
}
Test-Case 'Launcher' 'OS lock excludes concurrent startup' {
  $lock=[IO.File]::Open((Join-Path $taskRoot '.runtime/microservices-start.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
  try{Assert-Throws {& (Join-Path $taskRoot 'start-microservices.ps1') @ports -SkipBuild} '*in progress*'}finally{$lock.Dispose()}
}
Test-Case 'Launcher' 'PID timestamp mismatch never kills the replacement process' {
  $fixture=Join-Path $resultsRoot 'pid-guard.json';$current=Get-Process -Id $PID
  try{@{processes=@(@{name='borrowed';id=$current.Id;startTicks=($current.StartTime.ToUniversalTime().Ticks+100).ToString()})}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $fixture
    Assert-Throws {Stop-ServiceRun $fixture} '*another process*';if(-not(Test-Path $fixture)){throw 'Ownership evidence was deleted'}
  }finally{Remove-Item -LiteralPath $fixture -ErrorAction SilentlyContinue}
}
Test-Case 'Launcher' 'corrupt process record is retained and no process is stopped' {
  $fixture=Join-Path $resultsRoot 'corrupt-run.json'
  try{'{'|Set-Content -LiteralPath $fixture;Assert-Throws {Stop-ServiceRun $fixture} '*';if(-not(Test-Path $fixture)){throw 'Corrupt record was removed'}}finally{Remove-Item -LiteralPath $fixture -ErrorAction SilentlyContinue}
}
Test-Case 'Launcher' 'stale record with dead PID can be cleaned up' {
  $fixture=Join-Path $resultsRoot 'dead-run.json'
  @{processes=@(@{name='already-dead';id=2147483647;startTicks='1'})}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $fixture
  Stop-ServiceRun $fixture;if(Test-Path $fixture){throw 'Dead record remained'}
}
Test-Case 'Launcher' 'missing Oracle configuration is rejected before starting anything' {
  $saved=@{};foreach($name in @('DB_URL','DB_USERNAME','DB_PASSWORD')){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$null,'Process')}
  try{Assert-Throws {& (Join-Path $taskRoot 'start-microservices.ps1') @ports -Database Oracle -SkipBuild} '*requires DB_URL*'}finally{foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
}
Test-Case 'Build integrity' 'an added source file invalidates SkipBuild' {
  $fixture=Join-Path $taskRoot ('gateway/integrity-'+[Guid]::NewGuid().ToString()+'.tmp')
  try{'test-only'|Set-Content -LiteralPath $fixture;Assert-Throws {& (Join-Path $taskRoot 'build-before-start.ps1') -CheckOnly} '*Source files changed*'}finally{Remove-Item -LiteralPath $fixture}
}
foreach($property in @('servicesHash','frontendHash')){
  $propertyName=$property
  Test-Case 'Build integrity' "mismatched $propertyName is rejected" {
    $manifest=Join-Path $taskRoot '.runtime/verified-build.json';$bytes=[IO.File]::ReadAllBytes($manifest)
    try{$data=Get-Content -LiteralPath $manifest -Raw|ConvertFrom-Json;$data.$propertyName='invalid';$data|ConvertTo-Json|Set-Content -LiteralPath $manifest
      Assert-Throws {& (Join-Path $taskRoot 'build-before-start.ps1') -CheckOnly} '*changed since*'
    }finally{[IO.File]::WriteAllBytes($manifest,$bytes)}
  }
}
try{
  $savedEnv=@{};foreach($name in @('BACKEND_URL','PORT','GATEWAY_PORT','IDENTITY_URL','REPORTING_URL','BANKING_URL','PAYMENTS_URL','LOAN_URL','AUDIT_URL','LOCAL_DB_URL','FRONTEND_ORIGINS')){$savedEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
  & (Join-Path $taskRoot 'start-microservices.ps1') @ports -SkipBuild
  $ownedStack=$true
  Test-Case 'Network' 'every component listens on loopback only' {
    $lines=& "$env:SystemRoot/System32/netstat.exe" -ano -p tcp
    foreach($testPort in $ports.Values){$matchesForPort=@($lines|Where-Object{$_ -match (':'+$testPort+'\s+.*LISTENING')});if($matchesForPort.Count -ne 1 -or $matchesForPort[0] -notmatch ('\s127\.0\.0\.1:'+$testPort+'\s')){throw "Port $testPort is not uniquely bound to IPv4 loopback"}}
  }
  Test-Case 'Launcher' 'successful startup restores parent environment' {foreach($name in $savedEnv.Keys){if([Environment]::GetEnvironmentVariable($name,'Process') -ne $savedEnv[$name]){throw "$name was not restored"}}}
  Test-Case 'Launcher' 'a second start cannot replace a running stack' {Assert-Throws {& (Join-Path $taskRoot 'start-microservices.ps1') @ports -SkipBuild} '*run record exists*';$null=Http-Check 'http://127.0.0.1:18090/actuator/health' 200 ''}
  & node (Join-Path $taskRoot 'tools/service-edge.mjs') 'http://127.0.0.1:15173' 'http://127.0.0.1:18092' 'http://127.0.0.1:18093' 'http://127.0.0.1:18094' (Join-Path $resultsRoot 'cross-service.json') *> (Join-Path $resultsRoot 'cross-service.log')
  $edgeExit=$LASTEXITCODE
  Test-Case 'Cross-service suite' 'normal, security, boundary, concurrency and load tests' {if($edgeExit -ne 0){throw 'Cross-service cases failed; see cross-service.json'}}
  $edge=Get-Content -LiteralPath (Join-Path $resultsRoot 'cross-service.json') -Raw|ConvertFrom-Json
  & (Join-Path $taskRoot 'tools/prepare-flow-fixtures.ps1')
  & node (Join-Path $taskRoot 'tools/flow-scenarios.mjs') 'http://127.0.0.1:15173' (Join-Path $resultsRoot 'domain-flows.json') *> (Join-Path $resultsRoot 'domain-flows.log')
  $flowExit=$LASTEXITCODE
  Test-Case 'Domain flow suite' 'complete payment, lending, audit, account, card and deposit workflows' {if($flowExit -ne 0){throw 'Domain flow cases failed; see domain-flows.json'}}
  $flows=Get-Content -LiteralPath (Join-Path $resultsRoot 'domain-flows.json') -Raw|ConvertFrom-Json
  if($BrowserGate){
    $browserDone=Join-Path $resultsRoot 'browser-done.json'
    Remove-Item -LiteralPath $browserDone -ErrorAction SilentlyContinue
    [IO.File]::WriteAllText((Join-Path $resultsRoot 'browser-ready.txt'),'http://127.0.0.1:15173')
    $browserDeadline=[DateTime]::UtcNow.AddSeconds(180)
    while(-not(Test-Path -LiteralPath $browserDone) -and [DateTime]::UtcNow -lt $browserDeadline){Start-Sleep -Milliseconds 500}
    Test-Case 'Browser' 'browser verification checkpoint completed' {if(-not(Test-Path -LiteralPath $browserDone)){throw 'Browser checkpoint expired'};$browserResult=Get-Content -LiteralPath $browserDone -Raw|ConvertFrom-Json;if($browserResult.status -ne 'PASS'){throw 'Browser verification did not pass'}}
  }
  $login=Invoke-RestMethod -Uri 'http://127.0.0.1:15173/api/v1/auth/login' -Method Post -ContentType 'application/json' -Body '{"username":"customer","password":"LocalBanking!2026","clientId":"RECOVERY_SUITE"}'
  $token=$login.data.accessToken
  Stop-TestService 'reporting-service'
  Test-Case 'Failure' 'reporting outage lowers readiness while banking stays usable' {$null=Http-Check 'http://127.0.0.1:15173/actuator/health' 503 '';$null=Http-Check 'http://127.0.0.1:15173/api/v1/reporting/statements?accountId=1' 502;$null=Http-Check 'http://127.0.0.1:15173/api/v1/banking/my-dashboard' 200}
  Restart-TestService 'reporting-service' 18093
  Test-Case 'Recovery' 'reporting restart preserves issued statements' {$view=Http-Check "http://127.0.0.1:15173/api/v1/reporting/statements/$($edge.evidence.statementId)" 200;if($view.statementId -ne $edge.evidence.statementId){throw 'Statement continuity failed'}}
  $draftBody=@{jsonrpc='2.0';id=1;method='tools/call';params=@{name='draft_payment';arguments=@{accountId=1;beneficiaryId=$flows.evidence.beneficiaryId;rail='UPI';amount='1.00'}}}|ConvertTo-Json -Depth 8 -Compress
  $draftReply=Invoke-RestMethod -Uri 'http://127.0.0.1:15173/api/v1/assistant/mcp' -Method Post -ContentType 'application/json' -Headers @{Authorization="Bearer $token";'MCP-Protocol-Version'='2026-07-28';'Mcp-Method'='tools/call';'Mcp-Name'='draft_payment'} -Body $draftBody
  if($draftReply.result.isError){throw 'Recovery payment draft failed'}
  $retryIntent=($draftReply.result.content[0].text|ConvertFrom-Json).intentId
  Stop-TestService 'payments-service'
  Test-Case 'Failure' 'payments outage is bounded while lending, audit and banking remain usable' {
    $null=Http-Check 'http://127.0.0.1:15173/actuator/health' 503 ''
    $null=Http-Check 'http://127.0.0.1:15173/api/v1/payments/availability' 502
    $null=Http-Check "http://127.0.0.1:15173/api/v1/assistant/intents/$retryIntent/confirm" 503 $token '{}'
    $null=Http-Check 'http://127.0.0.1:15173/api/v1/loans/my/applications' 200
    $null=Http-Check 'http://127.0.0.1:15173/api/v1/privacy/consents/availability' 200
    $null=Http-Check 'http://127.0.0.1:15173/api/v1/banking/my-dashboard' 200
  }
  Restart-TestService 'payments-service' 18095
  Test-Case 'Recovery' 'payment restart preserves the pending confirmation key and replay result' {
    $route="http://127.0.0.1:15173/api/v1/assistant/intents/$retryIntent/confirm"
    $first=Http-Check $route 200 $token '{}';$second=Http-Check $route 200 $token '{}'
    if($first.resultRef -ne $second.resultRef){throw 'Payment confirmation replay duplicated the instruction'}
    $null=Http-Check "http://127.0.0.1:15173/api/v1/payments/$($first.resultRef)" 200
  }
  Stop-TestService 'loan-service'
  Test-Case 'Failure' 'lending outage leaves payments, audit and banking available' {
    $null=Http-Check 'http://127.0.0.1:15173/actuator/health' 503 ''
    $null=Http-Check 'http://127.0.0.1:15173/api/v1/loans/my/applications' 502
    $null=Http-Check 'http://127.0.0.1:15173/api/v1/payments/availability' 200
    $null=Http-Check 'http://127.0.0.1:15173/api/v1/privacy/consents/availability' 200
  }
  Restart-TestService 'loan-service' 18096
  Test-Case 'Recovery' 'lending restart preserves the serviced facility and outstanding principal' {
    $value=Http-Check "http://127.0.0.1:15173/api/v1/loans/facilities/$($flows.evidence.facilityId)" 200
    if($value.data.PRINCIPAL_OUTSTANDING -ne 11000){throw 'Loan principal changed during restart'}
  }
  Stop-TestService 'audit-service'
  Test-Case 'Failure' 'audit outage leaves statements, payments and banking available' {
    $null=Http-Check 'http://127.0.0.1:15173/actuator/health' 503 ''
    $null=Http-Check 'http://127.0.0.1:15173/api/v1/privacy/consents/availability' 502
    $null=Http-Check 'http://127.0.0.1:15173/api/v1/reporting/statements?accountId=1' 200
    $null=Http-Check 'http://127.0.0.1:15173/api/v1/payments/availability' 200
    $null=Http-Check 'http://127.0.0.1:15173/api/v1/banking/my-dashboard' 200
  }
  Restart-TestService 'audit-service' 18097
  Test-Case 'Recovery' 'audit restart preserves immutable consent history' {
    $history=Http-Check "http://127.0.0.1:15173/api/v1/privacy/consents/history?subjectService=CIF&subjectExternalId=demo-cif-1&purposeCode=$($flows.evidence.purpose)" 200
    if($history.Count -ne 2){throw 'Consent history changed during restart'}
  }
  Stop-TestService 'identity-service'
  Test-Case 'Failure' 'identity outage denies all five dependent services' {foreach($route in @('banking/my-dashboard','reporting/statements?accountId=1','payments/availability','loans/my/applications','privacy/consents/availability')){$null=Http-Check "http://127.0.0.1:15173/api/v1/$route" 503};$null=Http-Check 'http://127.0.0.1:15173/api/v1/auth/me' 502}
  Restart-TestService 'identity-service' 18092
  Test-Case 'Recovery' 'identity restart preserves the existing database-backed session' {$null=Http-Check 'http://127.0.0.1:15173/api/v1/auth/me' 200;$null=Http-Check 'http://127.0.0.1:15173/api/v1/banking/my-dashboard' 200;$null=Http-Check 'http://127.0.0.1:15173/api/v1/reporting/statements?accountId=1' 200}
  $balanceBeforeRestart=(Http-Check 'http://127.0.0.1:15173/api/v1/accounts/1/position' 200).posted
  Stop-TestService 'banking-service'
  Test-Case 'Failure' 'banking outage leaves identity and issued statement reads working' {$null=Http-Check 'http://127.0.0.1:15173/api/v1/banking/my-dashboard' 502;$null=Http-Check 'http://127.0.0.1:15173/api/v1/auth/me' 200;$null=Http-Check "http://127.0.0.1:15173/api/v1/reporting/statements/$($edge.evidence.statementId)" 200}
  Restart-TestService 'banking-service' 18094
  Test-Case 'Recovery' 'banking restart preserves balances without seeding again' {$view=Http-Check 'http://127.0.0.1:15173/api/v1/accounts/1/position' 200;if($view.posted -ne $balanceBeforeRestart){throw 'Balance continuity failed'}}
  Stop-TestService 'gateway'
  Test-Case 'Failure' 'gateway outage returns bounded frontend errors and static pages remain available' {$null=Http-Check 'http://127.0.0.1:15173/api/v1/auth/me' 502;$null=Http-Check 'http://127.0.0.1:15173/login' 200 '';$null=Http-Check 'http://127.0.0.1:18094/api/v1/banking/my-dashboard' 200}
  Restart-TestService 'gateway' 18090
  Test-Case 'Recovery' 'gateway restart restores all routes and aggregate readiness' {$null=Http-Check 'http://127.0.0.1:15173/actuator/health' 200 '';$null=Http-Check 'http://127.0.0.1:15173/api/v1/auth/me' 200}
  Stop-TestService 'frontend'
  Test-Case 'Failure' 'frontend outage leaves gateway and service APIs available' {$null=Http-Check 'http://127.0.0.1:18090/actuator/health' 200 '';$null=Http-Check 'http://127.0.0.1:18090/api/v1/auth/me' 200}
  Restart-TestService 'frontend' 15173
  Test-Case 'Recovery' 'frontend restart restores login assets and authenticated routing' {$null=Http-Check 'http://127.0.0.1:15173/login' 200 '';$null=Http-Check 'http://127.0.0.1:15173/api/v1/auth/me' 200}
  Stop-TestService 'local-database'
  Test-Case 'Failure' 'shared database outage fails every service safely' {$null=Http-Check 'http://127.0.0.1:15173/actuator/health' 503 '';$null=Http-Check 'http://127.0.0.1:18092/api/v1/auth/me' 503;$null=Http-Check 'http://127.0.0.1:15173/api/v1/banking/my-dashboard' 503;$null=Http-Check 'http://127.0.0.1:15173/api/v1/reporting/statements?accountId=1' 503;$null=Http-Check 'http://127.0.0.1:18092/api/v1/auth/login' 503 '' '{"username":"customer","password":"LocalBanking!2026"}'}
  Test-Case 'Failure' 'database outage also denies payments, lending and audit APIs' {foreach($route in @('payments/availability','loans/my/applications','privacy/consents/availability')){$null=Http-Check "http://127.0.0.1:15173/api/v1/$route" 503}}
}finally{if($ownedStack){& (Join-Path $taskRoot 'stop-microservices.ps1');$ownedStack=$false};Save-TestResults}
Test-Case 'Shutdown' 'all owned processes and ports are released' {Assert-ReleasedPorts}

if(-not $SkipLauncherFailureTests){
  foreach($kind in @('Cif','Mfa')){
    $failureKind=$kind
    Test-Case 'Launcher failure' "invalid $failureKind key cleans up partial startup" {
      $badKey=Join-Path $resultsRoot 'invalid-test.key';'not-a-valid-key'|Set-Content -LiteralPath $badKey
      try{$arguments=@{};if($failureKind -eq 'Cif'){$arguments.CifKeyFile=$badKey}else{$arguments.MfaKeyFile=$badKey}
        Assert-Throws {& (Join-Path $taskRoot 'start-microservices.ps1') @ports @arguments -SkipBuild *> (Join-Path $resultsRoot "$failureKind-startup.log")} '*exited*'
        Assert-ReleasedPorts
      }finally{Remove-Item -LiteralPath $badKey -ErrorAction SilentlyContinue}
    }
  }
}
}
Test-Case 'Compatibility' 'Windows PowerShell 5.1 can start a fresh stack and stop it' {
  $started=$false;$compatLauncher=$null
  try{
    $compatArguments=@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',('"'+(Join-Path $taskRoot 'start-microservices.ps1')+'"'),'-GatewayPort','18090','-IdentityPort','18092','-ReportingPort','18093','-BankingPort','18094','-PaymentsPort','18095','-LoanPort','18096','-AuditPort','18097','-DatabasePort','19095','-FrontendPort','15173','-SkipBuild')
    $compatLauncher=Start-Process -FilePath "$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe" -ArgumentList $compatArguments -WindowStyle Hidden -RedirectStandardOutput (Join-Path $resultsRoot 'powershell51.log') -RedirectStandardError (Join-Path $resultsRoot 'powershell51.err.log') -PassThru
    $deadline=[DateTime]::UtcNow.AddSeconds(300);$ready=$false
    while([DateTime]::UtcNow -lt $deadline){try{$health=Invoke-RestMethod -Uri 'http://127.0.0.1:15173/actuator/health' -TimeoutSec 3;if($health.status -eq 'UP'){$ready=$true;break}}catch{};$compatLauncher.Refresh();if($compatLauncher.HasExited -and $compatLauncher.ExitCode -ne 0){throw 'PowerShell 5.1 startup failed'};Start-Sleep -Milliseconds 500}
    if(-not $ready){throw 'PowerShell 5.1 readiness timed out'};$started=$true
    $freshLogin=Invoke-RestMethod -Uri 'http://127.0.0.1:15173/api/v1/auth/login' -Method Post -ContentType 'application/json' -Body '{"username":"customer","password":"LocalBanking!2026"}'
    $position=Http-Check 'http://127.0.0.1:15173/api/v1/accounts/1/position' 200 $freshLogin.data.accessToken
    if($position.posted -ne 75000){throw 'A fresh local stack did not reset synthetic funds'}
  }finally{
    if($started -or(Test-Path -LiteralPath $record)){& "$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $taskRoot 'stop-microservices.ps1') *> (Join-Path $resultsRoot 'powershell51-stop.log');if($LASTEXITCODE -ne 0){throw 'PowerShell 5.1 shutdown failed'}}
    if($compatLauncher){if(-not $compatLauncher.WaitForExit(10000)){Stop-Process -Id $compatLauncher.Id -Force}}
  }
  Assert-ReleasedPorts
}
Save-TestResults
if(@($cases|Where-Object{$_.status -eq 'FAIL'}).Count -gt 0){throw 'Environment tests found failures. See .runtime/environment-tests/operations.json and cross-service.json.'}
Write-Host 'Expanded environment checks passed. Results: .runtime/environment-tests/'
