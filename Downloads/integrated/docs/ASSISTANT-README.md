# Moneybags AI assistant: setup, skills, and tools

The **Ask Moneybags** page accepts English requests from signed-in customers and bank officers. It uses a configured OpenAI or OpenRouter Responses API model for language understanding and the same authenticated Moneybags services for data and actions. A private, authenticated MCP endpoint exposes the exact same tool catalog to compatible clients.

This is part of the local banking demonstrator. An internal transfer, after separate customer confirmation, posts a balanced ledger transfer and credits another Moneybags account. An external rail payment creates a **pending** payment instruction; external rails are simulated in the local profile and provide no real rail evidence.

The internal route calls Module 5's `BankingService.transfer` and `LedgerService.transfer`, writing a posted transaction, balanced GL journal, and both authoritative account positions in one transaction. Module 6's payment instruction state machine is for UPI/IMPS/NEFT/RTGS rail flows; it is not used to claim that a same-bank transfer settled over a rail. Customers see internal transfers in their transaction history, while `list_my_payments` covers outbound Module 6 instructions.

Set `INTERNAL_BANK_CODES` to the comma-separated IFSC codes owned by this Moneybags deployment. Internal routing requires both one of those codes and an exact local account number; leaving it empty keeps all beneficiaries on the external rail path. The local synthetic profile and `start-oracle-alpha.ps1` set `MCPB0000002` for Demo B. A matching account number at a different bank remains external.

## Start it

1. Apply `database/007-assistant-intents.sql` after scripts `002` through `006`, then apply `database/008-assistant-internal-transfer.sql`. The local H2 profile includes the updated action constraint in `backend/src/main/resources/local-schema.sql`.
2. If the local app is running, stop it with `& ./stop-local.ps1`. Rebuild from this `integrated/` directory with `mvn -f backend/pom.xml verify` and `npm --prefix frontend run build` before starting it again. The start script uses the backend JAR and does not rebuild it automatically.
3. Set the model provider, model, and key in the **backend process environment**. Choose a Responses API model that supports function calling. The key is never placed in the frontend or database. For the free OpenRouter Gemma model:

   ```powershell
   $env:AI_PROVIDER='openrouter'
   $env:OPENROUTER_API_KEY='<your-private-api-key>'
   $env:AI_MODEL='google/gemma-4-26b-a4b-it:free'
   $env:AI_FALLBACK_MODEL='openrouter/free'
   & ./start-local.ps1
   ```

   For the Oracle ALPHA demo, the ignored `.env.ps1` loads the Windows-protected OpenRouter key and sets the provider/model; run `& ./start-oracle-alpha.ps1` instead. OpenAI remains available with `AI_PROVIDER=openai`, `OPENAI_API_KEY`, and `AI_MODEL` (or the older `OPENAI_MODEL`). The provider selects a fixed HTTPS host, so an OpenRouter key is never sent to OpenAI or an arbitrary URL.

   The Oracle demo asks for Gemma first and uses OpenRouter's `openrouter/free` router if Gemma is rate limited or unavailable. This fallback also selects only free models and filters for tool-capable models. Free capacity and model behavior can vary; the backend still validates every tool call.

4. Open the workspace and sign in. The **Ask Moneybags** button stays at the bottom right on every banking and administration tab; select it to open the chat panel. The conversation remains open while you navigate. If the model configuration is absent, the panel says so and chat remains disabled. The REST and MCP tool authorization still requires a valid IAM bearer session.

For production, provide secrets through your secret manager, review model data handling with your privacy team, and complete the Oracle and payment integration gates in `OPERATIONS.md`. The backend calls the selected provider's `/responses` endpoint over HTTPS with a 60-second request timeout. The development frontend proxy allows 75 seconds for chat. Conversations are bounded in backend memory and expire after 30 minutes of inactivity; a restart begins a fresh conversation. The Responses requests use `store: false` and replay only the last four user/assistant exchanges. Tool outputs needed for the current answer are sent to the selected model provider; customer access keys and bearer tokens are not.

