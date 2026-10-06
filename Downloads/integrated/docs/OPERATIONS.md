# Operations and Oracle acceptance

## Environment profiles

| Setting | Local demonstrator | Oracle deployment |
| --- | --- | --- |
| Profile | `local` | Default; do not enable `local` |
| Database | Fresh in-memory H2, generated fixture | Existing Oracle schema; no startup DDL |
| Seed | Public synthetic users/accounts | No demo seed |
| Payment simulator | Enabled | Disabled unless explicitly configured |
| Kafka | Disabled | Opt-in after broker/ACL testing |
| FX provider | Unconfigured | Trusted HTTPS endpoint and secret supplied externally |
| Bind/transport | Loopback, Tomcat NIO2 | Deployment-controlled interface, TLS ingress |

Build before startup: `mvn -f integrated/backend/pom.xml verify`. On Windows, stop the running JAR before packaging because it may be locked. `npm --prefix integrated/frontend ci` restores the lockfile dependencies; `npm --prefix integrated/frontend run build` creates `frontend/dist`. The provided development server accepts only a loopback HTTP `BACKEND_URL`; use a managed reverse proxy for a distributed/TLS production topology.

Run the PowerShell scripts from PowerShell 7 with `& ./integrated/start-local.ps1` and `& ./integrated/stop-local.ps1`. If your organization's execution policy blocks local scripts, use separate terminals instead, without changing the machine policy:

```powershell
java -Djava.net.preferIPv4Stack=true -jar integrated/backend/target/moneybags-integrated-1.0.0.jar --spring.profiles.active=local --server.port=8090
```

```powershell
$env:BACKEND_URL='http://127.0.0.1:8090'
$env:PORT='5173'
node integrated/frontend/dist/server.mjs
```

The start script records process IDs and start timestamps; the stop script refuses to stop a reused PID. `.runtime/backend.log`, `backend.err.log`, `frontend.log` and `frontend.err.log` contain local diagnostics. A startup timeout is an error, not a healthy deployment. Health is `/actuator/health`.

## Oracle schema installation

The supplied database installer remains verbatim in `database/001-original-oracle.sql`. It contains object removal/recreation logic. It is suitable only for a deliberately disposable clean schema, with an appropriate backup/restore plan. **It is not an upgrade migration.** The additive scripts `002`–`007` were later applied to the existing ALPHA demo schema; see [the Oracle MCP demo record](MCP-ORACLE-DEMO.md). No destructive clean installer was run there.

For an existing schema matching the supplied installer, have the DBA review and apply the following missing migrations once, in order:

1. `002-statement-extension.sql`: statement revision intent and M10 foreign keys.
2. `003-integration-extension.sql`: customer hash digest, business audit, beneficiaries, teller cash, currency and FX rate records.
3. `004-override-approvals.sql`: account/version-bound override approval evidence.
4. `005-permission-catalog.sql`: insert missing permission catalog entries only; no role grants and no changes to existing MFA requirements.
5. `006-audit-immutability.sql`: Oracle append-only trigger for MBX audit events.
6. `007-assistant-intents.sql`: short-lived, session-bound assistant action proposals.

Scripts 002–004 are not repeatable DDL. Track applied versions in your release process. Review the original Oracle triggers and function-based indexes; H2 deliberately omits them. Install schema objects as the migration owner and use a separate least-privilege runtime identity under your DBA's grants/synonym strategy. The bundled repositories expect the Money Bags tables to resolve without a schema prefix.

Run `database/acceptance/oracle-readiness.sql` as a read-only diagnostic. The IAM `/api/v1/system/schema` endpoint additionally compares the original M01–M06 record mappings; it is not a complete validator for M07–M10 and MBX. Do not interpret its result as whole-bank certification.

## Configuration

| Environment variable / Spring property | Purpose |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Oracle JDBC connection; keep passwords in a secret manager |
| `SERVER_PORT` | Backend port, default 8080 |
| `FRONTEND_ORIGINS` | Explicit comma-separated CORS origins |
| `BOOTSTRAP_ADMIN`, `BOOTSTRAP_USERNAME`, `BOOTSTRAP_PASSWORD` | One-time first administrator for an empty IAM schema only |
| `CUSTOMER_SIGNUP_ENABLED`, `CUSTOMER_SIGNUP_BRANCH` | Demo-only public customer sign-up (off by default); creates an active CIF with KYC marked verified without evidence and uses the configured home branch |
| `KAFKA_ENABLED`, `KAFKA_BOOTSTRAP_SERVERS` | Optional outbox publisher |
| `spring.kafka.properties.security.protocol` and standard Kafka properties | Deployment TLS/SASL/ACL settings |
| `moneybags.kafka.topic-prefix` | Default `moneybags` |
| `FX_PROVIDER_URL`, `FX_API_TOKEN` | Configured HTTPS FX adapter and bearer secret |
| `moneybags.payment-simulation.enabled` | Explicit local rail simulator opt-in |
| `BACKEND_URL`, `PORT` | Frontend proxy target and port |

