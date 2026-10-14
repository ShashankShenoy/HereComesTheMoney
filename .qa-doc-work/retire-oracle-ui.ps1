$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'oracle-config.ps1')
$taskFixturePath = Join-Path $taskProject '.runtime/oracle-m12/ui-fixture.json'
$taskFixture = Get-Content -LiteralPath $taskFixturePath -Raw | ConvertFrom-Json
if ($taskFixture.database -ne 'Oracle' -or $taskFixture.run -notmatch '^qa_m12_\d{8}_[a-f0-9]{6}$' -or $taskFixture.username -ne ($taskFixture.run + '_maker')) {
    throw 'Synthetic Oracle fixture guard rejected cleanup.'
}
$taskApi = 'http://127.0.0.1:15173/api/v1'
function Invoke-QAApi([string]$Method, [string]$Path, [string]$Token, $Body) {
    $taskHeaders = @{ 'X-Correlation-ID' = [Guid]::NewGuid().ToString() }
    if ($Token) { $taskHeaders.Authorization = 'Bearer ' + $Token }
    $taskArgs = @{ Uri = $taskApi + $Path; Method = $Method; Headers = $taskHeaders; TimeoutSec = 60 }
    if ($null -ne $Body) { $taskArgs.ContentType = 'application/json'; $taskArgs.Body = ($Body | ConvertTo-Json -Depth 12 -Compress) }
    $taskValue = Invoke-RestMethod @taskArgs
    if ($taskValue.PSObject.Properties.Name -contains 'data') { return $taskValue.data }
    return $taskValue
}
$taskAdminToken = $null
$taskMakerToken = $null
try {
    $taskAdminRow = @($taskOracleSettings.staffCredentials -split "`n" | Where-Object { ($_ -split "`t")[0] -eq 'admin' })[0] -split "`t"
    $taskAdminToken = (Invoke-QAApi 'POST' '/auth/login' '' @{ username = $taskAdminRow[0]; password = $taskAdminRow[2].TrimEnd("`r"); clientId = 'ORACLE_M12_CLEANUP' }).accessToken
    $taskMakerToken = (Invoke-QAApi 'POST' '/auth/login' '' @{ username = $taskFixture.username; password = 'OracleQa!2026Secure'; clientId = 'ORACLE_M12_CLEANUP' }).accessToken
    $taskCustomer = Invoke-QAApi 'GET' ('/cif/customers/' + $taskFixture.cifId) $taskMakerToken $null
    $taskUser = Invoke-QAApi 'GET' ('/iam/users/' + $taskFixture.userId) $taskAdminToken $null
    $taskRole = Invoke-QAApi 'GET' ('/iam/roles/' + $taskFixture.roleId) $taskAdminToken $null
    $taskLegalName = @($taskCustomer.names | Where-Object { $_.NAME_TYPE -eq 'LEGAL' -and $null -eq $_.VALID_TO } | Select-Object -First 1)[0].FULL_NAME
    if ($taskCustomer.customer.CIF_ID -ne $taskFixture.cifId -or $taskLegalName -ne ($taskFixture.run + ' Customer')) { throw 'Customer identity mismatch.' }
    if ($taskUser.user.userId -ne $taskFixture.userId -or $taskUser.user.username -ne $taskFixture.username) { throw 'User identity mismatch.' }
    if ($taskRole.role.roleId -ne $taskFixture.roleId -or $taskRole.role.roleCode -ne $taskFixture.run.ToUpperInvariant()) { throw 'Role identity mismatch.' }
    if ($taskCustomer.customer.STATUS -ne 'CLOSED') {
        $null = Invoke-QAApi 'PATCH' ('/cif/customers/' + $taskFixture.cifId + '/status') $taskMakerToken @{ rowVersion = $taskCustomer.customer.ROW_VERSION; status = 'CLOSED'; reason = 'Retire synthetic Module 1 and 2 Oracle QA fixture' }
    }
    if ($taskUser.user.status -ne 'DISABLED') {
        $null = Invoke-QAApi 'PATCH' ('/iam/users/' + $taskFixture.userId + '/status') $taskAdminToken @{ rowVersion = $taskUser.user.rowVersion; status = 'DISABLED'; reason = 'Retire synthetic Module 1 and 2 Oracle QA fixture' }
    }
    if ($taskRole.role.status -ne 'RETIRED') {
        $taskPermissionIds = @($taskRole.permissions | ForEach-Object { $_.permissionId })
        $null = Invoke-QAApi 'PUT' ('/iam/roles/' + $taskFixture.roleId) $taskAdminToken @{ rowVersion = $taskRole.role.rowVersion; displayName = $taskRole.role.displayName; sensitive = ($taskRole.role.sensitiveFlag -eq 'Y'); status = 'RETIRED'; permissionIds = $taskPermissionIds }
    }
    $taskCustomer = Invoke-QAApi 'GET' ('/cif/customers/' + $taskFixture.cifId) $taskAdminToken $null
    $taskUser = Invoke-QAApi 'GET' ('/iam/users/' + $taskFixture.userId) $taskAdminToken $null
    $taskRole = Invoke-QAApi 'GET' ('/iam/roles/' + $taskFixture.roleId) $taskAdminToken $null
    if ($taskCustomer.customer.STATUS -ne 'CLOSED' -or $taskUser.user.status -ne 'DISABLED' -or $taskRole.role.status -ne 'RETIRED') { throw 'Synthetic fixture cleanup verification failed.' }
    @{ database = 'Oracle'; run = $taskFixture.run; customerStatus = $taskCustomer.customer.STATUS; userStatus = $taskUser.user.status; roleStatus = $taskRole.role.status; verifiedAt = [DateTimeOffset]::UtcNow.ToString('o') } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskProject '.runtime/oracle-m12/ui-fixture-retired.json')
    Write-Output 'Synthetic Oracle fixture retired: customer CLOSED, user DISABLED, role RETIRED.'
} finally {
    foreach ($taskSession in @($taskAdminToken, $taskMakerToken)) {
        if ($taskSession) { try { $null = Invoke-QAApi 'POST' '/auth/logout' $taskSession $null } catch {} }
    }
    $taskAdminToken = $null
    $taskMakerToken = $null
    $taskOracleSettings = $null
}
