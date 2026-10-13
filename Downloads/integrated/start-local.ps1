param([int]$BackendPort=8090,[int]$FrontendPort=5173,[switch]$SkipBuild,[switch]$Offline,
      [int]$IdentityPort=8092,[int]$ReportingPort=8093,[int]$BankingPort=8094,[int]$DatabasePort=9095)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'start-microservices.ps1') -Database Local -GatewayPort $BackendPort -FrontendPort $FrontendPort -IdentityPort $IdentityPort -ReportingPort $ReportingPort -BankingPort $BankingPort -DatabasePort $DatabasePort -SkipBuild:$SkipBuild -Offline:$Offline
