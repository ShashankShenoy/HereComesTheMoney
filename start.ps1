param([ValidateSet('Local','Oracle')][string]$Database='Local', [int]$GatewayPort=8090,
      [int]$FrontendPort=5173, [int]$IdentityPort=8092, [int]$ReportingPort=8093,
      [int]$BankingPort=8094, [int]$PaymentsPort=8095, [int]$LoanPort=8096, [int]$AuditPort=8097,
      [int]$DatabasePort=9095, [string]$CifKeyFile, [string]$MfaKeyFile,
      [switch]$SkipBuild, [switch]$Offline)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Downloads/integrated/start-microservices.ps1') @PSBoundParameters
