# Money Bags — integrated banking workspace

One Spring Boot application, one Oracle JET / Knockout frontend, and the supplied Oracle schema. The original module folders are preserved. New work lives entirely in `integrated/`.

**This is a runnable integration and local banking demonstrator, not a production-certified bank.** All requested domains have navigation and API-backed workflow screens. Supported money movements use balanced ledger postings. External rails are explicitly simulated. Oracle acceptance, broker integration, institution-specific policies and compliance validation remain deployment gates; see [feature coverage](docs/FEATURE-COVERAGE.md).

## Start the local demo

Prerequisites: Java 17+, Maven 3.9+, Node.js 20+ and npm. Run these commands from the repository root:

```powershell
mvn -f integrated/backend/pom.xml verify
npm --prefix integrated/frontend ci
npm --prefix integrated/frontend run check
npm --prefix integrated/frontend run build
& ./integrated/start-local.ps1
```

Open [the banking workspace](http://localhost:5173). The backend listens at `http://127.0.0.1:8090`; [API documentation](http://localhost:5173/swagger) and [health](http://localhost:5173/actuator/health) use the same frontend proxy.

The starter runs Java and Node in hidden processes and checks backend health. Logs and verified process IDs are stored under `.runtime/`. Stop only those processes with:

```powershell
& ./integrated/stop-local.ps1
```

`start-local.ps1 -BackendPort 8091 -FrontendPort 5174` selects alternate ports. The local profile uses Tomcat NIO2 to accommodate Windows hosts where the default Java selector cannot create its loopback socket. It binds both services to loopback.

### Local users and data

All four **synthetic local** users use password `LocalBanking!2026`:

| Login | Purpose |
| --- | --- |
| `admin` | Maker and banking administration |
| `checker` | Independent approval; do not approve the maker's own request |
| `customer` | Self access to account 1 / `demo-cif-1` |
| `customer2` | Self access to account 2 / `demo-cif-2` |

Account 1 starts at INR 75,000; account 2 at INR 25,000. Branch: `MUM001`. Savings product/version: `1`; fixed-rate monthly loan product/version: `2`. The reserve is synthetic, and displayed demo FX rates are deliberately not market prices.

Customer 1's demonstration hash is:

```text
customer-demo-hash-xxxxxxxxxxxxxxxxxxxxxxxx
```

Customers can view balances, transactions, and statements for their linked accounts after signing in; no extra key is needed for self-service reads. An account-holder access key remains available for authorized bank officers to view protected financial details, subject to their normal IAM permission and account scope. A customer cannot use another customer's key to read another account. Without a key, officer transaction and payment receipts return only their identifiers, and officer position reads and statement downloads reject access.

The demo is an in-memory H2 database in Oracle compatibility mode. **Restarting the backend resets all demo data.** It never uses the Oracle connection guide's credentials. Do not use the local profile for real customer data or public hosting.

### Demo customer sign-up

The sign-in page offers **Create a customer account** when `CUSTOMER_SIGNUP_ENABLED=true` (enabled by the local and Oracle ALPHA launchers). The administrator's **Users → Create user → CUSTOMER** option uses the same demo onboarding flow. It accepts adults only; minors need the existing guardian-assisted staff workflow. A new customer gets an active login, a linked CIF, a `SELF` customer role, and an assumed approved KYC case. No identity evidence is collected or checked. After signing in, the customer can open an eligible savings or current account from **Accounts**. A zero-minimum-opening-balance account activates immediately; an account requiring opening funding remains pending until the funding clears. The customer role is limited to the app's retail customer permissions.

Other deployments leave sign-up disabled by default. Use `CUSTOMER_SIGNUP_BRANCH` to select the home branch, and disable the feature before connecting the application to real customer data.

## What is connected

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

Banking screens use the backend OpenAPI contract to render Oracle JET fields and Knockout observables, including nested objects, repeating lines, dates, idempotency keys and optimistic-version headers. The menu uses Oracle JET CoreRouter. Original IAM/CIF/product screens remain integrated.

### Customer assistant and MCP

Signed-in customers can ask **Ask Moneybags** for balances, a specific count of recent transactions, saved beneficiaries, payment status, and statements. Asking to add a beneficiary opens a secure form; the recipient details go directly to the banking API and registration stays pending until independent verification. An active internal beneficiary yields a five-minute transfer draft; separate confirmation posts a balanced Module 5 ledger transaction, debits the source, and credits the recipient. An external beneficiary yields a simulated outbound rail draft; confirmation creates a pending Module 6 payment instruction, with later status checks distinguishing pending, rejected, refunded, and settled outcomes. A statement request for a linked account and past period of at most one year uses the existing reporting authorization and immutable snapshot workflow. The tool returns a request/statement ID and an authenticated download path; PDF, CSV, and HTML are available through the reporting API. Existing issued statements for the same period are reused.

The same role-filtered tools are available at `POST /api/v1/assistant/mcp` with a Moneybags bearer session and the documented MCP headers. Tool calls are limited to the signed-in customer's account and current permissions. `draft_internal_transfer` prepares a review card for an active internal beneficiary; separate authenticated confirmation posts the ledger transfer and credits the recipient. `draft_payment` handles simulated outbound rails. Neither tool accepts a caller-supplied customer identity or confirms its own draft. See [assistant tools and MCP examples](docs/ASSISTANT-README.md) for names, arguments, and status behavior.

## Use Oracle

Read [the Oracle and operations runbook](docs/OPERATIONS.md) first. The application does not migrate Oracle at startup.

- For an existing installed schema, inspect compatibility and apply only missing additive scripts `002` through `008` with your DBA.
- `database/001-original-oracle.sql` is the supplied **destructive clean-install script**. It is retained for traceability and fresh disposable schemas. Never run it against an existing database containing needed data.
- Supply `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `FRONTEND_ORIGINS` through your environment or secret manager. No original connection-guide credentials were copied into application configuration.
- Omit `--spring.profiles.active=local`. Enable first-admin bootstrap only for an empty IAM setup, with a new private password.

```powershell
$env:DB_URL='jdbc:oracle:thin:@<host>:1521/<service>'
$env:DB_USERNAME='<application-user>'
$env:DB_PASSWORD='<obtain-from-your-secret-manager>'
$env:FRONTEND_ORIGINS='https://<your-banking-host>'
java -jar integrated/backend/target/moneybags-integrated-1.0.0.jar
```

For the development proxy, set `BACKEND_URL=http://127.0.0.1:8080` and run `node integrated/frontend/dist/server.mjs`. Use a TLS reverse proxy and institution-managed deployment configuration outside the local demo.

On this workstation, `& ./integrated/start-oracle-alpha.ps1` starts that same application against the already configured ALPHA schema with OpenRouter, at `http://localhost:5174`; `& ./integrated/stop-oracle-alpha.ps1` stops it. The `mcp_demo_*` users are optional seeded test accounts, not a separate MCP service. An authorized administrator can create and grant other users in the IAM screens, then link customer users to their CIFs and accounts. Startup never displays an existing Oracle administrator password. The private `show-mcp-demo-credentials.ps1` script displays only the four synthetic demo passwords and access keys in your own console. `start-local.ps1` and the assistant preview use temporary H2 data instead of Oracle.

## Verification and handoff

The integrated test suite covers authentication/CORS, signed-in customer ownership and officer key checks, balanced/idempotent transfers, activation fences, teller controls, FX approval, one-time key issuance, reproducible statements and PDF downloads, payment settlement, loan disbursement/accrual/repayment, exact-command override approval, freeze/release, fee collection and branch isolation.

Tests run against the generated H2 schema with keys and check constraints. H2 does **not** prove Oracle trigger, locking, execution-plan or concurrency behavior. Kafka and the external FX provider have not been exercised against live services.

- [Full API catalog](docs/API-CATALOG.md)
- [Feature-by-feature coverage and boundaries](docs/FEATURE-COVERAGE.md)
- [Integration decisions and data flow](docs/INTEGRATION.md)
- [Operations and Oracle acceptance](docs/OPERATIONS.md)
- [Verification record](docs/VERIFICATION.md)
- [Source import manifest](docs/source-manifest.json)
- [AI assistant setup, skills, tools, MCP, and usage](docs/ASSISTANT-README.md)
- [Oracle ALPHA MCP demo, users, and smoke checks](docs/MCP-ORACLE-DEMO.md)

The `tools/assemble.py` and `tools/wire_frontend.py` scripts record the original import process. **Do not rerun them over the integrated implementation.** `tools/generate_local_schema.py` can regenerate the H2 fixture after reviewed schema changes.
