$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'oracle-config.ps1')
$taskBase = 'http://127.0.0.1:5174/api/v1'
$taskToken = $null
try {
    $taskAdminRow = @($taskOracleSettings.staffCredentials -split "`n" | Where-Object { ($_ -split "`t")[0] -eq 'admin' })[0] -split "`t"
    $taskLogin = Invoke-RestMethod -Uri ($taskBase + '/auth/login') -Method POST -ContentType 'application/json' -Body (@{username=$taskAdminRow[0];password=$taskAdminRow[2].TrimEnd("`r");clientId='ASSISTANT_READINESS_CHECK'} | ConvertTo-Json -Compress) -TimeoutSec 20
    $taskToken = $taskLogin.data.accessToken
    if (-not $taskToken) { throw 'Assistant status login did not produce an access session.' }
    $taskStatus = Invoke-RestMethod -Uri ($taskBase + '/assistant/status') -Headers @{Authorization='Bearer '+$taskToken} -Method GET -TimeoutSec 20
    $taskData = if ($taskStatus.PSObject.Properties.Name -contains 'data') { $taskStatus.data } else { $taskStatus }
    $taskKeys = @($taskOracleSettings.PSObject.Properties.Name)
    [pscustomobject]@{
        liveStatusReachable = $true
        liveConfigured = [bool]$taskData.configured
        availableToolCount = @($taskData.tools).Count
        protectedSettingsHaveOpenRouterKey = ($taskKeys -contains 'openrouterApiKey' -and -not [string]::IsNullOrWhiteSpace($taskOracleSettings.openrouterApiKey))
        protectedSettingsHaveAiProvider = ($taskKeys -contains 'aiProvider' -and -not [string]::IsNullOrWhiteSpace($taskOracleSettings.aiProvider))
        protectedSettingsHaveAiModel = ($taskKeys -contains 'aiModel' -and -not [string]::IsNullOrWhiteSpace($taskOracleSettings.aiModel))
        openRouterRequestsUsed = 0
    } | ConvertTo-Json -Compress
} finally {
    if ($taskToken) { try { $null = Invoke-RestMethod -Uri ($taskBase + '/auth/logout') -Method POST -Headers @{Authorization='Bearer '+$taskToken} -TimeoutSec 12 } catch {} }
    $taskToken=$null
    $taskOracleSettings=$null
}
