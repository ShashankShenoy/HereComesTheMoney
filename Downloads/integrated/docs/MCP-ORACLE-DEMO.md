# Oracle ALPHA MCP demo

This page records the live demo installed in the existing **ALPHA** schema on the **PDB1** service. It uses synthetic records whose names begin with `MCP`, and it leaves the original installed tables and users in place. The ignored `.env.ps1` and `.runtime/*.dpapi` files hold this workstation's connection settings and demo secrets; they are not committed.

## What was installed

The additive database scripts `002` through `008` were applied to ALPHA. This added the statement revision, customer access key, audit, beneficiary, teller, currency/FX, override, and assistant intent objects plus required permissions and the immutable audit trigger. Script `008` permits exact internal transfer intents. The destructive clean installer `001-original-oracle.sql` was not used.

| Login | Role and scope | Demo use |
| --- | --- | --- |
| `mcp_demo_customer_a` | Customer, self | Account `MCPALPHA0001` (ID `3`), branch `MCP001`, ₹500 posted balance; active and pending payees. |
| `mcp_demo_customer_b` | Customer, self | Account `MCPALPHA0002` (ID `4`), branch `MCP002`, ₹0 balance; cross-account denial test. |
| `mcp_demo_officer` | Officer, branch `MCP001` | Scoped customer search, account reads, and synthetic teller deposit. |
| `mcp_demo_checker` | Independent checker, global | Pending beneficiary list and verification draft. |

The fixture also contains two synthetic CIFs, approved KYC cases, a savings product/version, three GL accounts, an INR currency record, and a balanced ₹500 teller deposit into account `3`. Customer A owns active beneficiaries `MCP Demo Payee (synthetic)` and `MCP Demo Account 2 (synthetic)`, plus pending `MCP Pending Payee (synthetic)`. `MCP Demo Account 2 (synthetic)` points to account token `MCPALPHA0002` with synthetic bank code `MCPB0000002`; customer A registered it and the independent demo checker verified it. The fixture scripts [MCP Demo A transfer permission](../database/acceptance/mcp-demo-a-transfer-permission.sql) and [synthetic product transfer policy](../database/acceptance/mcp-demo-internal-transfer-product.sql) give only Demo A `TXN_POST` and enable `WEB` internal transfers for the synthetic product version shared by A and B. The product script checks that version `32` contains exactly those two demo accounts before changing policy; it adds a ₹500 daily amount cap and a transfer GL mapping. Demo passwords and customer access keys were randomly generated; only BCrypt password hashes and SHA-256 access-key digests are in Oracle.

The `MCP Demo Account 2 (synthetic)` beneficiary resolves as `INTERNAL` because both its account token and configured synthetic bank code match. The assistant's `draft_internal_transfer` creates a five-minute proposal without moving funds; customer A must review and confirm it in Ask Moneybags. Confirmation posts a real internal ledger transfer, debits account `3`, and credits account `4` if balance, account state, product limits, and authorization checks pass. `draft_payment` rejects this internal beneficiary, preventing a misleading simulated outbound payment. No payment or transfer was initiated when this beneficiary was added or when the smoke check ran.

This internal path uses Module 5's banking transfer and balanced journal workflow. Module 6 handles outbound/inbound payment rails and cannot represent a same-bank transfer as a settled payment without rail evidence. The authoritative balances are `M05_ACCOUNT_POSITION.POSTED_BALANCE`; both customer dashboards read those positions. `M04_BANK_ACCOUNT.LEDGER_BALANCE` is an event-fed projection and is currently behind the original ₹500 demo deposit, so do not use that column to verify this demo's funds movement.

## Start the Oracle workspace

From this `integrated` directory in PowerShell:

```powershell
& ./start-oracle-alpha.ps1
```

Open <http://localhost:5174>. The backend is at <http://127.0.0.1:8091>. This script reads the ignored `.env.ps1`, starts Java and Node in hidden windows, and waits for backend health. It uses the normal Oracle profile; `start-local.ps1` is the separate in-memory H2 demo.

This is the normal Moneybags application connected to Oracle, not a separate MCP server. The MCP endpoint and Ask Moneybags run inside the same backend. The four `mcp_demo_*` logins are optional synthetic fixtures for repeatable checks; authorized administrators can create other users through the IAM screens, assign permissions, link customers to CIFs and accounts, and test with those logins. Creating a login alone does not grant account or assistant access.

To stop exactly the processes recorded by the starter:

```powershell
& ./stop-oracle-alpha.ps1
```

The ignored `.env.ps1` selects OpenRouter and the free `google/gemma-4-26b-a4b-it:free` model. It configures `openrouter/free` as an all-free fallback when Gemma is rate limited. It decrypts this workstation's Windows-protected OpenRouter key when the backend starts. The key stays out of the browser, database, and tracked project files. **Ask Moneybags** should report the model as configured. The authenticated MCP endpoint continues to work independently of model availability. Free models may be rate limited or temporarily unavailable; the banking tools and authorization remain on this backend.

To replace or rotate the OpenRouter key, run `& ./set-openrouter-key.ps1` in your own PowerShell console. It accepts hidden input and saves a new Windows-protected copy. Then run `& ./stop-oracle-alpha.ps1` followed by `& ./start-oracle-alpha.ps1`.

## Get the demo logins

Run this only in your own PowerShell console:

```powershell
& ./show-mcp-demo-credentials.ps1
```

It decrypts the locally stored demo passwords and account-holder access keys with the current Windows user's DPAPI key and displays them in that console. Another Windows user or machine cannot decrypt this file. Do not paste the keys into chat text, screenshots, or a repository. A customer needs only their own login to view linked balances and transactions. The access key is for an authorized officer viewing protected financial details.

