$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'oracle-config.ps1')
if ($taskOracleSettings.aiProvider -ne 'openrouter') { throw 'Protected ALPHA settings do not select OpenRouter; no provider request sent.' }
if ([string]::IsNullOrWhiteSpace($taskOracleSettings.openrouterApiKey) -or [string]::IsNullOrWhiteSpace($taskOracleSettings.aiModel)) { throw 'Protected ALPHA settings lack OpenRouter key or model; no provider request sent.' }
$taskHandler = [System.Net.Http.HttpClientHandler]::new()
$taskHandler.AllowAutoRedirect = $false
$taskClient = [System.Net.Http.HttpClient]::new($taskHandler)
$taskClient.Timeout = [TimeSpan]::FromSeconds(40)
$taskRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, 'https://openrouter.ai/api/v1/responses')
$taskRequest.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $taskOracleSettings.openrouterApiKey)
$taskRequest.Headers.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new('application/json'))
$taskBody = @{ model = $taskOracleSettings.aiModel; input = @(@{ role = 'user'; content = 'Reply with OK.' }); max_output_tokens = 24; store = $false; tools = @() } | ConvertTo-Json -Depth 7 -Compress
$taskRequest.Content = [System.Net.Http.StringContent]::new($taskBody, [System.Text.Encoding]::UTF8, 'application/json')
$taskResult = [ordered]@{ provider = 'openrouter'; requestsSent = 0; transport = 'not_started' }
try {
    $taskResult.requestsSent = 1
    $taskResponse = $taskClient.SendAsync($taskRequest).GetAwaiter().GetResult()
    $taskResult.httpStatus = [int]$taskResponse.StatusCode
    $taskResult.transport = 'response_received'
    if ($taskResponse.IsSuccessStatusCode) {
        $taskPayload = $taskResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        $taskResult.responseStatus = $taskPayload.status
        $taskResult.hasOutput = (@($taskPayload.output).Count -gt 0)
    }
} catch {
    $taskResult.transport = 'request_failed'
    $taskResult.failureClass = $_.Exception.GetType().Name
} finally {
    $taskClient.Dispose(); $taskHandler.Dispose(); $taskRequest.Dispose()
    $taskOracleSettings = $null
    $taskResult.checkedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $taskResult | ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $taskProject '.runtime/oracle-m12/openrouter-check.json')
    $taskResult | ConvertTo-Json -Compress
}