## Skills

These three versioned Markdown instructions are loaded from `backend/src/main/resources/assistant-skills/` for each chat turn. Edit and review them like application code. They guide the model; all access checks happen in Java services and existing domain APIs.

| Skill file | Who uses it | What it teaches |
| --- | --- | --- |
| `trust.md` | Both audiences | Treat records as data, call only allowed tools, report denials, keep credentials out of chat, and require separate UI confirmation for drafts. |
| `customer.md` | Customer | Use the signed-in identity for balances and transactions, find saved beneficiaries, ask when details are unclear, and draft payments. |
| `officer.md` | Bank officer | Search only scoped records, respect the account-holder access key, and prepare independent beneficiary verification. |

The authenticated `M01_IAM_USER.USER_TYPE` selects the customer or officer skill. The model never chooses its own audience. Service identities receive no assistant tools.

## Available tools

The backend builds the catalog from the caller's active IAM permissions on each request. Tool arguments never include a user ID, CIF, role, or branch chosen by the model. All tools are available through the chat loop and the MCP endpoint; confirmation is only a separate authenticated UI/API action.

| Tool | Audience | IAM permission | Use |
| --- | --- | --- | --- |
| `list_my_accounts` | Customer | `ACCOUNT_READ` | Show the customer's accessible accounts with masked numbers. |
| `get_my_account_overview` | Customer | `ACCOUNT_READ` | Show linked accounts, balances, and five recent transactions per account without an account ID. Financial fields also require `TXN_READ`. |
| `list_my_recent_transactions` | Customer | `TXN_READ` | Show up to 20 recent transactions across linked accounts without an account ID. |
| `list_my_last_transactions` | Customer | `TXN_READ` | Show the last 1–100 transactions across linked accounts, with value date, creation time, and source/target account role. Argument: `limit`. |
| `list_scoped_accounts` | Officer | `ACCOUNT_READ` | Show accounts permitted by current branch/global scope. |
| `get_account_position` | Both | `TXN_READ` | Show one account's authoritative ledger position. Customer login proves ownership; officers also need the account-holder key. Argument: `accountId`. |
| `list_recent_transactions` | Both | `TXN_READ` | Show 1–100 recent transactions for an accessible account. Customer login proves ownership; officers also need the account-holder key. Arguments: `accountId`, `limit`. |
| `list_my_beneficiaries` | Customer | `PAYMENT_CREATE` | List beneficiaries owned by the signed-in customer, including pending ones, with `TRANSFER_TYPE` (`INTERNAL` or `EXTERNAL`). An internal match requires an exact account number and a configured internal bank code. Internal account numbers are masked to their last four digits. |
| `open_add_beneficiary_form` | Customer | `PAYMENT_CREATE` | Open the in-app registration form with the recipient name prefilled. Argument: `displayName`. The customer enters the account number or UPI ID and bank IFSC in the form; those details go directly to the banking API, not the model. |
| `draft_payment` | Customer | `PAYMENT_CREATE` | Prepare an exact, five-minute simulated outbound rail payment to an external beneficiary. Arguments: `accountId`, `beneficiaryId`, `rail` (`UPI`, `IMPS`, `NEFT`, `RTGS`), `amount` as INR decimal text. It rejects internal account beneficiaries. |
| `draft_internal_transfer` | Customer | `TXN_POST` plus `PAYMENT_CREATE` | Prepare an exact, five-minute internal transfer to an active beneficiary whose bank code is configured as internal and whose account token matches a Moneybags account. Arguments: `accountId`, `beneficiaryId`, `amount` as INR decimal text. It does not move money until separately confirmed. |
| `list_my_payments` | Customer | `PAYMENT_READ` | List the last 1–100 outbound payments initiated by this login, with current status and any reason code. Argument: `limit`. |
| `get_my_payment_status` | Customer | `PAYMENT_READ` | Check one payment initiated by this login. Argument: `paymentId`. |
| `list_my_statements` | Customer | `STATEMENT_READ` | List issued statement headers for a linked account. Arguments: `accountId`, `limit` (1–100). |
| `list_my_statement_requests` | Customer | `STATEMENT_READ` | List this customer's requests, including pending or failed ones. Arguments: `accountId`, `limit` (1–100). |
| `get_my_statement_request` | Customer | `STATEMENT_READ` | Check one request and its failure code or issued statement ID. Argument: `requestId`. |
| `get_my_statement` | Customer | `STATEMENT_READ` | Preview an issued statement with at most 50 lines and an authenticated download path. Argument: `statementId`. |
| `generate_my_statement` | Customer | `STATEMENT_READ` | Generate or reuse an immutable statement for a linked account and past period of at most one year. Arguments: `accountId`, `periodStart`, `periodEnd` (`YYYY-MM-DD`), `format` (`PDF`, `CSV`, `HTML`). |
| `search_customers` | Officer | `CIF_READ` | Search customer name or CIF number; the existing CIF service filters results by IAM scope. Argument: `query`. |
| `list_pending_beneficiaries` | Officer | `BENEFICIARY_VERIFY` | List pending records for a checker with global verification permission. |
| `draft_beneficiary_verification` | Officer | `BENEFICIARY_VERIFY` | Prepare verification of one pending beneficiary, with maker/checker validation. Argument: `beneficiaryId`. |