The script shows the four demo logins, **not an Oracle administrator password**. Starting the application never displays or resets an existing administrator's password. First-admin bootstrap uses an explicitly supplied `BOOTSTRAP_PASSWORD` only when IAM has no users; the existing ALPHA installation already has users.

## Check MCP

With the Oracle backend running:

```powershell
& ./mcp-smoke.ps1
```

The script logs in all four demo users from the encrypted local store and calls the actual `/api/v1/assistant/mcp` endpoint. It checks role-specific tool lists, customer balances, counted transactions, the add-beneficiary form tool, read-only payment history, account isolation, branch-scoped officer search, officer access-key enforcement, the pending beneficiary list, and external payment/internal transfer/verification **drafts**. It verifies that B resolves as an internal recipient and cannot be routed through the simulated outbound rail. It checks statement lists when the demo customer's role has `STATEMENT_READ`; otherwise it prints a skip line. The current ALPHA demo customer does not have that grant. It does not generate a statement, register a beneficiary, confirm a draft, or move money. Its output contains pass/fail lines only, never passwords or access keys.

The initial synthetic deposit was posted through `/api/v1/teller/cash`, not by directly editing account balances. To recheck the transaction fixture without posting it again:

```powershell
& ./mcp-smoke.ps1 -AddOpeningDeposit
```

Expected result: the deposit is already present; customer A's `list_recent_transactions` tool returns a posted `DEPOSIT`. Teller receipts without an access key contain only the transaction ID. The Oracle journal control view showed **zero unbalanced journals** after posting.

For a manual MCP client, authenticate at `POST /api/v1/auth/login`, then send its bearer token to `POST /api/v1/assistant/mcp` with `MCP-Protocol-Version: 2026-07-28`, matching `Mcp-Method`, and for tool calls, matching `Mcp-Name`. Examples and the full tool catalog are in [ASSISTANT-README.md](ASSISTANT-README.md). This is the app's private stateless MCP transport; your client must support that envelope and Moneybags bearer sessions.

## Useful English requests

Customer A:

- “Show my accounts.”
- “What is the balance of my account ending 0001?”
- “Show my recent transactions.”
- “List my beneficiaries.”
- “Transfer ₹1 to MCP Demo B.” The assistant should select the active internal beneficiary and prepare a transfer draft. Review source account ending `0001`, recipient ending `0002`, and amount before selecting **Transfer money**. That confirmation debits A and credits B.
- “Prepare a ₹1 UPI payment to MCP Demo Payee.”

Officer:

- “Find MCP Demo customers in my branch.”
- “Show the accounts I can access.”

Checker:

- “List pending beneficiaries.”
- “Prepare verification of MCP Pending Payee.”

The internal transfer and payment requests produce five-minute drafts. The user must review the exact amount and recipient and use the separate confirmation control. The backend then rechecks the current session, role, scope, account state, and product rules. The checker request follows the same draft and confirmation pattern for beneficiary verification.

## Complete an A-to-B transfer

1. Start the Oracle workspace, get the demo credentials in your own terminal, and sign in at <http://localhost:5174> as `mcp_demo_customer_a`.
2. Open **Ask Moneybags** and say “Transfer ₹1 to MCP Demo B.” The amount is an example; choose the amount you intend to send. The agent should prepare an `INTERNAL_TRANSFER` card with source ending `0001` and recipient ending `0002`. Preparing the card leaves both balances unchanged.
3. Review the exact amount and account endings, then select **Transfer money** before the five-minute expiry. The returned transaction ID refers to a posted Module 5 `INTERNAL_TRANSFER`. If funds, account state, limits, or permissions fail, the confirmation returns an error and neither account is changed.
4. Check A's dashboard or recent transactions, then sign out and sign in as `mcp_demo_customer_b` to check B's new balance and incoming transaction. Repeat confirmation of the same intent returns the same transaction ID without another debit.

The Oracle MCP smoke test creates drafts only. To check exact ledger values directly in Oracle after a confirmed transfer, query `M05_ACCOUNT_POSITION` for account IDs `3` and `4`, or use each customer's authenticated account position API.

## Current verification

- Oracle connected as `ALPHA` on `pdb1`; all extension tables resolved, zero invalid own objects, immutable audit trigger enabled.
- Four demo users authenticated successfully through the live API.
- Customer A could read their own balance and transactions with only a signed-in session. Customer B could not read customer A's account even when the access key was supplied; the officer saw only branch `MCP001` records and still needed the account-holder key for financial details.
- Account `MCPALPHA0001` has exactly one posted ₹500 demo deposit. Account `MCPALPHA0002` remains at ₹0. No unbalanced GL journals were found. No A→B transfer has been confirmed in Oracle yet.
- Customer A has an active synthetic beneficiary pointing to `MCPALPHA0002`; creating and verifying it did not move funds.
- Oracle MCP checks passed for internal transfer, external payment, and verification drafts. The internal beneficiary was rejected by the outbound rail tool, and the smoke check did not confirm a transfer.
- A synthetic H2/OpenRouter chat test selected `draft_internal_transfer`, returned the exact A/B account endings and amount, and produced a confirmation card. A separate H2 test confirmed ₹1, observed A debit/B credit, and verified that replay did not post a second transfer.
- English chat was tested through OpenRouter with `mcp_demo_customer_a`: the response used the account tool and identified the synthetic account ending `0001`. Gemma returned a temporary upstream `429` during the initial probe; a separate free-fallback probe succeeded, and the later chat request succeeded.

The Oracle data is persistent. The H2 demo described in the main README is separate and resets on restart.
