# Money Bags — integrated banking workspace

English, Hindi and Kannada interface support is documented in [Multilingual UI](docs/MULTILINGUAL.md).

One Spring Boot application, one Oracle JET / Knockout frontend, and the supplied Oracle schema. The original module folders are preserved. New work lives entirely in `integrated/`.

**This is a runnable integration and local banking demonstrator, not a production-certified bank.** All requested domains have navigation and API-backed workflow screens. Supported money movements use balanced ledger postings. External rails are explicitly simulated. Oracle acceptance, broker integration, institution-specific policies and compliance validation remain deployment gates; see [feature coverage](docs/FEATURE-COVERAGE.md).

## Start the local demo

Prerequisites: Java 17+, Maven 3.9+, Node.js 20+ and npm. From the repository root, start the app with:

```powershell
& ./Downloads/integrated/start-local.ps1
```

On every start, the launcher calls `build-before-start.ps1`, which runs Maven clean verify, npm ci, the frontend check, and the frontend build. It serves the newly built `frontend/dist` files.

Open [the banking workspace](http://localhost:5173). The backend listens at `http://127.0.0.1:8090`; [API documentation](http://localhost:5173/swagger) and [health](http://localhost:5173/actuator/health) use the same frontend proxy.

The starter runs Java and Node in hidden processes and checks backend health. A failed build stops startup before either process launches. Logs and verified process IDs are stored under `.runtime/`. Stop the running app before pulling changes and starting again:

```powershell
& ./Downloads/integrated/stop-local.ps1
```

`start-local.ps1 -BackendPort 8091 -FrontendPort 5174` selects alternate ports. The local profile uses Tomcat NIO2 to accommodate Windows hosts where the default Java selector cannot create its loopback socket. It binds both services to loopback.

### Local users and data

All four **synthetic local** users use password `LocalBanking!2026`:

| Login | Purpose |
| --- | --- |
| `admin` | Maker and banking administration |
| `checker` | Bank Checker: read access and independent approvals only; cannot initiate other changes or approve the maker's own request |
| `customer` | Self access to account 1 / `demo-cif-1` |
| `customer2` | Self access to account 2 / `demo-cif-2` |

Account 1 starts at INR 75,000; account 2 at INR 25,000. Branch: `MUM001`. Savings product/version: `1`; fixed-rate monthly loan product/version: `2`. The reserve is synthetic, and displayed demo FX rates are deliberately not market prices.

Customer 1's demonstration hash is:

```text
customer-demo-hash-xxxxxxxxxxxxxxxxxxxxxxxx
```

Use the **account number** shown to the customer in **Banking → Account transactions**, **Banking → Account-holder access key**, and account fields in the operation forms. The app resolves it to the numeric account ID expected by existing service APIs. That ID remains an internal database reference used to join ledger and account records; existing API calls using it remain supported. Authorized staff can enter an account number to identify active holders who can provide a key. Existing keys cannot be retrieved from their stored digests. A signed-in customer can generate a replacement key in the same section; the old key then stops working.

Customers can view balances, transactions, and statements for their linked accounts after signing in. An employee with an active, unrestricted, global `BANK_ADMIN` assignment and `TXN_READ` permission can review account transactions and their details without an account-holder key; those reads are audited. Other staff need their own IAM permission and account scope plus a matching holder key for protected transaction details. The key alone never grants account access. A customer cannot use another customer's key to read another account. Payment receipts, account positions, and statement downloads still follow their separate access rules.

The **Transactions & ledger** page shows account transactions directly, displaying 25 rows at a time from its most recent 100 results. Duplicate read operations are omitted from its operation menu; the underlying APIs remain available to other clients. **Show ledger actions** loads the remaining transfer, reversal, fee, and reconciliation workflows only when needed. Account number suggestions load when the search field receives focus. The account transaction endpoint reads its list from one Oracle query and applies the account access check before returning financial fields.

Staff can find **Treasury & RBI ledger** under Banking. For an existing Oracle installation, run [database/010-bank-admin-and-checker-roles.sql](database/010-bank-admin-and-checker-roles.sql) as the Moneybags schema owner with F5 in SQL Developer, then sign out and back in. It gives `BANK_ADMIN` every configured permission and creates `BANK_CHECKER` with read and independent approval permissions. It can be rerun safely; it does not assign or remove roles from users. Use the IAM access request workflow to assign `BANK_CHECKER` to an employee and revoke their broader role after an independent checker approves both requests. The section displays Moneybags' simulated local reserve mirror, not a live RBI connection.

The demo is an in-memory H2 database in Oracle compatibility mode. **Restarting the backend resets all demo data.** It never uses the Oracle connection guide's credentials. Do not use the local profile for real customer data or public hosting.

### Demo customer sign-up

The sign-in page offers **Create a customer account** when `CUSTOMER_SIGNUP_ENABLED=true` (enabled by the local and Oracle ALPHA launchers). The administrator's **Users → Create user → CUSTOMER** option uses the same demo onboarding flow. It accepts adults only; minors need the existing guardian-assisted staff workflow. A new customer gets an active login, a linked CIF, a `SELF` customer role, and an assumed approved KYC case. No identity evidence is collected or checked. After signing in, the customer can open an eligible savings or current account from **Accounts**. A zero-minimum-opening-balance account activates immediately; an account requiring opening funding remains pending until the funding clears. The customer role is limited to the app's retail customer permissions.

Other deployments leave sign-up disabled by default. Use `CUSTOMER_SIGNUP_BRANCH` to select the home branch, and disable the feature before connecting the application to real customer data.

## Credit cards

Open **Banking → Credit cards** for versioned product terms, applications with independent approval, card activation/freeze/block/closure, simulated purchases and refunds, monthly statements, and repayments from the linked deposit account. The local demo includes **Moneybags Classic**; sign in as `customer` to apply and `checker` to approve, then return to the customer to activate the card.

The service adds six `M11_CC_*` tables and balanced M05 postings, with request replay protection, database locks, credit-limit checks, current KYC checks and scoped access. Its configurable demo interest policy uses simple ACT/365 interest from purchase with no interest-free grace period. See [credit card terms, setup, APIs and verification](docs/CREDIT-CARDS.md).

Existing Oracle deployments must install **`database/015-credit-cards.sql` once** after migrations 001–014, configure `CC_LIMIT` approval authority, and sign in again. This script was formerly named `011-credit-cards.sql` on the credit card feature branch. If that script is already installed, **skip 015** and run the read-only credit card acceptance checks; renumbering does not require rebuilding tables. The local H2 demo installs its generated schema automatically. No Oracle migration is executed by application startup. Card-network processing is simulated.

**015 is the SQL file's installation sequence; M11 is the permanent credit card module name.** Oracle tables continue to be named `M11_CC_*`, including installations created from the former filename. Do not rename them to M15 or rerun the credit card installer. Git commits and Oracle commits are separate: updating Git does not change the installed schema. See [the numbering explanation and existing-installation checklist](docs/CREDIT-CARD-MAIN-MERGE.md#sql-file-number-and-oracle-table-names).

The combined release preserves the newer teller/vault/RBI migrations `011`–`014`. Review [the main-branch integration and database upgrade steps](docs/CREDIT-CARD-MAIN-MERGE.md) before starting the combined application against an existing Oracle schema.

## What is connected

- Credit card products, independent application approval, simulated purchases/refunds, card controls, monthly billing and linked-account repayments. See [credit card setup and workflow](docs/CREDIT-CARDS.md).
- IAM, customer/CIF/KYC and versioned product administration.
- Account opening, parties, nominees, restrictions, limits, approved overrides and closure controls.
- Transfers, teller cash, fixed-fee collection, reversal workflows, holds, double-entry journals and reconciliation.
- Verified beneficiaries, simulated interbank payments, clearing operations and reserve settlement.
- Loan origination, independent sanction, acceptance, disbursement, fixed monthly schedules, due-interest accrual and repayments.
- Immutable statement snapshots with PDF/CSV/HTML downloads and hash-gated access.
- Customer chat and authenticated MCP tools for exact internal-transfer and outbound-payment drafts, payment status, the last 1–100 transactions, and statement generation, request status, preview, and download paths.
- Purpose/consent, legal-hold, casework and audit-evidence workflows.
- Currency master, approved FX rates and an optional HTTPS provider adapter.
- Optional Kafka publication from transactional outboxes for M05–M08.

Banking screens use the backend OpenAPI contract to render Oracle JET fields and Knockout observables, including nested objects, repeating lines, dates, idempotency keys and optimistic-version headers. The menu uses Oracle JET CoreRouter. Original IAM/CIF/product screens remain integrated. Credit cards have a dedicated customer/staff workspace using the same Oracle JET forms and authenticated APIs.

### Customer assistant and MCP

Signed-in customers can ask **Ask Moneybags** for balances, a specific count of recent transactions, saved beneficiaries, payment status, and statements. Asking to add a beneficiary opens a secure form; the recipient details go directly to the banking API and registration stays pending until independent verification. An active internal beneficiary yields a five-minute transfer draft; separate confirmation posts a balanced Module 5 ledger transaction, debits the source, and credits the recipient. An external beneficiary yields a simulated outbound rail draft; confirmation creates a pending Module 6 payment instruction, with later status checks distinguishing pending, rejected, refunded, and settled outcomes. A statement request for a linked account and past period of at most one year uses the existing reporting authorization and immutable snapshot workflow. The tool returns a request/statement ID and an authenticated download path; PDF, CSV, and HTML are available through the reporting API. Existing issued statements for the same period are reused.

The same role-filtered tools are available at `POST /api/v1/assistant/mcp` with a Moneybags bearer session and the documented MCP headers. Tool calls are limited to the signed-in customer's account and current permissions. `draft_internal_transfer` prepares a review card for an active internal beneficiary; separate authenticated confirmation posts the ledger transfer and credits the recipient. `draft_payment` handles simulated outbound rails. Neither tool accepts a caller-supplied customer identity or confirms its own draft. See [assistant tools and MCP examples](docs/ASSISTANT-README.md) for names, arguments, and status behavior.

## Use Oracle

Read [the Oracle and operations runbook](docs/OPERATIONS.md) first. The application does not migrate Oracle at startup.

- For an existing installed schema, inspect compatibility and apply only missing additive scripts `002` through `015` with your DBA. The `012-rbi-cash-delivery-readiness.sql` file is a read-only diagnostic, not another migration.
- `database/001-original-oracle.sql` is the supplied **destructive clean-install script**. It is retained for traceability and fresh disposable schemas. Never run it against an existing database containing needed data.
- Supply `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `FRONTEND_ORIGINS` through your environment or secret manager. No original connection-guide credentials were copied into application configuration.
- Omit `--spring.profiles.active=local`. Enable first-admin bootstrap only for an empty IAM setup, with a new private password.

```powershell
$env:DB_URL='jdbc:oracle:thin:@<host>:1521/<service>'
$env:DB_USERNAME='<application-user>'
$env:DB_PASSWORD='<obtain-from-your-secret-manager>'
$env:FRONTEND_ORIGINS='https://<your-banking-host>'
java -jar Downloads/integrated/backend/target/moneybags-integrated-1.0.0.jar
```

For the development proxy, set `BACKEND_URL=http://127.0.0.1:8080` and run `node Downloads/integrated/frontend/dist/server.mjs`. Use a TLS reverse proxy and institution-managed deployment configuration outside the local demo.

### Oracle connection settings after cloning

The launcher requires `Downloads/integrated/.env.ps1`. This private file is ignored by Git and is therefore missing from a fresh clone. From the repository root, copy the supplied template **only if `.env.ps1` does not already exist**:

```powershell
if (-not (Test-Path -LiteralPath '.\Downloads\integrated\.env.ps1')) {
    Copy-Item -LiteralPath '.\Downloads\integrated\.env.example.ps1' -Destination '.\Downloads\integrated\.env.ps1'
}
notepad .\Downloads\integrated\.env.ps1
```

Replace the placeholders using the same Oracle connection you use in SQL Developer:

- `DB_URL`: `jdbc:oracle:thin:@HOST:PORT/SERVICE_NAME`. If that connection uses a **SID** instead of a service name, use `jdbc:oracle:thin:@HOST:PORT:SID`.
- `DB_USERNAME`: the owner of the existing Moneybags tables, not the banking application's login name.
- The template prompts for the Oracle password with hidden input on each start; do not put a real password in the tracked example file. It is passed to Java through the environment. AI provider keys are optional and separate from the database settings.

Save the file, connect to the required network/VPN, and run `& ./Downloads/integrated/start-oracle-alpha.ps1`. The launcher checks configuration before building. Do not enable the `local` Spring profile for Oracle. Startup does not install database migrations or copy H2 demo data into Oracle.

For basic Oracle host/port URLs, the launcher checks DNS and database-port reachability before building. `ORA-17868: Unknown host specified` means the configured hostname cannot be resolved; connect the database VPN and verify the hostname. After an already successful build, use `start-oracle-alpha.ps1 -SkipBuild` on the VPN. The launcher records its processes immediately, includes the actual JVM behind Windows `javapath`, and cleans up its own processes if startup fails. The stop script checks each recorded process's start time before stopping it. The frontend health check uses basic HTTP parsing so it works in Windows PowerShell without Internet Explorer setup.

`& ./Downloads/integrated/start-oracle-alpha.ps1` runs backend verification, frontend dependency installation, syntax checks, frontend tests and the frontend build, then starts the application against the Oracle schema configured in `.env.ps1`, at `http://localhost:5174`; `& ./Downloads/integrated/stop-oracle-alpha.ps1` stops it. The original ALPHA workstation also configured OpenRouter in its private settings; those settings are not included in a clone. The `mcp_demo_*` users are optional seeded test accounts, not a separate MCP service. An authorized administrator can create and grant other users in the IAM screens, then link customer users to their CIFs and accounts. Startup never displays an existing Oracle administrator password. The private `show-mcp-demo-credentials.ps1` script displays only the four synthetic demo passwords and access keys in your own console. `start-local.ps1` and the assistant preview use temporary H2 data instead of Oracle.

After pulling new code, run `stop-oracle-alpha.ps1` and then `start-oracle-alpha.ps1` from the same updated checkout. The restart rebuilds the JAR and UI and replaces the running processes.

If the Oracle VPN blocks Maven Central, stop Oracle Alpha and run `& ./Downloads/integrated/build-before-start.ps1` while disconnected from the VPN. Reconnect, then run `& ./Downloads/integrated/start-oracle-alpha.ps1 -SkipBuild`. The `-SkipBuild` path verifies that the source files, JAR, and frontend output match the completed build; it refuses to launch after a pull or source edit until you rebuild.

If all dependencies are already cached, `build-before-start.ps1 -Offline` can build and run the backend/frontend tests while connected to the database VPN. It uses Maven offline mode and npm's local cache. Missing cached packages require the normal online build. File fingerprints use ordinal ordering so Windows PowerShell and PowerShell 7 agree on the verified build.

`restart-oracle-alpha.ps1` checks the build, stops the recorded application processes, and starts Oracle mode with `-SkipBuild`. Its JAR is `backend/target/moneybags-integrated-1.0.0.jar`, the standard Maven output containing all integrated modules. It neither builds a separate credit card service nor installs SQL. The startup/VPN cleanup fixes check connectivity, track the actual Java process and stop failed launches; they do not remove database rows.

### Oracle startup and VPN recovery

The commands below work in Windows PowerShell 5.1 and PowerShell 7. An earlier fingerprint mismatch between them was fixed by sorting file paths with ordinal comparison; rebuild once with the updated helper to replace an older build record. From the repository root:

```powershell
cd "C:\Users\Nikunj_Mittal\Desktop\Training\MONEYBAGS_PROJECT\moneybags-integrated"
# Only if this window refuses to run the scripts:
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned
& .\Downloads\integrated\stop-oracle-alpha.ps1
# If dependencies are blocked by the VPN, disconnect before this build:
& .\Downloads\integrated\build-before-start.ps1
# Connect the database VPN before the next command and keep it connected:
& .\Downloads\integrated\start-oracle-alpha.ps1 -SkipBuild
```

After a successful build, routine starts need only the VPN and `start-oracle-alpha.ps1 -SkipBuild`; stop an existing run first. Open [Oracle Moneybags](http://localhost:5174) and sign in with an application account. The Oracle schema password is separate from the application password. A backend restart preserves Oracle data.

If connection fails:

1. Run `stop-oracle-alpha.ps1`, connect/reconnect the database VPN, and confirm the same connection works in SQL Developer.
2. Check the database hostname and port from `.env.ps1` using `Resolve-DnsName <database-host>` and `Test-NetConnection <database-host> -Port 1521`. DNS success is required first; `TcpTestSucceeded` must be `True`. Successful TCP connectivity does not verify the password or service name.
3. Restart with `-SkipBuild`. If source actually changed, stop and rebuild. Use `build-before-start.ps1 -Offline` when dependencies are cached, or build off VPN, reconnect, and start again.
4. For a remaining failure, read `Downloads/integrated/.runtime/oracle-alpha-backend.log` and `.err.log`. [ORA-17868](https://docs.oracle.com/en/error-help/db/ora-17868/) indicates an unknown hostname; a connection timeout points to network/listener reachability; [ORA-01017](https://docs.oracle.com/en/error-help/db/ora-01017/) indicates invalid credentials or denied database authorization. Do not reinstall the schema to fix a VPN failure.

The launcher now rejects missing settings, unreachable database hosts/ports, and occupied app ports before launching, and cleans up its recorded processes after startup failure. If the VPN drops during a run, reconnect it and check [backend health](http://127.0.0.1:8091/actuator/health); restart with stop/start if health remains down.

### Is a database COMMIT needed?

- **Application actions:** no manual SQL Developer `COMMIT` is needed. Successful product configuration, approval, purchases, repayments and refunds commit through the application's transactions; a failed command rolls back its writes.
- **Credit card migration 015 (formerly 011-credit-cards.sql):** its final `COMMIT;` commits the permission/ledger seed changes. Oracle also commits DDL implicitly. If the full script already completed successfully, do not run it again or issue another commit for the application.
- **Your manual SQL `INSERT`/`UPDATE`/`DELETE`:** commit the intended successful changes in that SQL session unless autocommit is enabled. Acceptance scripts contain only `SELECT` queries and need no commit. A SQL Developer commit cannot commit a separate application's connection. See [Oracle COMMIT documentation](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/COMMIT.html).

## Verification and handoff

**Updated main integration — 7 October 2026:** PR #8 merged the newer main `da8929e` and credit card feature `78ea458` into main `495888b`. A fresh offline build on VPN passed **34 backend tests**, **14 frontend tests**, JavaScript checks and builds. The combined JAR then passed Oracle ALPHA admin/checker product approval, purchase/freeze/repayment/refund/replay and matched reconciliation. Main's account, paged GL, teller and treasury inquiry APIs remained available. All needed tables/columns were already installed, so no installer was rerun. Two existing treasury triggers invalidated by schema changes were recompiled without changing their source or data; the schema now has zero invalid objects and no journal/vault/reserve/card accounting mismatches. SQL file **015** keeps the permanent **M11** module/table names. See [integration verification and Oracle upgrade order](docs/CREDIT-CARD-MAIN-MERGE.md).

**Credit card addition — 7 October 2026:** Maven `verify` passed all 32 backend tests; all 14 frontend tests, JavaScript checks, and the frontend build passed. Both the H2 workflow and an Oracle ALPHA staff workflow passed. On Oracle, `admin` proposed Moneybags Classic and `checker1` independently approved the terms and a INR 25,000 synthetic card. Activation, purchase, frozen-card repayment, full refund, unfreeze, replay and ledger reconciliation passed. Six tables, three enabled/valid triggers and three ledger accounts were verified; all acceptance exception queries returned zero rows. The test deposit balance was restored to INR 1,050 and the card balance to zero. See [credit card verification and acceptance](docs/CREDIT-CARDS.md#verification) for scope and remaining checks.


The integrated test suite covers authentication/CORS, signed-in customer ownership and officer key checks, balanced/idempotent transfers, activation fences, teller controls, FX approval, one-time key issuance, reproducible statements and PDF downloads, payment settlement, loan disbursement/accrual/repayment, exact-command override approval, freeze/release, fee collection and branch isolation.

Automated backend tests use the generated H2 schema with keys and check constraints; they provide isolated regression coverage. The combined application was also tested separately against Oracle, including trigger enforcement and locking from two independent sessions. These checks do not cover all concurrent financial workflows, query plans or crash recovery. Kafka and the external FX provider have not been exercised against live services.

- [Full API catalog](docs/API-CATALOG.md)
- [Feature-by-feature coverage and boundaries](docs/FEATURE-COVERAGE.md)
- [Integration decisions and data flow](docs/INTEGRATION.md)
- [Operations and Oracle acceptance](docs/OPERATIONS.md)
- [Verification record](docs/VERIFICATION.md)
- [Source import manifest](docs/source-manifest.json)
- [AI assistant setup, skills, tools, MCP, and usage](docs/ASSISTANT-README.md)
- [Oracle ALPHA MCP demo, users, and smoke checks](docs/MCP-ORACLE-DEMO.md)

The `tools/assemble.py` and `tools/wire_frontend.py` scripts record the original import process. **Do not rerun them over the integrated implementation.** `tools/generate_local_schema.py` can regenerate the H2 fixture after reviewed schema changes.
