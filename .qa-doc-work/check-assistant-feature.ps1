$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'oracle-config.ps1')
$taskBase = 'http://127.0.0.1:5174/api/v1'
$taskToken = $null
try {
    $taskAdminRow = @($taskOracleSettings.staffCredentials -split "`n" | Where-Object { ($_ -split "`t")[0] -eq 'admin' })[0] -split "`t"
    $taskLogin = Invoke-RestMethod -Uri ($taskBase + '/auth/login') -Method POST -ContentType 'application/json' -Body (@{username=$taskAdminRow[0];password=$taskAdminRow[2].TrimEnd("`r");clientId='ASSISTANT_FEATURE_CHECK'} | ConvertTo-Json -Compress) -TimeoutSec 20
    $taskToken = $taskLogin.data.accessToken
    if (-not $taskToken) { throw 'Assistant feature login did not produce an access session.' }
    $taskHeaders = @{Authorization='Bearer '+$taskToken;'MCP-Protocol-Version'='2026-07-28';'Mcp-Method'='tools/list'}
    $taskList = Invoke-RestMethod -Uri ($taskBase + '/assistant/mcp') -Method POST -Headers $taskHeaders -ContentType 'application/json' -Body (@{jsonrpc='2.0';id=1;method='tools/list';params=@{}} | ConvertTo-Json -Compress) -TimeoutSec 25
    $taskListData = if ($taskList.PSObject.Properties.Name -contains 'data') { $taskList.data } else { $taskList }
    if ($taskListData.error) { throw ('MCP tool discovery failed: ' + $taskListData.error.code) }
    $taskNames = @($taskListData.result.tools | ForEach-Object { $_.name })
    if ($taskNames -notcontains 'list_scoped_accounts') { throw 'Scoped-account feature not available to the officer.' }
    if ($taskNames -notcontains 'search_customers') { throw 'Customer-search feature not available to the officer.' }
    if ($taskNames -contains 'list_my_accounts') { throw 'Customer-only account tool exposed to the officer.' }

    $taskHeaders['Mcp-Method'] = 'tools/call'
    $taskHeaders['Mcp-Name'] = 'list_scoped_accounts'
    $taskCall = Invoke-RestMethod -Uri ($taskBase + '/assistant/mcp') -Method POST -Headers $taskHeaders -ContentType 'application/json' -Body (@{jsonrpc='2.0';id=2;method='tools/call';params=@{name='list_scoped_accounts';arguments=@{}}} | ConvertTo-Json -Depth 8 -Compress) -TimeoutSec 35
    $taskCallData = if ($taskCall.PSObject.Properties.Name -contains 'data') { $taskCall.data } else { $taskCall }
    if ($taskCallData.error -or $taskCallData.result.isError) { throw 'Scoped-account assistant tool returned an error.' }
    $taskAccounts = @($taskCallData.result.content[0].text | ConvertFrom-Json)
    if ($taskAccounts.Count -eq 0) { throw 'Scoped-account tool returned no Oracle-backed accounts to verify.' }
    foreach ($taskAccount in $taskAccounts) {
        $taskFields = @($taskAccount.PSObject.Properties.Name)
        if ($taskFields -contains 'ACCOUNT_NUMBER' -or $taskFields -contains 'accountNumber') { throw 'Unmasked account number present in assistant tool result.' }
        if ([string]$taskAccount.accountEnding -notmatch '^[0-9]{1,4}$') { throw 'Account ending is missing or not masked.' }
    }

    $taskHeaders['Mcp-Name'] = 'list_my_accounts'
    $taskDenied = Invoke-RestMethod -Uri ($taskBase + '/assistant/mcp') -Method POST -Headers $taskHeaders -ContentType 'application/json' -Body (@{jsonrpc='2.0';id=3;method='tools/call';params=@{name='list_my_accounts';arguments=@{}}} | ConvertTo-Json -Depth 8 -Compress) -TimeoutSec 25
    $taskDeniedData = if ($taskDenied.PSObject.Properties.Name -contains 'data') { $taskDenied.data } else { $taskDenied }
    if (-not $taskDeniedData.result.isError -or $taskDeniedData.result.content[0].text -notmatch '^TOOL_UNAVAILABLE:') { throw 'Customer-only tool was not denied for the officer.' }

    $taskHeaders['Mcp-Name'] = 'search_customers'
    $taskSearch = Invoke-RestMethod -Uri ($taskBase + '/assistant/mcp') -Method POST -Headers $taskHeaders -ContentType 'application/json' -Body (@{jsonrpc='2.0';id=4;method='tools/call';params=@{name='search_customers';arguments=@{query='NONEXISTENT_ASSISTANT_QA_20261014_7F92B1'}}} | ConvertTo-Json -Depth 8 -Compress) -TimeoutSec 25
    $taskSearchData = if ($taskSearch.PSObject.Properties.Name -contains 'data') { $taskSearch.data } else { $taskSearch }
    if ($taskSearchData.error -or $taskSearchData.result.isError) { throw 'Synthetic customer search failed.' }
    $taskSearchPayload = [string]$taskSearchData.result.content[0].text
    if ($taskSearchPayload.Trim() -ne '[]') { throw 'Synthetic search did not return an empty result; do not send it to the provider.' }

    [pscustomobject]@{
        feature = 'Oracle-backed assistant MCP account lookup'
        toolDiscovery = 'passed'
        accountRead = 'passed'
        accountCount = $taskAccounts.Count
        accountMasking = 'passed'
        audienceIsolation = 'passed'
        syntheticCustomerSearch = 'empty_result_confirmed'
        openRouterRequestsUsedByFeatureTest = 0
    } | ConvertTo-Json -Compress
} finally {
    if ($taskToken) { try { $null = Invoke-RestMethod -Uri ($taskBase + '/auth/logout') -Method POST -Headers @{Authorization='Bearer '+$taskToken} -TimeoutSec 12 } catch {} }
    $taskToken=$null
    $taskOracleSettings=$null
}
