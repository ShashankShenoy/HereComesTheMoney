$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'oracle-config.ps1')
$taskRecordPath = Join-Path $taskProject '.runtime/microservices-processes.json'
if (Test-Path -LiteralPath $taskRecordPath) { throw 'Another service run is recorded; outage test will not touch it.' }
$taskBase = 'http://127.0.0.1:18090/api/v1'
$taskFrontend = 'http://127.0.0.1:15173/'
$taskAdminToken = $null
$taskResults = [ordered]@{ database = 'Oracle'; startedAt = [DateTimeOffset]::UtcNow.ToString('o') }
function Get-QAResponse([string]$Path, [string]$Token) {
    $taskHeaders = @{ 'X-Correlation-ID' = [Guid]::NewGuid().ToString() }
    if ($Token) { $taskHeaders.Authorization = 'Bearer ' + $Token }
    $taskResponse = Invoke-WebRequest -Uri ($taskBase + $Path) -Headers $taskHeaders -SkipHttpErrorCheck -TimeoutSec 12
    $taskCode = $null
    try { $taskCode = ($taskResponse.Content | ConvertFrom-Json).code } catch {}
    return @{ status = [int]$taskResponse.StatusCode; code = $taskCode }
}
function Start-QAStack {
    $taskLog = Join-Path $taskProject '.runtime/oracle-m12/outage-start.log'
    & (Join-Path $taskProject 'start-microservices.ps1') -Database Oracle -GatewayPort 18090 -IdentityPort 18092 -ReportingPort 18093 -BankingPort 18094 -PaymentsPort 18095 -LoanPort 18096 -AuditPort 18097 -FrontendPort 15173 -SkipBuild -StartupTimeoutSeconds 180 -CifKeyFile $env:M12_CIF_KEY_FILE -MfaKeyFile $env:M12_MFA_KEY_FILE *> $taskLog
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $taskRecordPath)) { throw 'Oracle service stack did not start for outage test.' }
}
function Stop-QAStack {
    if (Test-Path -LiteralPath $taskRecordPath) {
        & (Join-Path $taskProject 'stop-microservices.ps1') | Out-Null
    }
}
function Stop-OwnedService([string]$Name) {
    $taskRecord = Get-Content -LiteralPath $taskRecordPath -Raw | ConvertFrom-Json
    if ($taskRecord.database -ne 'Oracle') { throw 'Refusing to stop a non-Oracle service run.' }
    $taskEntry = @($taskRecord.processes | Where-Object { $_.name -eq $Name })
    if ($taskEntry.Count -ne 1) { throw 'Expected owned service was not found.' }
    $taskProcess = Get-Process -Id ([int]$taskEntry[0].id) -ErrorAction Stop
    if ($taskProcess.StartTime.ToUniversalTime().Ticks -ne [long]$taskEntry[0].startTicks) { throw 'Service process ownership changed.' }
    Stop-Process -Id $taskProcess.Id -Force -ErrorAction Stop
    if (-not $taskProcess.WaitForExit(10000)) { throw 'Owned service did not stop.' }
}
try {
    Start-QAStack
    $taskAdminRow = @($taskOracleSettings.staffCredentials -split "`n" | Where-Object { ($_ -split "`t")[0] -eq 'admin' })[0] -split "`t"
    $taskLogin = Invoke-RestMethod -Uri ($taskBase + '/auth/login') -Method POST -ContentType 'application/json' -Body (@{ username = $taskAdminRow[0]; password = $taskAdminRow[2].TrimEnd("`r"); clientId = 'ORACLE_M12_OUTAGE' } | ConvertTo-Json -Compress) -TimeoutSec 15
    $taskAdminToken = $taskLogin.data.accessToken
    if (-not $taskAdminToken) { throw 'Oracle outage admin sign-in failed.' }
    $taskResults.baselineIam = Get-QAResponse '/iam/dashboard' $taskAdminToken
    $taskResults.baselineBanking = Get-QAResponse '/banking/overview' $taskAdminToken
    if ($taskResults.baselineIam.status -ne 200 -or $taskResults.baselineBanking.status -ne 200) { throw 'Baseline service request failed.' }

    Stop-OwnedService 'banking-service'
    $taskResults.bankingDuringOutage = Get-QAResponse '/banking/overview' $taskAdminToken
    $taskResults.iamDuringBankingOutage = Get-QAResponse '/iam/dashboard' $taskAdminToken
    $taskResults.staticDuringBankingOutage = [int](Invoke-WebRequest -Uri $taskFrontend -SkipHttpErrorCheck -TimeoutSec 12).StatusCode
    if ($taskResults.bankingDuringOutage.status -ne 502 -or $taskResults.bankingDuringOutage.code -ne 'SERVICE_UNAVAILABLE' -or $taskResults.iamDuringBankingOutage.status -ne 200 -or $taskResults.staticDuringBankingOutage -ne 200) { throw 'Banking outage contract failed.' }

    Stop-QAStack
    Start-QAStack
    $taskResults.bankingAfterRecovery = Get-QAResponse '/banking/overview' $taskAdminToken
    if ($taskResults.bankingAfterRecovery.status -ne 200) { throw 'Banking did not recover.' }

    Stop-OwnedService 'identity-service'
    $taskResults.iamDuringIdentityOutage = Get-QAResponse '/iam/dashboard' $taskAdminToken
    $taskResults.cifWithExistingSessionDuringIdentityOutage = Get-QAResponse '/cif/customers' $taskAdminToken
    $taskResults.cifWithoutSessionDuringIdentityOutage = Get-QAResponse '/cif/customers' $null
    if ($taskResults.iamDuringIdentityOutage.status -ne 502 -or $taskResults.iamDuringIdentityOutage.code -ne 'SERVICE_UNAVAILABLE' -or $taskResults.cifWithoutSessionDuringIdentityOutage.status -eq 200) { throw 'Identity outage authorization contract failed.' }

    Stop-QAStack
    Start-QAStack
    $taskResults.iamAfterRecovery = Get-QAResponse '/iam/dashboard' $taskAdminToken
    $taskResults.bankingAfterIdentityRecovery = Get-QAResponse '/banking/overview' $taskAdminToken
    if ($taskResults.iamAfterRecovery.status -ne 200 -or $taskResults.bankingAfterIdentityRecovery.status -ne 200) { throw 'Identity/banking recovery failed.' }
    $taskResults.completedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $taskResults | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskProject '.runtime/oracle-m12/outage-evidence.json')
    Write-Output 'Controlled Oracle service outage and recovery checks passed; sanitized evidence saved.'
} finally {
    if ($taskAdminToken -and (Test-Path -LiteralPath $taskRecordPath)) {
        try { $null = Invoke-RestMethod -Uri ($taskBase + '/auth/logout') -Method POST -Headers @{ Authorization = 'Bearer ' + $taskAdminToken } -TimeoutSec 12 } catch {}
    }
    Stop-QAStack
    $taskAdminToken = $null
    $taskOracleSettings = $null
}
