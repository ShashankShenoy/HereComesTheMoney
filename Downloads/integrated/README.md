# Moneybags Oracle ALPHA

Moneybags runs six Spring Boot services, a Node.js API gateway, and an Oracle JET / Knockout frontend against the existing shared ALPHA Oracle database. There are three user-facing PowerShell scripts in this directory:

| Script | Purpose |
| --- | --- |
| `set-openrouter-key.ps1` | Save this Windows user's OpenRouter API key at a hidden prompt |
| `start-alpha-team.ps1` | Unlock the team bundle, build, and start the Oracle application |
| `stop-oracle-alpha.ps1` | Stop the application processes recorded by this checkout |

## Start the Oracle app

Use PowerShell 7 on Windows with Java JDK 17+, Maven 3.9+, and Node.js 20+ on PATH. Connect the ALPHA network/VPN and have the current team bundle passphrase ready. Open PowerShell in `Downloads/integrated`:

~~~powershell
./set-openrouter-key.ps1  # Save or replace your own AI key when needed.
./start-alpha-team.ps1
~~~

Open [localhost:5174](http://localhost:5174) after the **Moneybags microservices ready** message. Enter the team passphrase at the hidden prompt. The launcher automatically displays all seven team logins with their roles, passwords, and any customer access keys, then starts six Java services, the API gateway, and frontend in hidden windows, checks readiness, and cleans up a partial failure. It uses Oracle with SQL initialization disabled. Database records persist after stopping.

~~~powershell
./stop-oracle-alpha.ps1
~~~

The start script also supports:

| Option | Purpose |
| --- | --- |
| `-BuildOnly` | Build and test before connecting a restrictive VPN; no passphrase or database needed |
| `-Offline` | Build with cached Maven/npm dependencies |
| `-SkipBuild` | Verify the previous build's fingerprints and start it |
| `-ShowCredentials` | Display locally unlocked team logins in your own console |
| `-CheckOnly` | Check the bundle passphrase and any existing ALPHA encryption keys |
| `-UnlockOnly` | Unlock team logins and keys without starting services |
| `-BackendPort` / `-FrontendPort` | Change the gateway/frontend ports; defaults are 8091/5174 |

Internal build, encryption, and process helpers are modules under `tools/`. The H2, Docker-demo, duplicate start/stop, and restart launchers have been removed. See [service architecture](docs/MICROSERVICES.md) for ports and service ownership.

## Demo logins and data

### Align customer roles and product types

Run `database/023-align-demo-roles.sql` in SQL Developer as the schema owner after migrations 010 and 015–022. It preserves every username, status, password, CIF, account and financial record. All existing customer identities receive `BANK_CUSTOMER` with SELF scope and the product/currency dimensions from the confirmed customer `adipil`. New customer signups inherit those dimensions too. Customer product offers still check each customer's own branch and KYC eligibility.

Use `admin` with `BANK_ADMIN` to prepare operations and `checker1` with `BANK_CHECKER` for independent approvals. Existing checker demo logins remain available with the same complete checker role. The checker covers loan decisions, signed documents and disbursements, teller cash counts, card applications/products, payments, KYC, account and other approvals. The script requires the existing reference customer, admin and checker1; it fails before writes if that setup is missing. Default demo authorities, when not already configured, are INR 5,000,000 at 20% for loan sanction and INR 200,000 at 24% for credit limits. Affected users sign in again after applying it.

Credit card purchases and repayments now require the customer's existing six-digit payment PIN. **Statements & documents** lets a customer choose an account and period, then generate and download PDF, CSV or HTML in one step. Pending authorized requests can be retried from the same page.

Starting with `./start-alpha-team.ps1` automatically displays the ALPHA usernames, roles, passwords, and customer access keys after you enter the passphrase. Use `-ShowCredentials` to display them again without restarting. Customer login is `/login`; staff login is `/employee/login`. Use current Oracle balances and configuration when following the product workflows below; earlier H2 fixture balances and passwords are not ALPHA credentials.

## Use Oracle

### Shared ALPHA database for the team

The repository includes `alpha-team.bundle.json`, encrypted with AES-256-GCM and a separately held team passphrase. It contains the ALPHA JDBC connection, schema password, the installation's CIF/MFA decryption keys, optional assistant provider key, and team demo logins. Private `.env.ps1` and Windows DPAPI files remain ignored; the launcher uses the encrypted team bundle and this Windows user's saved OpenRouter override. **The team passphrase is not in Git.** Obtain it through your team's private channel; do not place it in a commit, issue, or chat transcript.

On a new Windows checkout, connect the ALPHA network/VPN, open **PowerShell 7** in this directory, and run:

~~~powershell
./start-alpha-team.ps1
~~~

Enter the team passphrase at the hidden prompt. The launcher decrypts the bundle, places ALPHA's CIF/MFA keys in the dedicated ignored `.runtime/alpha-keys/` directory, saves **all seven demo logins in one Windows-protected file** at `.runtime/alpha-team-credentials.dpapi`, automatically displays their usernames, roles, passwords, and any customer access keys in your console, builds the app, and starts it at [localhost:5174](http://localhost:5174). Existing `runtime/cif.key` and `runtime/mfa.key` files from another local demo are left alone. The database password is passed to the backend through its process environment. Use `./start-alpha-team.ps1 -ShowCredentials` to display the saved logins again. Stop with `./stop-oracle-alpha.ps1`. For a second start, repeat the same unlock command. On a restrictive VPN, build first with `./start-alpha-team.ps1 -BuildOnly`, reconnect, and use `./start-alpha-team.ps1 -SkipBuild`.

To use your own OpenRouter key with this team launcher, run `./set-openrouter-key.ps1` in PowerShell 7 and enter the key at its hidden prompt. It saves a Windows-protected override in `.runtime/openrouter-api-key.dpapi`; the next `./start-alpha-team.ps1` run uses that key instead of the bundle's key. The browser and tracked files never need the key.

| Staff demo login | Active ALPHA role | Sign-in |
| --- | --- | --- |
| `admin` | Global `BANK_ADMIN`; general maker and administration | `/employee/login` |
| `checker1` | Global `BANK_CHECKER`; independent decisions | `/employee/login` |
| `demo.officer` | Branch-scoped `DEMO_BRANCH_OFFICER` at `BR01` | `/employee/login` |
| `mcp_demo_checker` | MCP demo checker | `/employee/login` |
| `mcp_demo_officer` | MCP demo officer at `MCP001` | `/employee/login` |

The shared staff passwords were reset for the team bundle. Previously signed-in sessions for `admin`, `checker1`, and `demo.officer` were ended. Any earlier copies of the encrypted bundle or passphrase no longer provide their current passwords; distribute the replacement passphrase from the original workstation.

The passphrase for the bundle created on the original workstation is stored only in ignored `.runtime/alpha-team-passphrase.txt`. Its owner must share it securely with authorized teammates. If the passphrase or encrypted bundle is lost, the original workstation can create a replacement with `./tools/export-alpha-team-bundle.ps1 -ReplaceBundle`; distribute the replacement passphrase separately. A replacement invalidates the previous passphrase. Existing local installation keys that differ from the bundle stop startup rather than overwriting them.

The shared database is a **single persistent environment**: every teammate sees and can change the same accounts, payments, approvals, and balances. Coordinate demos and use current balances. Repository access alone does not unlock the bundle, but anyone who obtains both the bundle and passphrase can recover all included secrets. The old CIF/MFA keys were previously present in Git history; removing them from the current revision did not erase that history. Rotation of those keys against existing ALPHA records requires a data migration.

### Existing Oracle schema

### 1. Prepare or check the schema

The application **does not run Oracle migrations at startup**.

- Existing schema: inspect installed objects and apply only missing additive scripts in order. Do not rerun completed non-repeatable scripts.
- Empty disposable schema: the schema owner can review and run database/001-original-oracle.sql, then the required additive scripts.
- **001 removes/recreates objects. Never use it to upgrade a database containing records you need.**

| Scripts | Setup |
| --- | --- |
| 002–006 | Statement extensions, integration objects, overrides, permissions, immutable audit |
| 007–008 | Assistant drafts and internal-transfer intents |
| 009–010 | Treasury access and bank admin/checker roles |
| 011, 012-rbi-cash-delivery.sql, 013, 014 | Vault allocation, cash delivery, replenishment, synthetic reserve opening |
| 015-credit-cards.sql | M11 credit cards and permissions |
| 016-customer-loan-apply.sql, 017-loan-operational-readiness.sql | Customer loan application and operational readiness |
| 018-deposit-interest.sql | Monthly savings interest and fixed deposits |
| 019-customer-account-requests.sql | Account service requests |
| 020-cif-proposed-segment.sql | Segment proposals governed by KYC approval |
| 021-payment-clearing-simulator.sql, 022-customer-payment-pin.sql | Payment simulation and customer payment PIN |
| 023-align-demo-roles.sql | Preserve users and align customer scopes with adipil; complete checker approvals |

**012-rbi-cash-delivery-readiness.sql is a diagnostic, not an additional migration.** Inspect scripts under database/acceptance before running them: the general readiness checks are read-only, but specifically named synthetic fixture scripts can change demo configuration.

If the former 011-credit-cards.sql from the card branch is already installed, **skip 015**. File 015 is installation order; the permanent module/table prefix remains **M11 / M11_CC_**. See [the existing-installation checklist](docs/CREDIT-CARD-MAIN-MERGE.md).

Run applicable read-only checks: [Oracle readiness](database/acceptance/oracle-readiness.sql), [integrated schema/balances](database/acceptance/integrated-main.sql), and [credit cards](database/acceptance/credit-cards.sql). Resolve missing/invalid objects and accounting exceptions before financial demos. [Operations](docs/OPERATIONS.md) contains the installation details.

### 2. Start, open, and stop

Connect the ALPHA network/VPN and run `./start-alpha-team.ps1`. The encrypted bundle supplies the Oracle connection and original CIF/MFA keys. Startup connects to the existing database without installing a database or changing its schema.

| Page | Address |
| --- | --- |
| Customer sign-in | [localhost:5174/login](http://localhost:5174/login) |
| Bank-admin / employee sign-in | [localhost:5174/employee/login](http://localhost:5174/employee/login) |
| API documentation | [localhost:5174/swagger](http://localhost:5174/swagger) |
| Health | [localhost:5174/actuator/health](http://localhost:5174/actuator/health) |
| Backend directly | http://127.0.0.1:8091 |

~~~powershell
& ./stop-oracle-alpha.ps1
~~~

Use the installation's actual web credentials and MFA settings. The local fixture password does not establish an Oracle login. [The Oracle ALPHA fixture record](docs/MCP-ORACLE-DEMO.md) documents this workstation's synthetic accounts; check their current balances rather than assuming the historical figures.

**Keep the original encryption keys for an existing Oracle database.** Back up `runtime/cif.key` and `runtime/mfa.key` privately before replacing a checkout or moving to another machine, and restore them before starting against the same database. New keys cannot decrypt existing CIF or authenticator records. These keys are generated for a fresh installation and must not be committed or shared with another installation. Anyone updating an older checkout that already tracked the two files should save a private copy before pulling this change; Git may remove the tracked copies during that pull.

The Oracle demo launcher enables demo signup and external payment simulation, and sets the synthetic internal bank code. Oracle deposit-interest services additionally require DEPOSIT_INTEREST_ENABLED=true, migration 018, and complete product/GL mappings. Do not enable the local Spring profile when using Oracle.

To disable external initiation while retaining direct same-bank transfers:

~~~powershell
& ./start-alpha-team.ps1 -PaymentSimulationEnabled $false
~~~

## Oracle startup and VPN recovery

If the database VPN blocks dependency downloads, build on a network that permits them first:

~~~powershell
& ./start-alpha-team.ps1 -BuildOnly
# Connect the database VPN after the build succeeds.
& ./start-alpha-team.ps1 -SkipBuild
~~~

With cached dependencies, start-alpha-team.ps1 -BuildOnly -Offline can build without downloads. Missing cached packages require an online build. **-SkipBuild verifies the recorded source, JAR, and frontend fingerprints**; it does not bypass checks. Rebuild after source/output changes. Stop the backend before replacing its running JAR.

Check your configured host and listener port:

~~~powershell
Resolve-DnsName 'YOUR_ORACLE_HOST'
Test-NetConnection -ComputerName 'YOUR_ORACLE_HOST' -Port 1521
~~~

DNS and TCP reachability do not verify database credentials. Application health checks the database connection too. After a VPN drop, reconnect, check health, and stop/start if it remains unhealthy. Do not reinstall the schema to fix networking.

### Is a database COMMIT needed?

- Successful application actions commit through their own transactions; no manual SQL Developer commit is needed.
- Commit manual SQL changes in the session that made them, unless autocommit is enabled. That cannot commit another application's connection.
- Oracle DDL commits implicitly. Review a partially failed migration before retrying it.
- Read-only acceptance queries need no commit.

## Optional configuration

| Setting | Purpose |
| --- | --- |
| DB_URL, DB_USERNAME, DB_PASSWORD | Oracle connection |
| FRONTEND_ORIGINS | Allowed browser origins, including custom local ports |
| CUSTOMER_SIGNUP_ENABLED / CUSTOMER_SIGNUP_BRANCH | Demo onboarding; enabled by the local profile and Oracle demo launcher |
| DEPOSIT_INTEREST_ENABLED | Savings interest/deposit services; local profile enables it; Oracle needs migration/mappings |
| PAYMENT_SIMULATION_ENABLED | External simulator; local profile/demo launcher enable it; base default is false |
| INTERNAL_BANK_CODES | Assistant verified-beneficiary routing; direct account-number transfers do not need it |
| AI_PROVIDER, AI_MODEL, OPENAI_API_KEY or OPENROUTER_API_KEY | Optional Ask Moneybags provider |
| AI_FALLBACK_MODEL | Optional fallback model for that provider |
| KAFKA_ENABLED / KAFKA_BOOTSTRAP_SERVERS | Optional outbox publication; ordinary banking does not need Kafka |
| FX_PROVIDER_URL / FX_API_TOKEN | Optional HTTPS rate feed; manually approved rates need no feed |
| BACKEND_URL / PORT | Frontend proxy target/web port; launchers set these |

The ALPHA launcher loads assistant provider/model settings from the encrypted bundle. Run `./set-openrouter-key.ps1` to save your own OpenRouter key, then restart with `./stop-oracle-alpha.ps1` and `./start-alpha-team.ps1`. Java services enable the system proxy. See [assistant setup](docs/ASSISTANT-README.md).

Demo signup assumes verified KYC without checking real identity evidence. Use synthetic people only. This shortcut is disabled in the base application configuration.

## Prepare a demonstration

1. Use separate browser profiles for customer, customer2, admin, and checker, or sign out/in when switching identities.
2. Use English labels to follow this guide; Hindi and Kannada are also available.
3. Record starting balances and every returned reference. Prior demos change later starting balances.
4. On staff screens, open **Choose operation / Operations** and search for the described action. Required fields and allowed choices come from the running API contract.
5. Use a **different checker** for independent decisions. Admin privileges do not bypass self-approval controls.
6. For Oracle, confirm active products, effective availability/GL mappings, verified KYC, customer ownership links, scope, and authorities such as LOAN_SANCTION and CC_LIMIT.

A short presentation can follow: savings overview → two-user transfer → admin ledger → fixed deposit → credit card → loan → statement. Add the other modules when presenting them. Keep the local backend running through the whole presentation.

## Product demo walkthroughs

### 1. Savings accounts and customer onboarding

**User:** customer. **Screen:** My accounts / Accounts.

1. Sign in and review the overview's own balances/activity.
2. Open MB000000001; a fresh local backend shows INR 75,000. Inspect details, status, product, posted/spendable balances.
3. Use nominees to add a synthetic nominee; shares must total 100%.
4. Submit a limit request or issue, refresh, and show the saved request. A request does not automatically change the limit.
5. Open another eligible savings account. The seeded product's zero opening minimum allows an eligible opening to activate immediately; a product requiring funding remains pending until funded.
6. Optionally use **Create a customer account** on the sign-in page for an adult synthetic person, sign in, and open an account. A new account does not receive the seeded INR 75,000 automatically.

**Expected:** own-account access and saved service requests. Funds come from actual demo ledger postings.

**Current accounts:** supported for eligible INR products, but the fresh catalog does not seed a CURRENT product. To demonstrate one, create its product/version in Product master, configure account rules, WEB/INR availability and supported transaction rules, submit, independently approve and activate it, and configure effective customer-liability GL mappings before posting. Creating a product identity alone does not make a customer offer.

### 2. Payments between two different users

**Users:** customer and customer2. **Screen:** Payments → Send to another bank account.

1. As customer2, copy MB000000002 from My accounts.
2. As customer, choose source MB000000001, recipient MB000000002, and amount **25**.
3. Click **Review transfer**, check the details, and confirm.
4. Refresh both accounts and **Sent and received transfers**. Both see the same reference and **Completed** status; directions are Sent and Received.
5. Optionally send INR 3 back from customer2.

| Fresh fixture | Before INR 25 transfer | After |
| --- | --- | --- |
| Customer 1 | INR 75,000 | INR 74,975 |
| Customer 2 | INR 25,000 | INR 25,025 |

**Expected:** both balances change together and one balanced journal posts. Cancellation, insufficient funds, and invalid recipients leave balances unchanged. Retrying the same confirmed request posts once; confirming a new request creates a new transfer. The sender does not gain access to the recipient's private details.

This flow needs no external beneficiary verification, reserve authorization, or live network, and works with external simulation disabled. Its endpoint is **POST /api/v1/transactions/transfers/by-number**.

### 3. Bank-admin account ledger and general ledger

**User:** admin. **Screen:** Transactions & ledger.

1. Sign in through the employee page and open **Account transactions**.
2. Load MB000000001, find the transfer, and open **View details**.
3. Review amount, status history, linked journal, debit/credit totals, and **Balanced**.
4. Load MB000000002 to inspect the recipient side.
5. Open **GL account ledger**, load local GL ID **1**, and use **View journal** to inspect every debit/credit line.
6. If available, inspect the transaction through **Audit trail** using its reference/date filters.

**Expected:** an active unrestricted global BANK_ADMIN with TXN_READ can view account transactions without a customer key. GL inquiry also requires GL_RECONCILE. Reads are audited. Account activity contains the latest 100 results with Show more; the GL has paging. Other staff still require their own scope and any required holder key.

### 4. Fixed deposits and savings interest

**User:** customer. **Screen:** My accounts / Accounts.

1. Open the **Fixed Deposit** product, funded from MB000000001.
2. Enter principal **1000** and tenure **6 months**; review the rate and expected payout.
3. Confirm, then inspect ACTIVE deposit details: principal, funding account, rate, maturity, payout.
4. Check the funding account decreased by INR 1,000 and its ledger contains the funding posting.
5. Use the savings account's **Interest** action to review posted monthly credits.

**Expected:** a separate deposit contract holds principal; principal/interest return to the funding account at maturity. It does not immediately mature for a presentation. A fresh savings account may have no completed-month credit yet.

Savings posting runs at 01:10 Asia/Kolkata on the first of the month; maturity runs daily at 01:30. Retry actions still require due dates/eligible periods. Early/partial withdrawal, automatic renewal, and tax withholding are not implemented. See [Deposit interest](docs/DEPOSIT-INTEREST.md) for Oracle mappings and service setup.

### 5. Credit cards

**Users:** customer, checker; admin for staff actions. **Screen:** Credit cards.

1. As customer, review **Moneybags Classic** terms and apply with, for example, a **25,000** requested limit linked to your active deposit account. Accept the terms.
2. As checker, independently approve within the request, product range, and CC_LIMIT authority. The local range is INR 10,000–200,000.
3. Refresh as customer and activate the synthetic card.
4. Make a simulated **100** purchase; inspect outstanding balance, available credit, and history.
5. Freeze it to show spending is unavailable, then unfreeze.
6. Repay from the linked account using the **current payoff shown**. Elapsed-day interest may make payoff differ from the original purchase.
7. Review reconciliation: expected **MATCHED**. Authorized staff can demonstrate a full purchase refund once.

**Expected:** purchase increases card receivable; repayment reduces deposit/card balances through balanced journals. No live merchant/card network is involved. Scheduled statements become issuable on/after billing, not immediately upon application. Terms use simple ACT/365 interest from purchase without an interest-free grace period. No real PAN/PIN/CVV is created. [Credit cards](docs/CREDIT-CARDS.md) covers billing, refunds, closure, and Oracle setup.

### 6. Personal loans

**Users:** customer → admin → checker at the relevant stages. **Screen:** Loans.

1. Customer: **Apply for a loan**, select Monthly Personal Loan, amount **10000**, tenure **12 months**, and an allowed purpose. Submit: SUBMITTED, with no account credit yet.
2. Admin: assess the application using sample monthly income **50000**, obligations **5000**, recommendation APPROVE, and reason AFFORDABLE.
3. Checker: independently approve **10000 / 12 months / 12%**, authority LOAN_SANCTION, with a synthetic authority evidence reference.
4. Admin: issue an offer with a future expiry and synthetic document reference such as demo://loan-offer.
5. Customer: refresh, inspect the offer, and accept it with your active INR disbursement/repayment account.
6. Admin: register a SIGNED_OFFER document with synthetic storage reference and SHA-256 digest. Use the actual digest for a real file; a 64-character hexadecimal test value represents synthetic evidence only. Checker: verify the document.
7. Admin: convert to a facility and request disbursement. Checker: approve disbursement with the required authority.
8. Customer: verify the account credit of INR 10,000 and inspect the 12-installment schedule.

**Expected:** application/sanction alone do not move money; approved disbursement does. Copy the correct application, offer, document, facility, and disbursement IDs between stages.

Servicing requires eligible due interest accrual before a due repayment; future/unsupported prepayments fail. Oracle needs loan products/rules/mappings, KYC, customer permissions, and checker authority. See [Operations](docs/OPERATIONS.md).

### 7. External payment simulation

1. Customer: add an external recipient with **Account number** and a registered bank IFSC, for example HDFC0000001, UTIB0000001, SBIN0000001 or ICIC0000001.
2. Checker: open **Beneficiaries Pending → View** or **Verify a beneficiary** and use the row's Verified/Not verified button.
3. Customer: choose the source account, verified recipient, rail and amount in External payment simulation. Eligible requests process automatically. IMPS/NEFT above INR 1,00,000 and RTGS above INR 10,00,000 await independent checker approval; UPI retains its INR 1,00,000 cap and IMPS has an INR 5,00,000 maximum.
4. Checker: **Authorize simulated payment** lists pending requests with Approve/Reject buttons. Approval opens the reserve selector.
5. Admin: open the separate **RBI / NPCI simulation portal** to inspect windows, payment membership and participating bank reserves, settle a cycle early, or simulate an incoming instruction.
6. Customer: refresh payment progress. Completed outgoing payments say: **Interbank settlement completed; beneficiary credit handled by receiving bank.**

UPI and IMPS use separate two-hour demo clearing cycles; NEFT uses 30-minute cycles, Asia/Kolkata. RTGS settles individually and retains its INR 2,00,000 minimum. Internal account transfers remain a separate workflow. External customer accounts are outside scope. See [payment and clearing policies, installation and verification](docs/PAYMENT-CLEARING.md).
### 8. Statements and documents

**User:** customer. **Screen:** Statements & documents.

1. Perform a transfer or other posting first.
2. Create a statement request with your account number, type AD_HOC, a non-future period containing the activity, PDF format, WEB channel, and en-IN locale.
3. Copy the **request ID**, process that request, and record the returned **statement ID**.
4. Open completed request history and download PDF, CSV, and/or HTML.
5. Check opening, transaction, running, and closing balances.

**Expected:** completed requests retain a downloadable reference; request ID and statement ID differ. Another customer cannot access it. Later postings do not rewrite an issued immutable snapshot. Approved narration/masking is seeded locally and must be configured on Oracle. Use en-IN for the built-in PDF demo; non-Latin PDF narration needs additional font support. Email/SMS delivery is not connected.

### 9. Currency and rate comparison

**Users:** customer; admin/checker for a proposal. **Screen:** Currency & rates.

1. Customer: choose an approved pair, enter an amount, and calculate. Inspect source/expiry. The local synthetic USD/INR rate expires after 12 hours.
2. Optional admin proposal: EUR/INR buy **89**, mid **90**, sell **91**, current observation time and future validity; label its source synthetic.
3. Checker: independently approve the proposal.
4. Customer: refresh. With that example mid-rate, EUR 10 compares to INR 900 and reciprocal INR 180 to EUR 2.

**Expected:** no balance movement. Pending/expired rates are not current approved quotes. Executable conversion, foreign-currency account posting, and international transfers are not implemented.

## Supporting banking and administration tabs

### Teller cash

1. Admin: **Open a teller till**, branch MUM001. Opening allocates INR **5,000** from the seeded vault.
2. Record the till ID. **Post a cash deposit or withdrawal** to MB000000001: DEPOSIT **100**, with a reason.
3. Post WITHDRAWAL **40**. The customer gains net INR 60; this otherwise untouched till expects INR **5,060**.
4. Checker: **Independently close a teller till**, using its ID, actual counted cash, and reason.
5. Check CLOSED status and cash returned to the vault. Use the current expected amount if other cash operations occurred.

An already open till for the same teller/branch/day cannot be opened again. Oracle needs registered vault cash and sufficient funds. These internal cash movements do not alter RBI reserve balances.

### Treasury & RBI ledger

1. Admin: select local reserve account **1**, load the position/movements, and review **Matched** reconciliation.
2. Complete an external simulated payment, then refresh to show the reserve movement.
3. Optional hold demo: use an **eligible authorized payment** with matching rail/reserve, reserve INR 25 until a future expiry, then release that hold with a reason.
4. Verify active holds increase/decrease by 25 while the hold itself leaves confirmed balance unchanged.

A new Oracle reserve starts at zero and needs the independently confirmed synthetic opening workflow. Never manually edit balance rows to fund a demo. See [Operations](docs/OPERATIONS.md) for openings, cash delivery, evidence, and reconciliation.

### Users, roles, access requests, and security

1. Admin: create a synthetic employee under Users, activate it, and inspect status.
2. Create a demo read-only role under Roles with appropriate inquiry permissions.
3. Propose the role assignment under Access requests with the intended scope. A different checker approves.
4. Employee: sign in again or refresh the session under Security; navigation reflects current permissions.
5. Demonstrate password/session controls without changing another presenter's credentials mid-demo.

Restricted checkers can review and make named independent decisions; they cannot initiate transfers/purchases/general maintenance. On Oracle, migration 010 synchronizes role permissions but does not assign roles to people. Holding both admin and checker roles retains admin rights.

### Customers & KYC, Product master, and account controls

- **Customers & KYC:** inspect the seeded CIF, then propose synthetic contact/profile changes through an open KYC case and have a different reviewer decide. No government verification is connected. Segment proposals become final only through the KYC decision.
- **Product master:** create identity/version, add type-specific rules and effective availability, submit, independently approve, activate, then show customer discovery. Credit cards use the separate Credit cards catalog.
- **Account controls:** demonstrate supported restrictions or an exact-command override approval. Use a separate disposable account for closure: it needs zero exposure and no open card, fixed deposit, payment, or loan obligations.

### Privacy & compliance

1. Admin: create a synthetic purpose with CONSENT legal basis, version, a reachable demo notice URI, its digest, and effective date.
2. Checker: approve using the current version.
3. Customer: refresh, read the approved notice, acknowledge review, then allow/deny the purpose.
4. Withdraw a grant and inspect immutable history; another customer cannot read it.

The local fixture has a **synthetic approved 12-month audit retention policy** for this journey. That is not an approved bank policy. Oracle needs an effective approved M09_PRIVACY / AUDIT_EVENT retention schedule before consent changes work. Without it, the screen preserves notices/history and disables changes. Obtain the actual approved policy from its owner rather than copying the local fixture. Casework/legal-hold records do not provide automatic erasure or compliance certification.

### Account-holder access key

Customers can read their own linked transactions after sign-in. Global bank admins can review account transactions without a holder key. Other authorized staff need their own permissions/scope and any required key.

Customer: generate a replacement in **Account-holder access key**, copy it privately for authorized staff, and demonstrate one-time display. The previous key stops working; stored keys cannot be retrieved. A key alone never grants access to unrelated accounts or spending permission.

### Ask Moneybags

With the optional provider configured, open Ask Moneybags and try:

- “Show my account balances.”
- “Show my last five transactions.”
- “Show my payment status.”
- “Prepare a transfer of INR 25 to my verified internal beneficiary.”

For the last example, register the other local account with bank code MCPB0000002 and have another employee verify it first. Review the five-minute draft and **separately confirm** it. This assistant route needs the verified account token and internal bank code; the direct Payments form does not. Drafting does not move money.

If the model is unconfigured/unavailable, ordinary banking remains usable. Never paste access keys or database/provider credentials into chat. [Assistant documentation](docs/ASSISTANT-README.md) covers authenticated MCP and statement tools.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| Tool command not found | Check PATH and prerequisite versions |
| Dependency downloads fail | Check repository/proxy access; build before restrictive VPN; offline builds need cached packages |
| Run record/port already in use | Run stop-oracle-alpha.ps1; do not kill unrelated processes or delete records blindly |
| -SkipBuild fingerprint mismatch | Rebuild after source/output changes |
| Missing Oracle settings | Check alpha-team.bundle.json and use the current team passphrase |
| ORA-17868 / unknown host | Verify configured hostname, DNS, and required VPN |
| Database timeout | Check VPN route, listener port, service, and database availability |
| ORA-01017 | Check database credentials; these differ from web login |
| Health stays down after VPN recovery | Reconnect, then stop/start if needed |
| HTTP 403 on custom ports | Set the exact browser origin before backend startup |
| Missing tab/action | Check user type, active permissions/scope/authority; refresh session or sign in again |
| Self-approval rejected | Use a different checker |
| Wrong reference accepted nowhere | Account UI fields use numbers; product/GL/application/facility/payment fields use their own IDs |
| Insufficient funds/frozen account | Inspect spendable balance, holds, debit/credit controls, and limits |
| No product offer | Check active approved version, availability, KYC, branch/segment, and permissions |
| External form unavailable | Check simulator enablement; direct account transfers remain separate |
| Internal transfer missing from Payment requests | Use Sent and received transfers or account history |
| Privacy changes unavailable | Oracle needs the approved audit retention schedule |
| Statement cannot issue/download | Check account access, approved narration/masking, locale, and completed statement ID |
| Maturity/interest/billing action too early | Use an eligible period/due date; the app does not advance time for a demo |
| Assistant provider error | Check backend provider/model/key, system proxy, and restart after environment changes |

Service logs are in `.runtime/<service>.log` and `.runtime/<service>.err.log`; gateway and frontend logs use the same names. Process ownership is recorded in `.runtime/microservices-processes.json`. Keep private runtime files/logs out of published demo materials.

## Verification and handoff

Build and regression verification without connecting to Oracle:

~~~powershell
& ./start-alpha-team.ps1 -BuildOnly
~~~

Launcher checks without a real database:

~~~powershell
& ./tools/test-oracle-startup.ps1
~~~

The opt-in Oracle environment suite is now `./tools/test-oracle-environment.ps1 -SkipBuild`; it requires the ALPHA network and team passphrase. Other developer diagnostics are under `tools/`.

Targeted checks with dependencies installed:

~~~powershell
mvn -f backend/pom.xml verify
npm --prefix frontend test
npm --prefix frontend run check
npm --prefix frontend run build
~~~

The previous six-service verification recorded **318 automated local checks** and **seven browser checks**. Live Oracle verification recorded **41 passes, two failures and three skips**; the unresolved findings are an existing account-position reconciliation gap and a bundled demo-password mismatch. See the [current test report](docs/MICROSERVICES-TEST-REPORT.md) for the complete flow coverage, evidence, measured load and limits. The [11 October demo payment record](docs/DEMO-PAYMENTS-VERIFIED-2026-10-11.html) remains a historical verification record.

These checks do not certify live rails, every Oracle product configuration, regulatory compliance, or all crash/concurrency cases. The earlier H2 launchers and their dedicated environment runners have been removed. Java regression fixtures remain available to the build; the application launcher requires Oracle. These historical results do not replace testing the current checkout.

## Further documentation

- [Payment clearing and RBI simulation](docs/PAYMENT-CLEARING.md)
- [Feature coverage and boundaries](docs/FEATURE-COVERAGE.md)
- [Oracle operations](docs/OPERATIONS.md)
- [Combined release / schema upgrades](docs/CREDIT-CARD-MAIN-MERGE.md)
- [Credit cards](docs/CREDIT-CARDS.md)
- [Savings interest and fixed deposits](docs/DEPOSIT-INTEREST.md)
- [Assistant and MCP](docs/ASSISTANT-README.md)
- [Historical Oracle ALPHA fixtures](docs/MCP-ORACLE-DEMO.md)
- [API catalog](docs/API-CATALOG.md) — use the running /v3/api-docs for current schemas
- [English, Hindi, and Kannada UI](docs/MULTILINGUAL.md)
- [Integration decisions](docs/INTEGRATION.md)
- [Earlier verification records](docs/VERIFICATION.md)
- [Source import manifest](docs/source-manifest.json)

tools/assemble.py and tools/wire_frontend.py record the original import; do not rerun them over the integrated implementation. tools/generate_local_schema.py regenerates the H2 fixture after reviewed schema changes.
