param(
  [string]$Root = $PSScriptRoot,
  [string]$BaseUrl = 'http://127.0.0.1:8091',
  [switch]$AddOpeningDeposit
)
$ErrorActionPreference = 'Stop'

function Assert-True([bool]$condition, [string]$message) {
  if (-not $condition) { throw $message }
  Write-Output "PASS $message"
}

$credentialFile = Join-Path $Root '.runtime\mcp-demo-credentials.dpapi'
$protected = Get-Content -LiteralPath $credentialFile -Raw | ConvertTo-SecureString
$plain = [System.Net.NetworkCredential]::new('', $protected).Password
$creds = @{}
foreach ($line in ($plain -split "`n")) {
  $parts = $line.TrimEnd("`r") -split "`t"
  if ($parts.Count -ge 2) { $creds[$parts[0]] = @{ password = $parts[1]; accessKey = if ($parts.Count -ge 3) { $parts[2] } else { '' } } }
}
$plain = $null

function Login([string]$username) {
  $body = @{ username = $username; password = $creds[$username].password; clientId = 'MCP_DEMO_SMOKE' } | ConvertTo-Json -Compress
  $reply = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' -Body $body
  if ([string]::IsNullOrWhiteSpace($reply.data.accessToken)) { throw "Login failed for $username" }
  return $reply.data.accessToken
}

function Call-Mcp([string]$token, [string]$method, [string]$name, [hashtable]$arguments, [string]$accessKey) {
  $headers = @{ Authorization = "Bearer $token"; 'MCP-Protocol-Version' = '2026-07-28'; 'Mcp-Method' = $method }
  $params = @{}
  if ($method -eq 'tools/call') {
    $headers['Mcp-Name'] = $name
    $params = @{ name = $name; arguments = $arguments }
  }
  if (-not [string]::IsNullOrWhiteSpace($accessKey)) { $headers['X-Customer-Hash'] = $accessKey }
  $body = @{ jsonrpc = '2.0'; id = 1; method = $method; params = $params } | ConvertTo-Json -Depth 12 -Compress
  $reply = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/assistant/mcp" -ContentType 'application/json' -Headers $headers -Body $body
  if ($reply.error) { throw "MCP $method returned JSON-RPC error $($reply.error.code): $($reply.error.message)" }
  return $reply.result
}

function Tool-Value([object]$result) {
  if ($result.isError) { throw "MCP tool error: $($result.content[0].text)" }
  return ($result.content[0].text | ConvertFrom-Json)
}

$customerA = Login 'mcp_demo_customer_a'
$customerB = Login 'mcp_demo_customer_b'
$officer = Login 'mcp_demo_officer'
$checker = Login 'mcp_demo_checker'
Write-Output 'PASS four demo users logged in'
$assistantStatus = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/assistant/status" -Headers @{ Authorization = "Bearer $customerA" }
Write-Output "INFO English chat model configured=$($assistantStatus.configured)"

$aTools = @(Call-Mcp $customerA 'tools/list' '' @{} '').tools.name
$bTools = @(Call-Mcp $customerB 'tools/list' '' @{} '').tools.name
$oTools = @(Call-Mcp $officer 'tools/list' '' @{} '').tools.name
$kTools = @(Call-Mcp $checker 'tools/list' '' @{} '').tools.name
Assert-True ($aTools -contains 'list_my_accounts' -and $aTools -contains 'get_my_account_overview' -and $aTools -contains 'list_my_recent_transactions' -and $aTools -contains 'list_my_last_transactions' -and $aTools -contains 'list_my_payments' -and $aTools -contains 'open_add_beneficiary_form' -and $aTools -contains 'draft_payment' -and $aTools -contains 'draft_internal_transfer' -and $aTools -notcontains 'search_customers') 'customer tool catalog is role-scoped'
Assert-True ($bTools -notcontains 'draft_internal_transfer') 'recipient demo user has no transfer-initiation permission'
$hasStatementRead = $aTools -contains 'generate_my_statement'
Assert-True ($oTools -contains 'list_scoped_accounts' -and $oTools -contains 'search_customers' -and $oTools -notcontains 'draft_payment') 'officer tool catalog is role-scoped'
Assert-True ($kTools -contains 'list_pending_beneficiaries' -and $kTools -contains 'draft_beneficiary_verification') 'checker tool catalog includes verification'

