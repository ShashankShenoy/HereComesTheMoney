$ErrorActionPreference='Stop'
$taskProject=Join-Path (Split-Path -Parent $PSScriptRoot) 'Downloads/integrated'
$taskRecord=Join-Path $taskProject '.runtime/microservices-processes.json'
. (Join-Path $taskProject 'tools/service-runtime.ps1')
$taskRun=Get-Content -LiteralPath $taskRecord -Raw|ConvertFrom-Json
if($taskRun.database -ne 'Oracle'){throw 'Oracle-only guard rejected the run.'}
$taskEntry=@($taskRun.processes|Where-Object {$_.name -eq 'frontend'})[0]
$taskProcess=Get-Process -Id $taskEntry.id
if($taskProcess.StartTime.ToUniversalTime().Ticks -ne [long]$taskEntry.startTicks){throw 'Frontend process ownership changed.'}
Stop-Process -Id $taskProcess.Id -Force
[void]$taskProcess.WaitForExit(10000)
$taskOldPort=$env:PORT;$taskOldBackend=$env:BACKEND_URL
try{
 $env:PORT='15173';$env:BACKEND_URL='http://127.0.0.1:18090'
 $taskNewProcess=Start-Process -FilePath (Get-Command node).Source -ArgumentList @('server.mjs') -WorkingDirectory (Join-Path $taskProject 'frontend/dist') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskProject '.runtime/frontend.log') -RedirectStandardError (Join-Path $taskProject '.runtime/frontend.err.log') -PassThru
 $taskEntry.id=$taskNewProcess.Id;$taskEntry.startTicks=$taskNewProcess.StartTime.ToUniversalTime().Ticks.ToString()
 Save-ServiceRun $taskRecord $taskRun
 Write-Output 'Owned Oracle frontend restarted with the updated assets.'
}finally{$env:PORT=$taskOldPort;$env:BACKEND_URL=$taskOldBackend}
