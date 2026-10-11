param([int]$BackendPort=8091,[int]$FrontendPort=5174,[bool]$PaymentSimulationEnabled=$true)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$runtime=Join-Path $projectRoot '.runtime'
$jar=Join-Path $runtime 'product-packaging/target/moneybags-integrated-product-verified-1.0.0.jar'
$record=Join-Path $runtime 'oracle-alpha-processes.json'
if(-not(Test-Path -LiteralPath $jar)){throw 'The product-verified backend package is missing.'}
foreach($port in @($BackendPort,$FrontendPort)){
 if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "Application port $port is already in use."}
}
if(Test-Path -LiteralPath $record){
 $previous=Get-Content -LiteralPath $record -Raw|ConvertFrom-Json
 foreach($name in @('backend','frontend','backendLauncher')){
  if($null -ne $previous.$name){
   $process=Get-Process -Id $previous.$name -ErrorAction SilentlyContinue
   if($process -and $process.StartTime.ToUniversalTime().Ticks -eq [long]$previous.($name+'StartTicks')){throw 'An existing Oracle application is running. Stop it with stop-oracle-alpha.ps1 first.'}
  }
 }
 Copy-Item -LiteralPath $record -Destination (Join-Path $runtime 'oracle-alpha-processes.before-product-review.json') -Force
}
. (Join-Path $projectRoot '.env.ps1')
$env:DEPOSIT_INTEREST_ENABLED='true'
$env:CUSTOMER_SIGNUP_ENABLED='true'
$env:PAYMENT_SIMULATION_ENABLED=$PaymentSimulationEnabled.ToString().ToLowerInvariant()
$env:INTERNAL_BANK_CODES='MCPB0000002'
$env:FRONTEND_ORIGINS="http://localhost:$FrontendPort,http://127.0.0.1:$FrontendPort"
$backend=Start-Process -FilePath 'java.exe' -ArgumentList @('-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp','-Djava.net.useSystemProxies=true','-jar',('"'+$jar+'"'),"--server.port=$BackendPort",'--server.address=127.0.0.1') -WindowStyle Hidden -WorkingDirectory $projectRoot -RedirectStandardOutput (Join-Path $runtime 'product-verified-backend.log') -RedirectStandardError (Join-Path $runtime 'product-verified-backend.err.log') -PassThru
$run=@{backend=$backend.Id;backendStartTicks=$backend.StartTime.ToUniversalTime().Ticks.ToString();backendLauncher=$backend.Id;backendLauncherStartTicks=$backend.StartTime.ToUniversalTime().Ticks.ToString();backendPort=$BackendPort;frontendPort=$FrontendPort;backendJar=$jar}
function Save-ProductRun{
 $children=@(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($backend.Id) AND Name='java.exe'"|Where-Object{$_.CommandLine -like "*$jar*"})
 if($children.Count -eq 1){$child=Get-Process -Id $children[0].ProcessId -ErrorAction SilentlyContinue;if($child){$run.backend=$child.Id;$run.backendStartTicks=$child.StartTime.ToUniversalTime().Ticks.ToString()}}
 $run|ConvertTo-Json|Set-Content -LiteralPath $record
}
Save-ProductRun
try{
 $healthy=$false
 for($attempt=0;$attempt -lt 60;$attempt++){
  Save-ProductRun
  try{if((Invoke-RestMethod "http://127.0.0.1:$BackendPort/actuator/health" -TimeoutSec 2).status -eq 'UP'){$healthy=$true;break}}catch{}
  $backend.Refresh();if($backend.HasExited){throw 'The verified Oracle backend exited. Inspect .runtime/product-verified-backend.log.'}
  Start-Sleep -Seconds 1
 }
 if(-not $healthy){throw 'The verified Oracle backend did not become healthy.'}
 $env:BACKEND_URL="http://127.0.0.1:$BackendPort";$env:PORT="$FrontendPort"
 $frontend=Start-Process -FilePath 'node.exe' -ArgumentList 'server.mjs' -WindowStyle Hidden -WorkingDirectory (Join-Path $projectRoot 'frontend/dist') -RedirectStandardOutput (Join-Path $runtime 'product-verified-frontend.log') -RedirectStandardError (Join-Path $runtime 'product-verified-frontend.err.log') -PassThru
 $run.frontend=$frontend.Id;$run.frontendStartTicks=$frontend.StartTime.ToUniversalTime().Ticks.ToString();Save-ProductRun
 $served=$false
 for($attempt=0;$attempt -lt 15;$attempt++){
  try{if((Invoke-WebRequest "http://127.0.0.1:$FrontendPort/" -TimeoutSec 2).StatusCode -eq 200){$served=$true;break}}catch{}
  Start-Sleep -Seconds 1
 }
 if(-not $served){throw 'The verified frontend did not become healthy.'}
 Write-Host "Product-verified Oracle workspace: http://localhost:$FrontendPort"
 Write-Host 'Stop this run with stop-oracle-alpha.ps1. Restart this package with tools/start-product-verified.ps1.'
}catch{
 Save-ProductRun
 & (Join-Path $projectRoot 'stop-oracle-alpha.ps1')
 throw
}
