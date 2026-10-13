param([switch]$SkipBuild,[switch]$Offline)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Net.Http
$taskRoot=$PSScriptRoot
$record=Join-Path $taskRoot '.runtime/microservices-processes.json'
$started=$false
function Assert-HttpStatus {
  param([string]$Url,[string]$Token,[int]$Expected)
  $client=[Net.Http.HttpClient]::new()
  $client.Timeout=[TimeSpan]::FromSeconds(15)
  try {
    if($Token){$client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$Token)}
    $response=$client.GetAsync($Url).GetAwaiter().GetResult()
    try {if([int]$response.StatusCode -ne $Expected){throw "Expected HTTP $Expected from $Url, got $([int]$response.StatusCode)."}}
    finally {$response.Dispose()}
  } finally {$client.Dispose()}
}
function Stop-TestService {
  param([string]$Name)
  $run=Get-Content -LiteralPath $record -Raw|ConvertFrom-Json
  if($run.database -ne 'Local'){throw 'Outage tests are restricted to the local synthetic database.'}
  $entry=@($run.processes|Where-Object{$_.name -eq $Name})
  if($entry.Count -ne 1){throw "Missing $Name process."}
  $process=Get-Process -Id $entry[0].id -ErrorAction Stop
  if($process.StartTime.ToUniversalTime().Ticks -ne [long]$entry[0].startTicks){throw 'Process ownership mismatch.'}
  Stop-Process -Id $process.Id -Force
  [void]$process.WaitForExit(10000)
}
try {
  & (Join-Path $taskRoot 'start-microservices.ps1') -Database Local -GatewayPort 18090 -IdentityPort 18092 -ReportingPort 18093 -BankingPort 18094 -PaymentsPort 18095 -LoanPort 18096 -AuditPort 18097 -DatabasePort 19095 -FrontendPort 15173 -SkipBuild:$SkipBuild -Offline:$Offline
  $started=$true
  & node (Join-Path $taskRoot 'tools/service-smoke.mjs') 'http://127.0.0.1:15173' 'http://127.0.0.1:18092' 'http://127.0.0.1:18093' 'http://127.0.0.1:18094'
  if($LASTEXITCODE -ne 0){throw 'Separate-service HTTP smoke tests failed.'}
  $login=Invoke-RestMethod -Uri 'http://127.0.0.1:15173/api/v1/auth/login' -Method Post -ContentType 'application/json' -Body '{"username":"customer","password":"LocalBanking!2026","clientId":"OUTAGE_SMOKE"}'
  $token=$login.data.accessToken
  Stop-TestService 'reporting-service'
  Assert-HttpStatus 'http://127.0.0.1:15173/actuator/health' '' 503
  Assert-HttpStatus 'http://127.0.0.1:15173/api/v1/reporting/statements?accountId=1' $token 502
  Assert-HttpStatus 'http://127.0.0.1:15173/api/v1/banking/my-dashboard' $token 200
  Stop-TestService 'identity-service'
  Assert-HttpStatus 'http://127.0.0.1:15173/api/v1/banking/my-dashboard' $token 503
  Assert-HttpStatus 'http://127.0.0.1:15173/actuator/health' '' 503
  Write-Host 'Dependency outage checks passed: readiness fails, reporting errors are bounded, identity failure denies access.'
} finally {
  if($started){& (Join-Path $taskRoot 'stop-microservices.ps1')}
}
if(Test-Path -LiteralPath $record){throw 'Test run process record remained after shutdown.'}
foreach($port in @(18090,18092,18093,18094,18095,18096,18097,19095,15173)){
  $probe=[Net.Sockets.TcpClient]::new()
  try{$probe.Connect('127.0.0.1',$port);throw "Test port $port is still listening after shutdown."}
  catch [Net.Sockets.SocketException]{}finally{$probe.Dispose()}
}
Write-Host 'Microservices verification passed and every test process was stopped.'
