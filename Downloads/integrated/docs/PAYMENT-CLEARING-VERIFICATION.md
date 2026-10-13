# Payment clearing verification — 13 October 2026

## Results

| Verification | Result |
| --- | --- |
| Local backend regression tests | 149 passed; 2 opt-in Oracle tests skipped in the local run |
| Frontend tests | 49 passed |
| Gateway and architecture checks | 23 passed |
| Shared ALPHA payment workflow tests | 2 passed, 0 failures, 0 skips |
| Executable services and frontend bundle | Verified build recorded; source/artifact fingerprint checked |
| Original supplied source | 528 original files preserved with folder paths in the root snapshot ZIP |

The ALPHA tests exercise real Oracle-backed incoming/outgoing UPI, IMPS, NEFT and RTGS, delayed NEFT customer credit, immediate incoming UPI/IMPS credit, independent checker approval, duplicate incoming references and repeated settlement. Test instructions, financial postings, reserve changes and temporary IAM sessions roll back. The additive simulator migration and four external-bank opening balances remain installed as requested. Existing Moneybags reserve/customer balances were not reset.

Local coverage adds boundary tests at ₹1,00,000 and ₹10,00,000, IMPS maximum/RTGS minimum, manual replacement windows, scheduled settlement, rejection without clearing membership, unknown/inconsistent routing, missing destination accounts, insufficient customer/external reserve funds, zero-net clearing and rejection of individual settlement for a managed batch payment. The unknown-routing rollback test crosses an actual HTTP request transaction boundary.

The JDBC tests exercise real Spring statement binding and fail immediately on any attempt to ask the driver for parameter metadata. Nullable payment-update/status-history and dispatch-insert/update parameters use explicit JDBC types.

Browser verification used the isolated local H2 stack and synthetic fixture users. It checked first-click beneficiary/evidence loading, payment records when returning from evidence, pending-payment Approve/Reject buttons, the reserve dropdown default, beneficiary Verified/Not verified buttons, the dedicated portal entry URL, checker inquiry access and reserve/cycle/payment/history tables.

The backend was stopped before every executable-JAR rebuild. No repository history was available in this downloaded project, so the supplied source snapshot is the preservation baseline. Production rail connectivity and external customer credit remain outside the simulator.

## Evidence

- `build-payment-changes.log`: final backend/frontend/gateway checks and packaging.
- `oracle-settlement-tests.log`: opt-in ALPHA workflow verification.
- `payment-unit-tests.log`: approval policy and JDBC binding regression tests.
- `settlement-integration-tests.log`: targeted settlement suite during development; use the final build log for final local results.
- `.runtime/verified-build.json`: source and executable artifact fingerprints.
- `source-before-payment-changes-with-paths.zip` at the project root: original source snapshot, excluding private settings, keys and generated runtime/build outputs.

See [the design and operation guide](PAYMENT-CLEARING.md) for the agreed policies and portal usage.