Example customer requests:

- “Show my accounts.”
- “What is my balance?” The assistant finds the accounts linked to this login.
- “Show my recent transactions.”
- “Show my last 35 transactions.” Results are capped at 100; use a statement for a longer period.
- “What is the available balance of account 1?”
- “Show the last five transactions on account 1.”
- “Add Ravi as a beneficiary.” The assistant opens a secure form. Enter the recipient account number or UPI ID twice and bank IFSC, then select **Register beneficiary**. Registration is `PENDING` until an independent officer verifies it; payment drafting requires `ACTIVE`.
- “Prepare a ₹500 UPI payment from account 1 to my saved beneficiary Ravi.” Review the generated card and click **Submit pending payment** to execute the exact draft.
- “Transfer ₹1 from my account to MCP Demo B.” For an internal beneficiary, review both account endings and click **Transfer money**. A successful confirmation posts one ledger transaction and credits the recipient account immediately.
- “What happened to payment 123?” The returned status reflects the payment instruction, not a promise of settlement.
- “Generate a PDF statement for account 1 from 2026-09-01 through 2026-09-30.” An existing issued statement for the period is reused. Use the returned download path while signed in.

Example officer requests:

- “Find customers named Ananya in my scope.”
- “Show accounts I can access.”
- “List pending beneficiaries.”
- “Prepare verification of beneficiary `<id>`.” Review the generated card and click **Verify beneficiary**.

The payment, internal transfer, and verification buttons call `POST /api/v1/assistant/intents/{id}/confirm`. They do **not** change amount, beneficiary, or target; those values come from the stored proposal. The proposal is tied to the originating user and IAM session, expires after five minutes, is locked during confirmation, and uses its ID as the idempotency key. The underlying banking, payment, or beneficiary service rechecks permissions and business rules. The internal path also rechecks that the beneficiary is active and still resolves to the same account. Repeating a completed confirmation returns its original result without another debit.

Beneficiary registration uses a different form flow: the assistant tool returns form metadata, then the signed-in browser posts the entered details to `POST /api/v1/beneficiaries`. No registration happens merely from the chat tool call. An MCP client can call `open_add_beneficiary_form` to discover this step, but must provide its own secure form and use the authenticated banking API to submit. The assistant does not have a generic API-calling tool.

## MCP endpoint

