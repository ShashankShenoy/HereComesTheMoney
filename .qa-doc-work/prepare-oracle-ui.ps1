$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'oracle-config.ps1')
$taskApi = 'http://127.0.0.1:15173/api/v1'
$taskRun = 'qa_m12_' + [DateTime]::Now.ToString('yyyyMMdd') + '_' + [Guid]::NewGuid().ToString('N').Substring(0,6)
$taskPassword = 'OracleQa!2026Secure'
$taskRootToken = $null
$taskCheckerToken = $null
function Invoke-QAApi([string]$Method,[string]$Path,[string]$Token,$Body) {
    $taskHeaders=@{'X-Correlation-ID'=[Guid]::NewGuid().ToString()}
    if ($Token) { $taskHeaders.Authorization='Bearer '+$Token }
    $taskArgs=@{Uri=$taskApi+$Path;Method=$Method;Headers=$taskHeaders;TimeoutSec=60}
    if ($null -ne $Body) { $taskArgs.ContentType='application/json';$taskArgs.Body=($Body|ConvertTo-Json -Depth 12 -Compress) }
    $taskValue=Invoke-RestMethod @taskArgs
    if ($taskValue.PSObject.Properties.Name -contains 'data') { return $taskValue.data }
    return $taskValue
}
try {
    $taskStaff=@($taskOracleSettings.staffCredentials -split "`n" | ForEach-Object {$_.TrimEnd("`r") -split "`t"})
    $taskAdminRow=@($taskOracleSettings.staffCredentials -split "`n" | Where-Object {($_ -split "`t")[0] -eq 'admin'})[0] -split "`t"
    $taskCheckerRow=@($taskOracleSettings.staffCredentials -split "`n" | Where-Object {($_ -split "`t")[0] -eq 'checker1'})[0] -split "`t"
    $taskRootToken=(Invoke-QAApi 'POST' '/auth/login' '' @{username=$taskAdminRow[0];password=$taskAdminRow[2].TrimEnd("`r");clientId='ORACLE_M12_UI_FIXTURE'}).accessToken
    $taskCheckerToken=(Invoke-QAApi 'POST' '/auth/login' '' @{username=$taskCheckerRow[0];password=$taskCheckerRow[2].TrimEnd("`r");clientId='ORACLE_M12_UI_FIXTURE'}).accessToken
    $taskPermissionCodes=@('IAM_USER_READ','IAM_USER_CREATE','IAM_USER_MANAGE','IAM_ROLE_MANAGE','IAM_ACCESS_MANAGE','IAM_AUDIT_READ','CIF_READ','CIF_CREATE','CIF_UPDATE','KYC_UPLOAD')
    $taskCatalog=Invoke-QAApi 'GET' '/iam/permissions' $taskRootToken $null
    $taskPermissionIds=@($taskCatalog | Where-Object {$_.permissionCode -in $taskPermissionCodes} | ForEach-Object {$_.permissionId})
    if ($taskPermissionIds.Count -ne $taskPermissionCodes.Count) { throw 'Required Oracle test permissions are missing.' }
    $taskRole=Invoke-QAApi 'POST' '/iam/roles' $taskRootToken @{roleCode=$taskRun.ToUpperInvariant();displayName='Temporary Oracle M12 QA';sensitive=$true;permissionIds=$taskPermissionIds}
    $taskIdentity=(Invoke-QAApi 'POST' '/customer-access/users' $taskRootToken @{username=$taskRun+'_maker';userType='EMPLOYEE';employeeRef=$taskRun;password=$taskPassword}).user
    $taskIdentity=Invoke-QAApi 'PATCH' ('/iam/users/'+$taskIdentity.userId+'/status') $taskRootToken @{rowVersion=$taskIdentity.rowVersion;status='ACTIVE';reason='Activate temporary Oracle UI test identity'}
    $taskRequest=Invoke-QAApi 'POST' '/iam/access-requests' $taskRootToken @{targetUserId=$taskIdentity.userId;roleId=$taskRole.roleId;changeType='GRANT';scopeType='GLOBAL';validFrom=[DateTimeOffset]::UtcNow.AddMinutes(-1).ToString('o');validTo=[DateTimeOffset]::UtcNow.AddHours(2).ToString('o');reason='Temporary Oracle Module 1 and 2 browser verification';dimensions=@()}
    $null=Invoke-QAApi 'POST' ('/iam/access-requests/'+$taskRequest.requestId+'/decision') $taskCheckerToken @{rowVersion=$taskRequest.rowVersion;decision='APPROVED'}
    $taskMakerToken=(Invoke-QAApi 'POST' '/auth/login' '' @{username=$taskIdentity.username;password=$taskPassword;clientId='ORACLE_M12_UI_FIXTURE'}).accessToken
    $taskCustomer=Invoke-QAApi 'POST' '/cif/customers' $taskMakerToken @{partyType='INDIVIDUAL';legalName=$taskRun+' Customer';dateOfBirth='1990-01-01';homeBranchRef='MUM001';segmentCode='RETAIL'}
    $taskCif=$taskCustomer.customer.CIF_ID
    $null=Invoke-QAApi 'POST' ('/cif/customers/'+$taskCif+'/contacts') $taskMakerToken @{contactType='EMAIL';value=$taskRun+'@example.test'}
    $null=Invoke-QAApi 'POST' ('/cif/customers/'+$taskCif+'/identifiers') $taskMakerToken @{identifierType='QA_TEST_ID';value=[Guid]::NewGuid().ToString();issuerCode='SYNTHETIC_QA'}
    $null=Invoke-QAApi 'POST' ('/cif/customers/'+$taskCif+'/addresses') $taskMakerToken @{addressType='RESIDENTIAL';line1='Synthetic QA Road';city='Mumbai';countryCode='IN'}
    $taskFixture=@{database='Oracle';run=$taskRun;username=$taskIdentity.username;userId=$taskIdentity.userId;roleId=$taskRole.roleId;cifId=$taskCif;caseId=$taskCustomer.cases[0].CASE_ID;expiresAt=$taskRequest.validTo}
    $taskFixture|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $taskProject '.runtime/oracle-m12/ui-fixture.json')
    $taskFixture|ConvertTo-Json
} finally {
    foreach ($taskSession in @($taskRootToken,$taskCheckerToken,$taskMakerToken)) {
        if ($taskSession) { try {$null=Invoke-QAApi 'POST' '/auth/logout' $taskSession $null} catch {} }
    }
    $taskRootToken=$null;$taskCheckerToken=$null;$taskMakerToken=$null;$taskStaff=$null;$taskAdminRow=$null;$taskCheckerRow=$null;$taskOracleSettings=$null
}