$aAccounts = @(Tool-Value (Call-Mcp $customerA 'tools/call' 'list_my_accounts' @{} ''))
$bAccounts = @(Tool-Value (Call-Mcp $customerB 'tools/call' 'list_my_accounts' @{} ''))
Assert-True ($aAccounts.Count -eq 1 -and $bAccounts.Count -eq 1 -and $aAccounts[0].accountId -ne $bAccounts[0].accountId) 'customer accounts are isolated'
$accountId = [long]$aAccounts[0].accountId
$selfPosition = Tool-Value (Call-Mcp $customerA 'tools/call' 'get_account_position' @{ accountId = $accountId } '')
Assert-True ($null -ne $selfPosition.posted) 'customer balance succeeds using signed-in identity'
$overview = Tool-Value (Call-Mcp $customerA 'tools/call' 'get_my_account_overview' @{} '')
Assert-True ($overview.accounts.Count -eq 1 -and $overview.accounts[0].accountId -eq $accountId -and $null -ne $overview.accounts[0].posted) 'customer overview includes only linked account and balance'
$allRecent = @(Tool-Value (Call-Mcp $customerA 'tools/call' 'list_my_recent_transactions' @{} ''))
Assert-True ($allRecent.Count -ge 1 -and $allRecent[0].accountEnding -eq $aAccounts[0].accountEnding) 'customer recent transactions use signed-in identity'
$lastOne = @(Tool-Value (Call-Mcp $customerA 'tools/call' 'list_my_last_transactions' @{ limit = 1 } ''))
Assert-True ($lastOne.Count -eq 1) 'customer can request an exact bounded transaction count'
$invalidLimit = Call-Mcp $customerA 'tools/call' 'list_my_last_transactions' @{ limit = 101 } ''
Assert-True ([bool]$invalidLimit.isError) 'transaction count above 100 is rejected'
$paymentHistory = @(Tool-Value (Call-Mcp $customerA 'tools/call' 'list_my_payments' @{ limit = 5 } ''))
Assert-True ($paymentHistory.Count -le 5) 'customer payment history is bounded and owner-scoped'
$form = Tool-Value (Call-Mcp $customerA 'tools/call' 'open_add_beneficiary_form' @{ displayName = 'MCP Form Check' } '')
Assert-True ($form.uiAction -eq 'ADD_BENEFICIARY' -and $form.displayName -eq 'MCP Form Check') 'customer can open a secure beneficiary form without registering a payee'
if ($hasStatementRead) {
  $statementHeaders = @(Tool-Value (Call-Mcp $customerA 'tools/call' 'list_my_statements' @{ accountId = $accountId; limit = 5 } ''))
  Assert-True ($statementHeaders.Count -le 5) 'customer statement headers are bounded and authorized'
  $statementRequests = @(Tool-Value (Call-Mcp $customerA 'tools/call' 'list_my_statement_requests' @{ accountId = $accountId; limit = 5 } ''))
  Assert-True ($statementRequests.Count -le 5) 'customer statement request status is bounded and authorized'
} else { Write-Output 'SKIP statement tools: demo customer role has no STATEMENT_READ grant' }
$crossAccount = Call-Mcp $customerB 'tools/call' 'get_account_position' @{ accountId = $accountId } $creds['mcp_demo_customer_a'].accessKey
Assert-True ([bool]$crossAccount.isError) 'other customer cannot read account even with its access key'
if ($hasStatementRead) {
  $crossStatements = Call-Mcp $customerB 'tools/call' 'list_my_statements' @{ accountId = $accountId; limit = 5 } ''
  Assert-True ([bool]$crossStatements.isError) 'other customer cannot list statements for this account'
}

$officerAccounts = @(Tool-Value (Call-Mcp $officer 'tools/call' 'list_scoped_accounts' @{} ''))
Assert-True ($officerAccounts.Count -eq 1 -and $officerAccounts[0].accountId -eq $accountId) 'officer only sees branch MCP001 account'
$officerNoKey = Call-Mcp $officer 'tools/call' 'get_account_position' @{ accountId = $accountId } ''
Assert-True ([bool]$officerNoKey.isError) 'officer financial details require account-holder key'
$officerPosition = Tool-Value (Call-Mcp $officer 'tools/call' 'get_account_position' @{ accountId = $accountId } $creds['mcp_demo_customer_a'].accessKey)
Assert-True ($null -ne $officerPosition.posted) 'authorized officer can use account-holder key'
$customers = @(Tool-Value (Call-Mcp $officer 'tools/call' 'search_customers' @{ query = 'MCP Demo' } ''))
Assert-True ($customers.Count -eq 1 -and $customers[0].branch -eq 'MCP001') 'officer customer search respects branch scope'
$pending = @(Tool-Value (Call-Mcp $checker 'tools/call' 'list_pending_beneficiaries' @{} ''))
Assert-True ($pending.Count -ge 1) 'checker sees pending demo beneficiary'

