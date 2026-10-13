# Moneybags microservices test report

13 October 2026

The six-service application builds and all **318 automated local checks pass**. The live Oracle verification has **two unresolved failures**: an existing account-position reconciliation gap and a demo password that no longer matches the encrypted team bundle. Oracle startup, the rollback-only financial audit suite, authorization checks and the authorized banking load test passed. The environment cannot be marked fully clean until those two failures are resolved. After reviewing these findings, the user explicitly authorized publishing the changes to the main GitHub repository; that authorization does not change the test results.

The Oracle run used the existing Oracle database exclusively: six service JVMs, gateway and frontend, with no H2 database process. Automated regression and destructive isolation tests used a separate ephemeral H2 database. H2 results do not establish Oracle behavior; the results below identify each environment explicitly. See the [architecture and ownership guide](MICROSERVICES.md) for the six services and seven reusable domain libraries.

## Test results

| Suite | Passed | Failed | Skipped | Total |
| --- | ---: | ---: | ---: | ---: |
| Backend unit and regression tests | 139 | 0 | 0 | 139 |
| Frontend regression and proxy tests | 47 | 0 | 0 | 47 |
| Gateway routing and transport tests | 17 | 0 | 0 | 17 |
| Architecture, module ownership and dependency checks | 6 | 0 | 0 | 6 |
| Separate-process integration on local H2 | 45 | 0 | 0 | 45 |
| Complete account, payment, loan, audit, card and deposit flows on H2 | 29 | 0 | 0 | 29 |
| Local startup, integrity, outages, recovery and shutdown | 35 | 0 | 0 | 35 |
| Live Oracle schema, credential preflight, financial audit and services | 41 | 2 | 3 | 46 |
| **Automated totals** | **359** | **2** | **3** | **364** |

There were 361 executed automated checks and three skipped checks. Seven additional manual browser checks passed on the fresh six-service H2 stack: customer sign-in and owned dashboard, loan products/applications, payment availability, privacy notices through audit, card products, owned-account statement inquiry and sign-out. The empty application, card, notice and statement results matched the fresh local fixture. The Loans screen capture is retained at `.runtime/environment-tests/six-service-loans.jpg`.

Each row is a test case or named operational check. Request counts, repeated runs, individual assertions inside the Oracle financial audit suite, and suite-level aggregate markers are not counted again. The [case inventory](MICROSERVICES-TEST-CASES.csv) contains all 364 automated entries and the seven manual entries. The [structured summary](MICROSERVICES-TEST-RESULTS.json) records totals and timing evidence. Backend results comprise 39 shared/security/client checks and 100 domain regression checks. The final publication build ran all 23 gateway and architecture checks together.

## Oracle findings requiring action

| Finding | Observed evidence | Consequence and next action |
| --- | --- | --- |
| Account ID 6 does not reconcile | `M05_V_ACCOUNT_POSITION_RECON`: posted balance `500000`, GL-derived balance `0`, difference `500000`. Exactly one account fails reconciliation. | Inspect its funding and journal history and establish the authoritative correction through the ledger workflow. A direct balance update would conceal the cause. The gap was present in the initial read-only diagnostics before the financial audit test. |
| Customer A cannot use the bundled password | `mcp_demo_customer_a`: BCrypt comparison with the decrypted bundle is false; user and credential are ACTIVE. Its lock has expired; five failed attempts remain recorded. Customer B's bundled password matches and its login succeeds. | Restore the intended demo credential through the authorized administration workflow, then regenerate the encrypted bundle if necessary and rerun verification. The final runner performs a read-only comparison and skips Customer A's login and account-read cases. |

No passwords, password hashes, passkey, customer access keys or database credentials are included in this report or its exported results. The financial audit test used only the guarded synthetic account pair, IDs 3 and 4. Its test transfers, roles, sessions, reversals and audit records were rolled back; both balances and observed table counts returned to their original values. Oracle identity sequences can advance despite rollback. Successful HTTP logins and reads create normal session and audit evidence; sessions created by the service suite were logged out.

Customer B's Oracle demo role intentionally lacks `STATEMENT_READ`, as described in [the existing Oracle demo documentation](MCP-ORACLE-DEMO.md). Its statement-history request correctly returns 403. Authorized Oracle statement-history access is the third skipped check. Statement creation, replay, issue, ownership and PDF/CSV/HTML downloads passed on local H2; they are not claimed as live Oracle successes.

## Normal and financial behavior

The local stack exercised four roles, authentication across services, owned dashboards and account positions, combined OpenAPI documentation with 316 paths, service route ownership, statement workflows and assistant calls to reporting over HTTP. Backend regression tests cover the existing banking, product rules, privacy, deposit-interest, loan, payment, treasury and credit-card behaviors represented by that suite.

The new domain suite exercised complete workflows through six separate JVMs and the gateway:

