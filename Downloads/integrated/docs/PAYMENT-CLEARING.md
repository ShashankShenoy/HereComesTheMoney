# Payments, clearing and RBI simulation

The default simulation uses the agreed policies below. These are demo timings, not an NPCI timetable. The supplied source was preserved in `source-before-payment-changes-with-paths.zip` at the project root before editing; private settings and encryption keys are excluded.

## Outbound approvals

| Rail | Transaction limit | Independent checker required |
| --- | --- | --- |
| UPI | ₹1,00,000 maximum, retained | Never when validation passes |
| IMPS | ₹5,00,000 maximum | Above ₹1,00,000 |
| NEFT | Existing account/product eligibility applies | Above ₹1,00,000 |
| RTGS | ₹2,00,000 minimum; no ₹5,00,000 ceiling | Above ₹10,00,000 |

The boundary amount itself processes automatically. Both outbound creation routes apply the policy. Validation covers account/customer/product eligibility, posting fences, verified beneficiary, registered bank routing, spendable funds and available reserve liquidity after the safety buffer. Independent review repeats validation before making the debit. Rejection happens before dispatch or clearing membership.

In Payments & clearing, **Authorize simulated payment** displays pending payments with Approve/Reject actions. Approve opens the reserve dropdown, defaulting to account 1 when present. Routing requires the reserve mapped to our bank. The originating user cannot approve or reject their own request.

## Cycles and accounting

UPI and IMPS have separate two-hour windows aligned to midnight in Asia/Kolkata. NEFT windows last 30 minutes. RTGS settles individually. The payments worker checks expired windows every 30 seconds, including overdue work after restart.

Accepted non-RTGS payments immediately acquire a clearing batch and Treasury cycle reference. An administrator can close and settle early. Closure commits the membership freeze before settlement is attempted; a failure retains the closed cycle for retry. Subsequent payments enter a replacement window ending at the next scheduled boundary. Approval assigns a pending payment to the currently eligible cycle, not its original request window.

Settlement computes signed net positions for all participating banks. External participant balances, our balanced GL journal, our existing Treasury reserve mirror, instruction references and confirmation commit together. Unique membership and confirmation references, payment locks and a shared simulation writer lock prevent duplicate inclusion and repeated financial posting. Individually settling a managed batch member is rejected. RTGS cannot join managed timed clearing.

Zero-net settlement still records a balanced GL journal and participant confirmation. It releases the associated reserve reservations without inventing a debit or credit in our reserve ledger.

Outgoing customer debits precede interbank settlement. External customer accounts are never created. A completed outgoing payment displays: **Interbank settlement completed; beneficiary credit handled by receiving bank.**

## Separate portal and incoming instructions

Open `/employee/rbi-simulator` on the frontend, or use **Open RBI / NPCI simulation portal** in Payments & clearing. Administrators may simulate incoming instructions and settle cycles. Checkers have inquiry access only. Customers cannot access the portal.

The Moneybags participant resolves to the existing active `MCP-RBI-CURRENT` reserve account. Its balance and reservations are read from `M07_RESERVE_POSITION`, so Treasury and the portal share one underlying position. No balance is copied or reset. HDFC, Axis, SBI and ICICI each start with ₹1 crore in simulator-only reserve balances. They are not `M07_RESERVE_ACCOUNT` assets belonging to Moneybags.

Explicit IFSC-prefix mappings are HDFC → HDFC, UTIB → Axis, SBIN → SBI, ICIC → ICICI and MCPB → Moneybags. The configured internal IFSC is MCPB0000002. Unknown external routing, inconsistent own-reserve mappings and incorrect destination branches/IFSCs are rejected.

Incoming instructions collect the sending bank, our destination account number, branch/IFSC where required, rail, amount, unique external reference and optional sender details. The destination must be eligible before acceptance. These represent incoming instructions already originating at the sending bank; our outbound employee-approval thresholds are not applied to receiving customer credits.

UPI/IMPS credit our customer immediately against payment suspense and settle reserves in the scheduled cycle. NEFT credits our customer after successful batch settlement. RTGS debits the sending bank's reserve, credits ours, then credits our customer in one individual settlement transaction. Every customer credit retains its instruction and journal link. A reserve movement alone never selects a customer. Identical external-reference replays return the original instruction; altered details conflict.

## Employee operations

Inquiries load on entry and retain results and filters by operation. Returning to an inquiry reloads its records. Record selectors replace reference typing where collection endpoints exist, including related-record inquiries. Status fields use schema enums or the corresponding valid-state catalog. Checker decision queues use row actions and derive the record/version from the selected row.

Pending beneficiaries show Account number plus Verified/Not verified buttons. Not verified disables that pending beneficiary. Employees have an Error logs tab containing service, time, safe error metadata and correlation references. Raw request bodies, credentials and stack traces remain outside that tab.

## Installation and verification

Migration `database/021-payment-clearing-simulator.sql` adds simulator records without changing existing customer or reserve balances. It has been applied to shared ALPHA. The H2 schema contains the same additions; the existing generator also discovers migration 021.

Keep `moneybags.payment-simulation.enabled=true` for the demo. `moneybags.payment-simulation.clearing-enabled` defaults to true. The older regression harness explicitly disables clearing to continue testing the pre-existing low-level manual orchestration contracts; the new settlement suite tests the default clearing behavior.

Stop project-managed services before rebuilding executable JARs:

```powershell
./stop-oracle-alpha.ps1
./start-alpha-team.ps1 -BuildOnly -Offline
```

Local tests cover approval boundaries, automatic processing, scheduled and early cycles, replacement cycles after approval, routing rejection, incoming credit timing, insufficient funds/reserves, duplicate references, mixed settlement rejection, zero net and balanced participant movements. JDBC regression tests run real Spring parameter binding with a driver mock that fails if parameter metadata is requested.

Shared ALPHA verification uses `OracleSettlementSmokeTest`, enabled only with `RUN_ALPHA_PAYMENT_TESTS=true` and the existing secure DB environment. It exercises incoming/outgoing UPI, IMPS, NEFT and RTGS, independent checker approval and settlement retries. Its temporary sessions and financial data roll back. `tools/OraclePaymentProbe.java` separately verifies additive installation, reserve isolation, nullable JDBC bindings and timestamp queries against Oracle.

Verification logs are `backend-payment-tests.log`, `frontend-payment-tests.log`, `oracle-settlement-tests.log` and `build-payment-changes.log`. Browser checks cover first-entry beneficiary/evidence tables, returning to recent payments, the approval queue/reserve dialog and the portal. Local browser fixtures are isolated H2 demo data; stopping that stack clears them.
