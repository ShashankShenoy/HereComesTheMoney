$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'oracle-config.ps1')
if ($taskOracleSettings.aiProvider -ne 'openrouter' -or [string]::IsNullOrWhiteSpace($taskOracleSettings.openrouterApiKey) -or [string]::IsNullOrWhiteSpace($taskOracleSettings.aiModel)) { throw 'OpenRouter settings are not ready; no provider request sent.' }
$taskBase = 'http://127.0.0.1:5174/api/v1'
$taskToken = $null
$taskHandler = [System.Net.Http.HttpClientHandler]::new()
$taskHandler.AllowAutoRedirect = $false
$taskClient = [System.Net.Http.HttpClient]::new($taskHandler)
$taskClient.Timeout = [TimeSpan]::FromSeconds(45)
$taskResult = [ordered]@{ feature='OpenRouter function call to synthetic Oracle-backed customer search'; requestsSent=0; firstStatus=$null; toolSelected=$false; toolExecuted=$false; finalStatus=$null; finalAnswerPresent=$false }
function Invoke-TaskModel([object]$body) {
    if ($taskResult.requestsSent -ge 2) { throw 'Tool-flow provider request cap reached.' }
    $taskRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, 'https://openrouter.ai/api/v1/responses')
    try {
        $taskRequest.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $taskOracleSettings.openrouterApiKey)
        $taskRequest.Headers.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new('application/json'))
        $taskRequest.Content = [System.Net.Http.StringContent]::new(($body | ConvertTo-Json -Depth 30 -Compress), [System.Text.Encoding]::UTF8, 'application/json')
        $taskResult.requestsSent++
        $taskResponse = $taskClient.SendAsync($taskRequest).GetAwaiter().GetResult()
        try {
            $taskStatus = [int]$taskResponse.StatusCode
            $taskPayload = $null
            if ($taskResponse.IsSuccessStatusCode) { $taskPayload = $taskResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json }
            return [pscustomobject]@{status=$taskStatus;payload=$taskPayload}
        } finally { $taskResponse.Dispose() }
    } finally { $taskRequest.Dispose() }
}
try {
    $taskAdminRow = @($taskOracleSettings.staffCredentials -split "`n" | Where-Object { ($_ -split "`t")[0] -eq 'admin' })[0] -split "`t"
    $taskLogin = Invoke-RestMethod -Uri ($taskBase + '/auth/login') -Method POST -ContentType 'application/json' -Body (@{username=$taskAdminRow[0];password=$taskAdminRow[2].TrimEnd("`r");clientId='ASSISTANT_TOOLFLOW_CHECK'} | ConvertTo-Json -Compress) -TimeoutSec 20
    $taskToken = $taskLogin.data.accessToken
    if (-not $taskToken) { throw 'Could not open an Oracle-backed officer session.' }
    $taskSearchTerm = 'NONEXISTENT_ASSISTANT_QA_20261014_7F92B1'
    $taskTool = @{type='function';name='search_customers';description='Search for customers in the signed-in bank officer authorized scope.';parameters=@{type='object';properties=@{query=@{type='string'}};required=@('query');additionalProperties=$false};strict=$true}
    $taskInput = @(@{role='user';content="Use the search_customers tool to find any customer matching $taskSearchTerm. Tell me whether a match exists."})
    $taskFirstBody = @{model=$taskOracleSettings.aiModel;instructions='You are testing an authorized banking assistant. Use the provided tool once before answering. Do not invent customer data.';input=$taskInput;tools=@($taskTool);tool_choice='required';parallel_tool_calls=$false;max_output_tokens=256;store=$false}
    $taskFirst = Invoke-TaskModel $taskFirstBody
    $taskResult.firstStatus = $taskFirst.status
    if ($taskFirst.status -ne 200) { return }
    if ($taskFirst.payload.status -ne 'completed') { return }
    $taskCalls = @($taskFirst.payload.output | Where-Object { $_.type -eq 'function_call' })
    if ($taskCalls.Count -ne 1 -or $taskCalls[0].name -ne 'search_customers') { return }
    $taskArgs = $taskCalls[0].arguments | ConvertFrom-Json
    if (@($taskArgs.PSObject.Properties.Name).Count -ne 1 -or $taskArgs.query -ne $taskSearchTerm) { return }
    $taskResult.toolSelected = $true
    $taskHeaders = @{Authorization='Bearer '+$taskToken;'MCP-Protocol-Version'='2026-07-28';'Mcp-Method'='tools/call';'Mcp-Name'='search_customers'}
    $taskCall = Invoke-RestMethod -Uri ($taskBase + '/assistant/mcp') -Method POST -Headers $taskHeaders -ContentType 'application/json' -Body (@{jsonrpc='2.0';id=1;method='tools/call';params=@{name='search_customers';arguments=@{query=$taskSearchTerm}}} | ConvertTo-Json -Depth 8 -Compress) -TimeoutSec 35
    $taskCallData = if ($taskCall.PSObject.Properties.Name -contains 'data') { $taskCall.data } else { $taskCall }
    if ($taskCallData.error -or $taskCallData.result.isError) { return }
    $taskToolOutput = [string]$taskCallData.result.content[0].text
    if ($taskToolOutput.Trim() -ne '[]') { return }
    $taskResult.toolExecuted = $true
    $taskInput += @($taskFirst.payload.output)
    $taskInput += @{type='function_call_output';call_id=$taskCalls[0].call_id;output=$taskToolOutput}
    $taskFinalBody = @{model=$taskOracleSettings.aiModel;instructions='State whether the synthetic search returned a match. Do not invent customer data.';input=$taskInput;max_output_tokens=100;store=$false}
    $taskFinal = Invoke-TaskModel $taskFinalBody
    $taskResult.finalStatus = $taskFinal.status
    if ($taskFinal.status -ne 200 -or $taskFinal.payload.status -ne 'completed') { return }
    $taskText = @($taskFinal.payload.output | Where-Object { $_.type -eq 'message' } | ForEach-Object { $_.content | Where-Object { $_.type -eq 'output_text' } | ForEach-Object { $_.text } }) -join ' '
    $taskResult.finalAnswerPresent = (-not [string]::IsNullOrWhiteSpace($taskText))
} catch {
    $taskResult.failureClass = $_.Exception.GetType().Name
} finally {
    if ($taskToken) { try { $null = Invoke-RestMethod -Uri ($taskBase + '/auth/logout') -Method POST -Headers @{Authorization='Bearer '+$taskToken} -TimeoutSec 12 } catch {} }
    $taskClient.Dispose(); $taskHandler.Dispose(); $taskOracleSettings = $null; $taskToken = $null
    $taskResult.checkedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $taskResult | ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $taskProject '.runtime/oracle-m12/openrouter-toolflow-check.json')
    $taskResult | ConvertTo-Json -Compress
}
