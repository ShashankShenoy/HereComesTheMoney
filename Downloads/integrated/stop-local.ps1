$ErrorActionPreference='Stop'
$record=Join-Path $PSScriptRoot '.runtime/processes.json'
if(!(Test-Path $record)){Write-Host 'No local run record.';return}
$run=Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
foreach($name in @('backend','frontend')){
 $process=Get-Process -Id $run.$name -ErrorAction SilentlyContinue
 $ticksProperty=$name+'StartTicks'
 $legacyProperty=$name+'Start'
 if($run.$ticksProperty){$expected=[long]$run.$ticksProperty}else{$expected=([datetime]$run.$legacyProperty).ToUniversalTime().Ticks}
 if($process -and $process.StartTime.ToUniversalTime().Ticks -eq $expected){
  & "$env:SystemRoot/System32/taskkill.exe" /PID $process.Id /T /F | Out-Null
  if($LASTEXITCODE -ne 0){throw "Could not stop recorded process $($process.Id). The run record has been retained."}
 }
}
Remove-Item -LiteralPath $record
Write-Host 'Stopped the recorded local Money Bags processes.'
