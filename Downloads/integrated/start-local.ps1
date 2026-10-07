param([int]$BackendPort=8090,[int]$FrontendPort=5173)
$ErrorActionPreference='Stop'
$taskRoot=$PSScriptRoot
$runtime=Join-Path $taskRoot '.runtime'
New-Item -ItemType Directory -Force $runtime | Out-Null
if(Test-Path (Join-Path $runtime 'processes.json')){throw 'A run record exists. Run stop-local.ps1 before starting another instance.'}
& (Join-Path $taskRoot 'build-before-start.ps1')
$jar=Join-Path $taskRoot 'backend/target/moneybags-integrated-1.0.0.jar'
if(!(Test-Path $jar)){throw 'Maven verify finished without creating the backend JAR.'}
$backend=Start-Process -FilePath 'java' -ArgumentList @('-Djava.net.preferIPv4Stack=true','-jar',('"'+$jar+'"'),'--spring.profiles.active=local',("--server.port="+$BackendPort)) -WindowStyle Hidden -WorkingDirectory $taskRoot -RedirectStandardOutput (Join-Path $runtime 'backend.log') -RedirectStandardError (Join-Path $runtime 'backend.err.log') -PassThru
$env:BACKEND_URL="http://127.0.0.1:$BackendPort"
$env:PORT="$FrontendPort"
$frontend=Start-Process -FilePath 'node' -ArgumentList @('server.mjs') -WindowStyle Hidden -WorkingDirectory (Join-Path $taskRoot 'frontend/dist') -RedirectStandardOutput (Join-Path $runtime 'frontend.log') -RedirectStandardError (Join-Path $runtime 'frontend.err.log') -PassThru
@{backend=$backend.Id;frontend=$frontend.Id;backendStartTicks=$backend.StartTime.ToUniversalTime().Ticks.ToString();frontendStartTicks=$frontend.StartTime.ToUniversalTime().Ticks.ToString()} | ConvertTo-Json | Set-Content (Join-Path $runtime 'processes.json')
$taskDeadline=[datetime]::UtcNow.AddSeconds(45)
$taskReady=$false
while([datetime]::UtcNow -lt $taskDeadline){
 try { $taskHealth=Invoke-RestMethod -Uri "http://127.0.0.1:$BackendPort/actuator/health" -TimeoutSec 2; if($taskHealth.status -eq 'UP'){$taskReady=$true;break} } catch {}
 Start-Sleep -Seconds 1
}
if(!$taskReady){throw 'Backend did not become healthy. See .runtime/backend.log and backend.err.log, then run stop-local.ps1.'}
$frontend.Refresh()
if($frontend.HasExited){throw 'Frontend exited. See .runtime/frontend.err.log and check whether the selected port is already in use.'}
Write-Host "Local workspace: http://localhost:$FrontendPort (in-memory demo database)"