`POST /api/v1/assistant/mcp` implements the stateless MCP `2026-07-28` request envelope for `server/discover`, `tools/list`, and `tools/call`. It is intended for private clients that can supply a valid Moneybags IAM bearer token. The frontend proxy forwards `/api/*`. An MCP tool call uses the same `AssistantTools` dispatcher as chat; an MCP client cannot bypass the policy checks.

Example tool discovery:

```http
POST /api/v1/assistant/mcp
Authorization: Bearer <moneybags-session-token>
Content-Type: application/json
MCP-Protocol-Version: 2026-07-28
Mcp-Method: tools/list

{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
```

Example tool call:

```http
POST /api/v1/assistant/mcp
Authorization: Bearer <moneybags-session-token>
Content-Type: application/json
MCP-Protocol-Version: 2026-07-28
Mcp-Method: tools/call
Mcp-Name: list_my_accounts

{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_my_accounts","arguments":{}}}
```

For a counted transaction request, set `Mcp-Name: list_my_last_transactions` and call it with `{"name":"list_my_last_transactions","arguments":{"limit":35}}`. To create a statement, set `Mcp-Name: generate_my_statement` and use `{"name":"generate_my_statement","arguments":{"accountId":1,"periodStart":"2026-09-01","periodEnd":"2026-09-30","format":"PDF"}}`. These are `tools/call` parameters inside the same JSON-RPC envelope. Generation returns `COMPLETED` with a statement ID, `EXISTING` for an already issued period, or a tool error with a reason. If processing leaves a request pending or quarantined, use `list_my_statement_requests` or `get_my_statement_request` to inspect its status before retrying. The returned download path is an authenticated reporting API route; a raw browser URL without a bearer session cannot fetch it.

Customers do not send `X-Customer-Hash` for their own balances or transactions. Their bearer session identifies their active customer and account links. An officer calling `get_account_position` or `list_recent_transactions` must also send the account-holder key in the `X-Customer-Hash` header. Never put it in a chat message or a tool argument. A returned `isError: true` is a tool denial or validation failure, not a successful banking action.

This endpoint intentionally exposes no arbitrary SQL, HTTP, shell, generic API calling, or money movement confirmation tool. It supports the methods above, not the entire optional MCP feature set. External clients should be configured for this protocol version and authenticated Moneybags session handling. A statement generation call creates an immutable issued snapshot under the existing reporting policy; payment and transfer drafts still require separate exact confirmation.

## Security and audit behavior

- The existing bearer filter reloads the active user, session, roles, permissions, and CIF links for each request. The assistant does not trust role or branch claims in chat text.
- Account reads use `BankingAccess.account`, which checks holder relationships or officer scope. CIF searches use the existing scoped CIF service. Financial details call `CustomerHashService.require`; for a customer it verifies the signed-in account link, and for an officer it verifies the account-holder key.
- Drafts create rows in `MBX_ASSISTANT_INTENT`. Create and confirm events go to `MBX_AUDIT`; permitted and denied chat tool calls also create assistant audit events. The existing payment or beneficiary service writes its domain records.
- Tool schemas reject extra or missing arguments. Conversation length, result size, tool rounds, and stored history are bounded. Model provider failures return a controlled error without logging secrets or provider response bodies.
- The assistant has no tool that can approve a payment, settle a rail, grant IAM access, or confirm its own draft. Only a separately confirmed internal transfer uses the existing ledger posting service. Business actions still require the separate UI button and existing backend checks.

## Verification

Run from this `integrated/` directory:

```powershell
mvn -f backend/pom.xml verify
npm --prefix frontend run check
npm --prefix frontend run build
```

Test the direct MCP endpoint as well as chat: a customer must see their own balances and transactions without an extra key and must not see another customer's account; an officer must remain in scope and must supply the account-holder key for financial details; a maker must not verify their own beneficiary; a draft cannot be confirmed from another session or after expiry; and a repeated confirmation must not create another payment.