| Domain | Verified sequence and boundaries |
| --- | --- |
| Accounts | Customer opening, replay, activation, ownership, closure and posting rejection after closure. |
| Payments | Independent beneficiary verification; initiation/replay/conflict; invalid rail amount; cross-customer isolation; independent approval with one ledger debit and treasury hold; settlement/replay; rejection of refund after settlement; eligible UNKNOWN-payment refund and replay. |
| Assistant payment boundary | Draft and HTTP confirmation create one payment; three concurrent confirmations return the same instruction; initiation leaves funds unchanged until approval. A payment-service outage leaves the intent retryable; recovery and replay create one instruction. |
| Loans | Application/replay/conflict; submission and assessment; maker cannot sanction their own work; independent decision; borrower-only offer acceptance; signed document and independent verification; facility conversion; approved disbursement/replay credits exactly 12000; 12 scheduled installments total 12000; premature repayment and future accrual are rejected; due accrual and repayment/replay leave principal outstanding 11000. Loan restart preserves that outstanding balance. |
| Audit and privacy | Approved purpose and retention prerequisites; subject ownership; consent grant/replay/conflict; withdrawal/replay and two-record history; withdrawn consent evaluates false; financial audit inquiry requires authorized staff and preserves exact decimal strings. Audit restart preserves consent history. |
| Cards | Application and independent approval, holder activation, purchase/replay, repayment/replay and reconciliation. |
| Deposits and treasury | Fixed-deposit funding debits 1000 once across replay and denies another customer's access; internal reserves require staff permission and retain posting evidence. |
| Ledger | After mixed payment, lending, card and deposit activity, every journal balances and every account position reconciles in the disposable database. |

`tools/LocalFlowFixtures.java` is guarded to the disposable H2 test listener and synthetic local customer records. It prepares an approved test purpose and makes one synthetic loan installment due, so servicing can be tested without waiting a month. It is never used against Oracle. The full loan, card, deposit and payment mutation sequences above were verified on H2; the live Oracle service checks covered permitted reads, ownership, routing, security and simulation-disabled behavior, plus the separate rollback-only financial audit.

Financial tests checked an exact one-cent transfer, balanced journals, request replay, changed-payload conflict, insufficient funds and position reconciliation. Eight simultaneous requests with the same key produced one transfer. Twelve opposing transfers conserved combined balances. Two competing withdrawals against limited funds allowed exactly one to succeed.

The Oracle financial audit suite additionally verified exact decimal JSON, source and recipient balances, replay, inclusive date filters and leap-day midnight boundaries, account filters, current and revoked authorization, failure rollback, the immutable evidence trigger, balanced journals and append-only reversal history. All application objects were valid, constraints were enabled and validated, triggers were enabled, active accounts had posting fences, proof digests had the expected byte length, and override approvals separated maker and checker. The account reconciliation check failed as recorded above.

## Boundaries and security

The tests covered zero, negative, sub-cent and oversized amounts; self transfers; future value dates; nonexistent recipients; malformed JSON; missing fields and headers; unsupported media types; wrong HTTP methods; unknown routes; invalid identities; ownership isolation; prohibited IAM and reconciliation access; allowed and denied CORS origins; missing customer proof; and immediate revocation across services.

Refresh-token rotation, refresh reuse, duplicate signup, case-insensitive usernames, underage signup, account lockout and TOTP enrollment, incorrect codes, valid codes and replay were tested with disposable local users. Lockout and MFA enrollment tests were not run against shared Oracle users. Live Oracle checks validated anonymous and spoofed identity rejection, invalid bearer rejection, account isolation, restricted administration, rejected invalid transfer amounts, CORS, transport validation and logout across services.

## Startup and failure behavior

The local operational suite rejected duplicate or occupied ports, simultaneous startup locks, missing Oracle settings, source changes and altered artifact hashes. PID timestamp mismatches did not terminate unrelated processes. Corrupt ownership records were retained, and records for dead processes could be cleaned up.

All six services, gateway and frontend were stopped and restarted independently. Readiness reflected the outages, unrelated capabilities stayed available, and recovery preserved sessions, balances, loans, consent history and issued statements. Payment failure/retry preserved assistant intent idempotency. Identity failures denied authenticated access in all five dependent services. A database outage returned service-unavailable responses rather than misleading authentication failures. Invalid CIF and MFA keys caused partial startup to be cleaned up. Every listener was bound to loopback. Successful and failed startups released owned processes and ports, including Windows PowerShell 5.1 startup and shutdown.

The Oracle startup check confirmed eight application components and no local database process. Oracle test shutdown also released every owned component and test port. No shared Oracle database process was stopped for an outage test. A final root `start.ps1 -SkipBuild` run supported the seven browser checks; root `stop.ps1` then removed the process ownership file and released all default and test ports.