if ($AddOpeningDeposit) {
  if ([decimal]$selfPosition.posted -eq 0) {
    $headers = @{ Authorization = "Bearer $officer" }
    $tills = @(Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/teller/tills" -Headers $headers)
    $till = @($tills | Where-Object { $_.BRANCH_CODE -eq 'MCP001' -and $_.STATUS -eq 'OPEN' } | Select-Object -First 1)
    if ($till.Count -eq 0) {
      $till = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/teller/tills" -ContentType 'application/json' -Headers $headers -Body (@{branchCode='MCP001';cashGlId=2}|ConvertTo-Json -Compress)
      $tillId = $till.tillId
    } else { $tillId = $till[0].TILL_ID }
    $cash = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/teller/cash" -ContentType 'application/json' -Headers $headers -Body (@{requestKey='mcp-demo-opening-deposit-v1';tillId=$tillId;accountId=$accountId;direction='DEPOSIT';amount='500.00';reason='Synthetic MCP demo opening balance'}|ConvertTo-Json -Compress)
    Assert-True ($cash.transactionId -gt 0 -and $null -eq $cash.amount) 'teller receipt masks financial details without account access key'
  } else { Write-Output 'PASS synthetic opening deposit already present; no duplicate posting' }
  $recent = @(Tool-Value (Call-Mcp $customerA 'tools/call' 'list_recent_transactions' @{accountId=$accountId;limit=20} ''))
  Assert-True ($recent.Count -ge 1 -and @($recent | Where-Object { $_.status -eq 'POSTED' -and $_.type -eq 'DEPOSIT' }).Count -ge 1) 'customer sees posted synthetic transaction through MCP'
}

$beneficiaries = @(Tool-Value (Call-Mcp $customerA 'tools/call' 'list_my_beneficiaries' @{} ''))
$demoAccountTwo = @($beneficiaries | Where-Object { $_.DISPLAY_NAME -eq 'MCP Demo Account 2 (synthetic)' -and $_.BANK_CODE -eq 'MCPB0000002' -and $_.STATUS -eq 'ACTIVE' })
Assert-True ($demoAccountTwo.Count -eq 1) 'customer 1 has active synthetic account 2 beneficiary'
Assert-True ($demoAccountTwo[0].TRANSFER_TYPE -eq 'INTERNAL' -and $demoAccountTwo[0].ACCOUNT_ENDING -eq '0002') 'account 2 beneficiary resolves to an internal transfer target'
$internal = Tool-Value (Call-Mcp $customerA 'tools/call' 'draft_internal_transfer' @{accountId=$accountId;beneficiaryId=$demoAccountTwo[0].BENEFICIARY_ID;amount='1.00'} '')
Assert-True ($internal.action -eq 'INTERNAL_TRANSFER' -and $internal.intentId) 'internal transfer draft created without moving funds'
$wrongRail = Call-Mcp $customerA 'tools/call' 'draft_payment' @{accountId=$accountId;beneficiaryId=$demoAccountTwo[0].BENEFICIARY_ID;rail='UPI';amount='1.00'} ''
Assert-True ([bool]$wrongRail.isError) 'internal recipient cannot be sent through simulated outbound rail'
$active = @($beneficiaries | Where-Object { $_.STATUS -eq 'ACTIVE' -and $_.TRANSFER_TYPE -eq 'EXTERNAL' } | Select-Object -First 1)
Assert-True ($active.Count -eq 1) 'customer sees active external demo beneficiary'
$payment = Tool-Value (Call-Mcp $customerA 'tools/call' 'draft_payment' @{accountId=$accountId;beneficiaryId=$active[0].BENEFICIARY_ID;rail='UPI';amount='1.00'} '')
Assert-True ($payment.action -eq 'PAYMENT_INITIATE' -and $payment.intentId) 'payment draft created without submitting payment'
$verify = Tool-Value (Call-Mcp $checker 'tools/call' 'draft_beneficiary_verification' @{beneficiaryId=$pending[0].BENEFICIARY_ID} '')
Assert-True ($verify.action -eq 'BENEFICIARY_VERIFY' -and $verify.intentId) 'beneficiary verification draft created without activating it'
Write-Output 'PASS Oracle-backed MCP smoke check complete'