Disable bootstrap after the first administrator has been created. Existing users and passwords are never reset by bootstrap. Use the IAM access request/approval screens to grant new catalog permissions; configure product/currency dimensions and amount/rate authorities explicitly. A new permission does not automatically grant access to an existing role. Customer users need an active CIF link and appropriate SELF role assignment; issuing a hash alone grants no account rights.

CORS supports `Authorization`, `Content-Type`, `Idempotency-Key`, `X-Customer-Hash` and version headers. The same-origin proxy avoids browser cross-origin configuration in the local demo. The generated workflow catalog depends on `/v3/api-docs`; keep it available to the frontend at deployment (or replace it with a versioned bundled contract) rather than disabling it blindly. Swagger UI itself can be restricted separately.

## Controlled workflow examples

### Transfers and financial detail

Use **Transactions & ledger → Post an internal transfer**, accounts 1→2, today's date, amount and a new request key. Retrying the identical command returns its original receipt; a changed payload under the same key is rejected. Customer 1 can spend account 1 only. Without the hash the receipt is identifier-only. Enter the demonstration hash and load account 1's transaction list to see amounts.

### Independent override

The product version needs an effective `M03_PM_OVERRIDE_POLICY` and the checker role needs its matching IAM authority code/range. Under **Accounts**, choose **Propose an account limit or interest override**, enable exactly one nested command and name a different employee's user ID. Use a stable `requestId`. The nominated checker reviews and decides that proposal. The maker then submits the exact approved limit/interest command through the corresponding account action. Any payload change, expiry, consumed approval or version change is rejected.

### Simulated payment

Customer creates a beneficiary. An employee independently verifies it. Customer initiates using the beneficiary ID and source account. A distinct checker authorizes the simulation with reserve account 1. Operator records ACCEPTED then SETTLED, or the relevant failure/unknown/refund outcome. The tested settled path produces one customer debit, clears suspense and reduces reserve once. Retain payment IDs when amounts are hash-redacted. Do not treat the simulator as network delivery.

### Loan and statement

Loan officer creates/submits/assesses an application; independent sanction requires an authority such as the local `LOAN_SANCTION`. Customer accepts an offer, officer attaches signed-document evidence, checker verifies it, and officer converts to a facility. Request disbursement and approve as an independent authorized checker. The facility receives a monthly schedule. Accrue due interest before applying a due repayment; future/unsupported prepayments fail.

For a statement, unlock the customer hash, create a request, process it, inspect the immutable result and download. Use en-IN PDF in the local seed. Locale-specific narration and masking catalogs must be approved before other formats/audiences are issued. External delivery is intentionally unavailable without an adapter.

## Kafka

The publisher is post-commit, at-least-once delivery from M05, M06, M07 and M08 outboxes to `moneybags.m05`, `.m06`, `.m07`, `.m08`. It uses partition keys, event ID/type headers, a 60-second lease, bounded retry/backoff and dead-letter status after ten attempts. Idempotent Kafka producer settings do not remove the need for consumer event-ID deduplication after a publish/ack crash.

Provision topics and ACLs externally. Verify broker TLS, outage recovery, lease recovery, duplicate handling and replay under load before enabling. Monitor oldest pending-event age and dead-letter count; investigate before administrative requeue. M01–M04/M09–M10 outboxes retain their supplied APIs; the new Kafka publisher does not automatically fan them into privacy/SIEM consumers. Financial commands remain synchronous JDBC transactions.

## FX adapter contract

The configured HTTPS endpoint receives `base` and `quote` query parameters and must return:

```json
{"base":"USD","quote":"INR","buy":"83.10","mid":"83.20","sell":"83.30","observedAt":"2026-10-05T09:00:00Z","validUntil":"2026-10-05T10:00:00Z"}
```

These numbers are contract examples, not current rates. Redirects are disabled; currency matching, positivity, bid/mid/ask ordering, timestamps and validity are checked. Responses are limited to 64 KiB. Refresh creates a pending proposal for independent approval. Approved cached rates have explicit expiry, and quotes do not post money. JVM truststore/credential rotation must be managed outside the app. No live provider was contacted during verification.

## Acceptance still required before real banking use

Run workflows in a fresh Oracle test schema with production-equivalent triggers, identities, collations, time zones and grants. Verify concurrent transfers/holds/freeze/reversal, restart/crash recovery, idempotency collisions, authority revocation, EOD cutoffs, statement source cuts, Oracle query plans and connection failures. Confirm every supported fee/rate/tax/limit policy against signed institution rules; disabled or unsupported policy branches must remain fail-closed.

Separately validate legal/RBI/data-protection requirements, retention and subject-right execution, storage encryption, key management, document scanning, immutable audit retention, regulatory reports, backup restoration, disaster recovery, security review, performance and operational segregation. This integration does not supply regulatory approval or live payment-network certification.
