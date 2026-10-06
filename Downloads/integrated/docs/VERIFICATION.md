# Verification record

Date: 2026-10-05. Host: Windows, Java 17, Node 24. Test profile: isolated H2 in Oracle mode. Original Oracle connection credentials were not used.

## Executed automated checks

`IntegratedContextTest`: **14 tests, 0 failures, 0 errors, 0 skipped**. The tests exercise the assembled Spring context through MockMvc, actual security filters, JDBC repositories and domain services. They are not mocked controller-only unit tests.

| Test | Evidence |
| --- | --- |
| Unified routes/authentication | Health UP, anonymous 401, combined OpenAPI, CORS headers |
| Hash + account ownership | Missing/valid hash, wrong customer denied, transaction ID-only redaction |
| Transfers | Balanced posting, identical replay, conflicting replay, unauthorized source, insufficient funds |
| Account activation | Opening eligibility and synchronous M04/M05 posting fence |
| Teller | Deposit once, replay once, reject self-close, independent counted-cash close |
| FX | Reject own rate approval, accept distinct checker |
| Hash issuance | 43-character one-time key, 32-byte persisted digest, absent from subsequent user read |
| Statements | Missing hash denied, request/process/snapshot, exact ledger closing balance, download denied without hash, PDF bytes |
| Payments | Verified beneficiary, customer debit, simulated acceptance/settlement, reserve reduction, replay without second settlement |
| Loans | Application to independent sanction/document verification/disbursement; 12 installment schedule; due accrual, repayment and replay; correct principal projection |
| Overrides | Exact command approval, reject self-approval, reject changed rate, consume once on apply |
| Freeze | Debit blocked after control acknowledgement and enabled after release |
| Fees | Product-bound assessment, key conflict, unauthorized collection denied, balanced collection/replay |
| Branch isolation | Different-branch employee cannot open account, transfer or request reversal |

The loan servicing test ages one synthetic schedule item within the test database to exercise a due installment. It does not change the production calendar or add a backdating API.

Generated fixture: **175 tables, 206 foreign keys**, original transition matrices and portable check constraints. Oracle PL/SQL triggers, function-based indexes and Oracle-specific JSON checks are not emulated.

Backend compilation/tests passed. Packaging initially encountered Windows' lock on the running JAR; the verified demo processes were stopped, and the identical tested backend source then packaged successfully. Output: `backend/target/moneybags-integrated-1.0.0.jar`.

Frontend JavaScript syntax checks and standalone build passed. Output: `frontend/dist`. The runtime OpenAPI catalog exposes **287 operations across 245 paths**; see API-CATALOG.md.

## Browser and document review

The actual local Oracle JET UI was exercised through the browser: sign-in, router navigation, bank overview, account directory, transaction identifier-only and hash-unlocked views, transfer fields, loan workspace, optional nested override fields, clearing workflows, privacy purpose list and `If-Match` approval input. No real-world financial action was performed.

Browser review found and fixed the initial CoreRouter state callback, missing clearing-route prefixes and repeated enhancement of native inputs inside already-created JET components. No new console errors appeared after the final reload; the tab's historical log retains the pre-fix router error.

The backend-generated statement PDF was rendered with Poppler and visually inspected. Text, columns, balances and pagination were legible without clipping or overlap. The test PDF and render are local QA files, not customer artifacts. Unicode narration is supported in HTML/CSV; non-Latin PDF is explicitly rejected until fonts are integrated.

The local demo restarted successfully, and health returned UP. Both listeners bind to loopback. The browser is left on the banking dashboard with synthetic data. Its screenshot is `docs/dashboard.jpg`.

## Not verified

- Real Oracle execution, migration application, trigger behavior, concurrent locking and load.
- Live Kafka publication/consumption, security/ACLs and crash recovery.
- Live FX provider availability, credentials/certificates and circuit behavior against a network service.
- Every original module endpoint, every rail outcome, net settlement cycle, all product policy combinations or all privacy workflows.
- Regulatory/legal compliance, penetration testing, high availability, backup/restore and production operations.

These are explicit acceptance requirements in OPERATIONS.md and FEATURE-COVERAGE.md, not tests implicitly passed by the local demonstration.
