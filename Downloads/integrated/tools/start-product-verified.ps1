param([int]$BackendPort=8091,[int]$FrontendPort=5174,[bool]$PaymentSimulationEnabled=$true)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
& (Join-Path $projectRoot 'build-before-start.ps1') -CheckOnly
& (Join-Path $projectRoot 'start-oracle-alpha.ps1') -BackendPort $BackendPort -FrontendPort $FrontendPort -SkipBuild -PaymentSimulationEnabled $PaymentSimulationEnabled