## Load measurements

| Environment and workload | Requests | Concurrency | Errors | Elapsed | Median | 95th percentile | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Local H2 dashboard and reporting reads | 64 | 8 | 0 | 875 ms | 72 ms | 118 ms | 136 ms |
| Oracle authorized dashboard and account-position reads | 64 | 8 | 0 | 50,728 ms | 3,944 ms | 6,347 ms | 7,163 ms |

These are short runs through the local frontend and gateway. The workloads differ because the existing Oracle customer role has no statement-read grant. The Oracle latency warrants investigation before setting a production response-time target; this run does not isolate database, network and application costs. It is not a sustained capacity or soak test.

## Problems fixed and rechecked

| Reproduced problem | Verified correction |
| --- | --- |
| Malformed request URLs crashed the frontend process | Return 400 and keep serving subsequent requests. |
| Invalid backend origins accepted userinfo, paths, queries or fragments | Validate the complete loopback HTTP origin before startup. |
| Gateway documentation routes accepted unsupported methods | Restrict them to GET and HEAD and return 405 otherwise. |
| Client cancellation left an upstream download running | Close the corresponding upstream request. |
| A truncated upstream response left a frontend download hanging | Fail the download and keep the frontend process alive. |
| Unsupported content types returned 500 | Return 415 with a structured client error. |
| Missing required request headers returned 500 | Return 400 with a structured client error. |
| Database transaction-start failures became misleading 401 responses | Return 503 from authentication and API error handling. |
| The H2 listener bound to all interfaces | Bind explicitly to `127.0.0.1`; verified every component listener. |
| Windows PowerShell could not resolve `Get-FileHash` through an inherited module path | Hash through .NET; a complete PowerShell 5.1 start/stop run passed. |
| The Oracle audit helper compiled UTF-8 source using the Windows default encoding | Set `javac -encoding UTF-8`; Oracle audit compilation and execution passed. |
| Ending or closing a relationship within one clock tick could violate its strict validity-date constraint | Use the application clock consistently and keep the end at least one persisted microsecond after the start. The existing regression now fixes the clock and verifies direct termination, repeated termination, unchanged start dates and closure of incoming/outgoing relationships without aging the fixture. Read-only Oracle prepared-statement checks passed for equal, earlier and later end timestamps. |
| Oracle proof-digest diagnostics used a character-length check for RAW data | Use `UTL_RAW.LENGTH`, which measures RAW bytes, as specified by [Oracle](https://docs.oracle.com/en/database/oracle/oracle-database/19/arpls/UTL_RAW.html). The three existing digests passed the corrected check. |

The final Maven clean verification, Node syntax checks, frontend build and artifact hash checks passed. An additional build before publication exposed the intermittent relationship failure above; it was corrected and the full build rerun. The Oracle schema diagnostics were also rerun with the new timestamp check, and the original reconciliation finding remained. Earlier separate-process and load measurements were retained; they were not rerun for this customer-relationship correction. Windows sandbox canonical-path errors in Java dependency handling were resolved by running the authorized Oracle verification outside that sandbox; the final compiler logs contain no sandbox access-denied errors.

Final repository checks found exactly one destination for each of the 341 original tracked Java files, including the original combined application moved into the test harness. All 17 changed/new PowerShell scripts parsed without errors. `git diff --check` passed; the verified-build check confirmed that every executable JAR, gateway source and frontend output still matches the current application source. These static checks and repeat verifications are not added to the case totals. Runtime evidence and protected credentials remain ignored by Git. The unresolved Oracle findings remain visible in the published report and case inventory.

## Reproduce the verification

From `Downloads/integrated` in PowerShell 7:

```powershell
./build-before-start.ps1 -Offline
./test-environment.ps1 -SkipBuild
./test-oracle-environment.ps1 -SkipBuild
```

The Oracle command prompts privately for the team bundle passphrase. It checks credentials without changing them, launches with Oracle, records expected permission denials, skips unavailable demo logins, executes the financial audit in a rollback-only transaction and stops its stack in a finally block. It returns failure while the Oracle reconciliation or credential checks fail. Stop an existing application run before either environment suite.

Detailed logs and JSON evidence are under `.runtime/environment-tests/`; they are ignored by Git. `-CompatibilityOnly -SkipBuild` reruns the Windows PowerShell compatibility check while retaining the other operational results. The tested runtime was Windows, Java 17.0.12, Node.js 24.21.0 and PowerShell 7.6.5, with a separate Windows PowerShell 5.1 compatibility run.

External AI provider calls, Kafka broker delivery, real payment rails, production TLS deployment, backup restoration and sustained high-volume operation remain outside this verification. Browser interaction was verified on H2, not Oracle. The architecture still shares a database between services, so a database outage affects the whole stack. Passing the listed cases does not prove that every possible input or production condition is covered.
