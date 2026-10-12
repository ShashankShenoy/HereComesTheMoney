# Moneybags — setup and product demo guide

Moneybags combines a Spring Boot backend, an Oracle JET / Knockout frontend, shared sign-in and permissions, and a balanced transaction ledger. Run it with a fresh local H2 database, a local Oracle Free container, or an existing Oracle database.

This guide covers setup, startup, logins, and demonstrations for each product and supporting banking tab. **Transfers between two users of this demo bank debit the sender and credit the recipient.** External payment rails, card purchases, and the RBI reserve mirror have separate simulation workflows.

## Contents

- [Choose your setup](#choose-your-setup)
- [Prerequisites](#prerequisites)
- [Start the local demo](#start-the-local-demo)
- [Local users and data](#local-users-and-data)
- [Use Oracle](#use-oracle)
- [Oracle startup and VPN recovery](#oracle-startup-and-vpn-recovery)
- [Optional configuration](#optional-configuration)
- [Prepare a demonstration](#prepare-a-demonstration)
- [Product demo walkthroughs](#product-demo-walkthroughs)
- [Supporting banking and administration tabs](#supporting-banking-and-administration-tabs)
- [Troubleshooting](#troubleshooting)
- [Verification and handoff](#verification-and-handoff)
- [Further documentation](#further-documentation)

## Choose your setup

| Setup | Database | Default web address | Restart behavior |
| --- | --- | --- | --- |
| Local demo | In-memory H2 with synthetic users/products | http://localhost:5173 | All demo changes reset to the seeded starting state |
| Self-contained Oracle demo | Local Oracle Free container and its own persistent volume | http://localhost:5174 | Committed records remain in the Docker volume |
| Shared ALPHA Oracle demo | Team ALPHA schema, unlocked from an encrypted repository bundle | http://localhost:5174 | Committed records remain in ALPHA |
| Other Oracle schema | Your configured Oracle schema | http://localhost:5174 | Committed records remain |

Choose local H2 for a first run or repeatable presentation. It needs no Oracle installation, VPN, database password, AI key, or Kafka broker. Choose the self-contained Oracle demo for a persistent local database. It needs Docker Desktop, but no private Oracle host or credentials. Use the existing-schema Oracle option to connect to an organization's database. The fully seeded H2 fixtures are **not automatically created in Oracle**.

## Prerequisites

| Tool | Requirement |
| --- | --- |
| Java JDK | 17 or newer; latest verification used JDK 21 |
| Maven | 3.9 or newer |
| Node.js and npm | Node.js 20 or newer; latest verification used Node.js 22 |
| PowerShell | Windows PowerShell 5.1 or PowerShell 7 for the supplied launchers |
| PowerShell 7 | Required for the encrypted shared ALPHA launcher |
| Browser | A current desktop browser; separate profiles help demonstrate several users |
| SQL Developer / SQL*Plus | Oracle schema installation and diagnostics only |
| Docker Desktop | Self-contained Oracle demo only; start its engine before launching |

Put Java, Maven, Node.js, and npm on PATH. Obtain the project, then open PowerShell in the **integrated directory** containing this README, backend, frontend, and the startup scripts:

~~~powershell
Set-Location 'C:/path/to/integrated'
java -version
mvn -version
node --version
npm --version
~~~

**Every command below assumes this directory.** Replace example paths and configuration placeholders. The first build needs access to Maven and npm repositories to download dependencies.

## Start the local demo

~~~powershell
& ./start-local.ps1
~~~

Wait for the **Local workspace** message. The launcher runs backend verification, installs frontend dependencies with npm ci, checks/tests the frontend, builds frontend/dist, and starts Java and Node in hidden windows.

| Page | Address |
| --- | --- |
| Customer sign-in | [localhost:5173/login](http://localhost:5173/login) |
| Bank-admin / employee sign-in | [localhost:5173/employee/login](http://localhost:5173/employee/login) |
| API documentation | [localhost:5173/swagger](http://localhost:5173/swagger) |
| Health | [localhost:5173/actuator/health](http://localhost:5173/actuator/health) |
| Backend directly | http://127.0.0.1:8090 |

Health should return status **UP**. Stop with:

~~~powershell
& ./stop-local.ps1
~~~

Restarting the local backend resets all synthetic data. Keep it running until a multi-step demonstration finishes.

The local launcher builds its own backend JAR and frontend bundle. On first backend start, the application creates installation-specific `runtime/cif.key` and `runtime/mfa.key` files. Neither those keys nor `.runtime/` build outputs, logs, process records, and workstation-bound encrypted settings are needed from another person's machine. A fresh checkout generates its own files.

For alternative ports, allow the new browser origin before startup:

~~~powershell
$env:FRONTEND_ORIGINS='http://localhost:5177,http://127.0.0.1:5177'
& ./start-local.ps1 -BackendPort 8097 -FrontendPort 5177
~~~

Use http://localhost:5177 for that run. Stop an earlier recorded local run before starting another. When returning to default ports, update or remove the FRONTEND_ORIGINS override.

## Local users and data

All four **synthetic local users** use password **LocalBanking!2026**. These credentials apply only to the isolated local fixture.

| Username | Sign-in page | Role / demonstration |
| --- | --- | --- |
| customer | /login | Own savings account, transfers, deposits, card/loan applications, statements |
| customer2 | /login | Different customer; receive money, send it back, inspect own history |
| admin | /employee/login | Global bank administrator / maker; products, servicing, ledger, teller, treasury, administration |
| checker | /employee/login | Restricted independent checker; named approvals and verification |

| Fixture | Fresh local value |
| --- | --- |
| Customer 1 account | MB000000001, internal account ID 1, INR 75,000 |
| Customer 2 account | MB000000002, internal account ID 2, INR 25,000 |
| CIFs | demo-cif-1 and demo-cif-2, with synthetic verified KYC |
| Branch | MUM001 |
| Savings | Everyday Savings, product/version 1, illustrative 3% annual rate |
| Loan | Monthly Personal Loan, product/version 2, illustrative 12% fixed annual rate |
| Fixed deposit | Fixed Deposit, product/version 3, illustrative 6.5% fixed annual rate |
| Credit card | Moneybags Classic, approved version 1, illustrative 24% annual rate |
| Example GL IDs | Customer deposits 1; teller cash 2; suspense 3; reserve 6; vault cash 8 |
| Reserve account | ID 1, synthetic opening INR 1,000,000 |
| Branch vault | Registered synthetic opening INR 100,000 |
| Internal bank code | MCPB0000002, for verified-beneficiary routing in the assistant |

Rates and amounts are software-demo fixtures. After transactions, use **current balances**, not these starting values. Customer UI account fields use account numbers; product, application, card, journal, GL, and payment references are separate IDs. Copy newly created IDs from the result shown on screen.

## Use Oracle

### Shared ALPHA database for the team

The repository includes `alpha-team.bundle.json`, encrypted with AES-256-GCM and a separately held team passphrase. It contains the ALPHA JDBC connection, schema password, the installation's CIF/MFA decryption keys, optional assistant provider key, and the synthetic demo logins. The original `.env.ps1` and Windows DPAPI files are tied to one workstation and remain ignored. **The team passphrase is not in Git.** Obtain it through your team's private channel; do not place it in a commit, issue, or chat transcript.

On a new Windows checkout, connect the ALPHA network/VPN, open **PowerShell 7** in this directory, and run:

~~~powershell
./start-alpha-team.ps1
~~~

Enter the team passphrase at the hidden prompt. The launcher decrypts the bundle, installs the matching CIF/MFA keys locally, protects the demo-login record for the current Windows user, builds the app, and starts it at [localhost:5174](http://localhost:5174). The database password is passed to the backend through its process environment. Run `./show-mcp-demo-credentials.ps1` in your own console to view the synthetic ALPHA web logins. Stop with `./stop-oracle-alpha.ps1`. For a second start, repeat the same unlock command. On a restrictive VPN, build first with `./build-before-start.ps1`, reconnect, and use `./start-alpha-team.ps1 -SkipBuild`.

The passphrase for the bundle created on the original workstation is stored only in ignored `.runtime/alpha-team-passphrase.txt`. Its owner must share it securely with authorized teammates. If the passphrase or encrypted bundle is lost, the original workstation can create a replacement with `./tools/export-alpha-team-bundle.ps1 -ReplaceBundle`; distribute the replacement passphrase separately. A replacement invalidates the previous passphrase. Existing local installation keys that differ from the bundle stop startup rather than overwriting them.

The shared database is a **single persistent environment**: every teammate sees and can change the same accounts, payments, approvals, and balances. Coordinate demos and use current balances. Repository access alone does not unlock the bundle, but anyone who obtains both the bundle and passphrase can recover all included secrets. The old CIF/MFA keys were previously present in Git history; removing them from the current revision did not erase that history. Rotation of those keys against existing ALPHA records requires a data migration.

### Self-contained Oracle demo from a fresh checkout

With Docker Desktop running, start from this directory:

~~~powershell
& ./start-oracle-demo.ps1
~~~

The first run downloads Oracle Free, starts an isolated `MONEYBAGS` schema on loopback port **1529**, applies the schema scripts, and builds/starts the app at [localhost:5174](http://localhost:5174). It may take several minutes and needs enough disk space and memory for Oracle. Later starts reuse the database volume. No private `.env.ps1`, VPN, or previous workstation's `.runtime` files are required. See [the Oracle Free container source](https://github.com/gvenzl/oci-oracle-free) for image details.

The first run generates random database and web-administrator passwords in ignored `.runtime/oracle-demo.env`. Sign in at `/employee/login` as **admin**, using `BOOTSTRAP_PASSWORD` from that file. The file and Docker volume belong together: keep the original file when reusing the volume. Stop the application with `./stop-oracle-alpha.ps1`; the Oracle container remains running and its data persists. To stop the container without deleting its data, run `docker compose --env-file .runtime/oracle-demo.env -f compose.oracle-demo.yaml stop`.

This path installs the schema and first administrator. It does **not** copy the existing ALPHA database, its users, encryption keys, accounts, balances, or product setup. Create synthetic customer users with the signup form, then configure and approve products/accounts and fund demo balances through the application. For an immediate two-user transfer demonstration with pre-funded accounts and all local fixtures, use `./start-local.ps1`. Do not treat generated passwords or local Oracle data as production credentials or records.

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

**012-rbi-cash-delivery-readiness.sql is a diagnostic, not an additional migration.** Inspect scripts under database/acceptance before running them: the general readiness checks are read-only, but specifically named synthetic fixture scripts can change demo configuration.

If the former 011-credit-cards.sql from the card branch is already installed, **skip 015**. File 015 is installation order; the permanent module/table prefix remains **M11 / M11_CC_**. See [the existing-installation checklist](docs/CREDIT-CARD-MAIN-MERGE.md).

Run applicable read-only checks: [Oracle readiness](database/acceptance/oracle-readiness.sql), [integrated schema/balances](database/acceptance/integrated-main.sql), and [credit cards](database/acceptance/credit-cards.sql). Resolve missing/invalid objects and accounting exceptions before financial demos. [Operations](docs/OPERATIONS.md) contains the installation details.

### 2. Create the private connection file

The ignored .env.ps1 file is missing from a fresh clone. Preserve an existing file:

~~~powershell
if (-not (Test-Path -LiteralPath './.env.ps1')) {
    Copy-Item -LiteralPath './.env.example.ps1' -Destination './.env.ps1'
}
notepad ./.env.ps1
~~~

Use your SQL Developer connection details:

~~~powershell
# Service-name connection:
$env:DB_URL='jdbc:oracle:thin:@HOST:1521/SERVICE_NAME'
$env:DB_USERNAME='YOUR_MONEYBAGS_SCHEMA'
# For a SID connection, use HOST:1521:SID instead.
~~~

Keep the template's hidden password prompt. DB_USERNAME is the database identity, not a web login. Do not put real database passwords or provider/access keys into tracked files.

For an **empty IAM installation only**, configure the one-time BOOTSTRAP_ADMIN, BOOTSTRAP_USERNAME, and private BOOTSTRAP_PASSWORD settings to create the first administrator, then disable bootstrap. Startup does not reset existing users, passwords, or roles. Set up a separate checker and customer/CIF links through the application, or use existing Oracle users.

### 3. Start, open, and stop

Connect the required database network/VPN:

~~~powershell
& ./start-oracle-alpha.ps1
~~~

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
& ./start-oracle-alpha.ps1 -PaymentSimulationEnabled $false
~~~

## Oracle startup and VPN recovery

If the database VPN blocks dependency downloads, build on a network that permits them first:

~~~powershell
& ./build-before-start.ps1
# Connect the database VPN after the build succeeds.
& ./start-oracle-alpha.ps1 -SkipBuild
~~~

With cached dependencies, build-before-start.ps1 -Offline can build without downloads. Missing cached packages require an online build. **-SkipBuild verifies the recorded source, JAR, and frontend fingerprints**; it does not bypass checks. Rebuild after source/output changes. Stop the backend before replacing its running JAR.

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

For local AI demos, set the provider/model/key in the PowerShell environment **before** starting. start-local.ps1 does not automatically load .env.ps1; the Oracle launcher does. Choose a provider-supported model with tool calling. Java launchers enable the system proxy. See [assistant setup](docs/ASSISTANT-README.md).

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

### 7. External payment simulation and recipients

**Users:** customer, checker, admin. **Screens:** Payments, Beneficiaries, Payments & clearing.

1. Customer: add a recipient, for example Demo External Recipient, account/payment address DEMOEXTERNAL001, bank code TEST0123456.
2. Checker: independently verify it under Beneficiaries; refresh the customer screen.
3. Customer: in the separate **External payment simulation** form, choose your account, that verified recipient, UPI, amount **10**. Review/confirm and record the payment ID.
4. Checker: **Authorize simulated payment**, using that ID and local reserve account **1**.
5. Admin: **Record simulated rail outcome**, first ACCEPTED, then SETTLED.
6. Customer: refresh **Payment requests → View progress**, ending at **Settled in simulation**.

**Expected:** initiation creates a request; authorization debits the customer, and the simulator uses suspense/reserve accounting. **It does not credit customer2. Use walkthrough 2 for transfers between demo users.**

Illustrative simulator limits: UPI/IMPS up to INR 100,000; RTGS starts at INR 200,000. A fresh INR 75,000 account cannot fund RTGS without additional valid demo funding. Invalid transitions/repeated terminal outcomes do not duplicate financial postings. These rail choices do not represent live network connections.

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
| Run record/port already in use | Use the matching stop script; do not kill unrelated processes or delete records blindly |
| -SkipBuild fingerprint mismatch | Rebuild after source/output changes |
| Missing Oracle settings | Create the private .env.ps1 and replace placeholders |
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

Local logs: .runtime/backend.log, backend.err.log, frontend.log, frontend.err.log. Standard Oracle logs use .runtime/oracle-alpha-backend.log and corresponding frontend/error logs. This workstation's prepackaged runner may use product-verified-backend.log and product-verified-frontend.log instead. Keep private runtime files/logs out of published demo materials.

## Verification and handoff

Full build and verification:

~~~powershell
& ./build-before-start.ps1
~~~

Targeted checks with dependencies installed:

~~~powershell
mvn -f backend/pom.xml verify
npm --prefix frontend test
npm --prefix frontend run check
npm --prefix frontend run build
~~~

The latest verification before this documentation update passed **98 backend tests**, **32 frontend tests**, syntax checks, and frontend build. Two-user Payments checks covered balances/history, reload, lost-response retry, insufficient funds, ownership, return transfer, and mobile layout. A separate local admin check verified both account histories and a balanced GL journal without a holder key. Oracle connectivity and read-only journal/reserve consistency were checked. See [the latest demo payment record](docs/DEMO-PAYMENTS-VERIFIED-2026-10-11.html).

These checks do not certify live rails, every Oracle product configuration, regulatory compliance, or all crash/concurrency cases. H2 demonstrations use generated synthetic fixtures; Oracle retains its own users/products. This documentation update does not change credentials, install schema, or reset balances.

## Further documentation

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
