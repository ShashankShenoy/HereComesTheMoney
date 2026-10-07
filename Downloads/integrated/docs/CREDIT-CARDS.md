# Credit cards

Credit cards use their own versioned product catalog in **Banking → Credit cards**. The M03 deposit/loan catalog is unchanged. M11 records use the existing IAM, CIF, deposit accounts, M05 ledger and MBX audit trail. The API lives at `/api/v1/credit-cards` and is described in Swagger.

## Terms and workflow

The local demo seeds **Moneybags Classic**: INR 10,000–200,000 credit limits, 24% annual simple interest, billing on day 1, payment due 20 days later, and a minimum payment of 5% of the statement balance or INR 200, capped at the balance. These are illustrative software-test terms, not a regulatory or market-rate recommendation.

**Interest policy:** simple ACT/365 interest on outstanding principal from the purchase date; there is no interest-free purchase grace period and no interest on interest. Interest accrues for elapsed calendar days, is carried at eight decimal places, and is posted in paise using half-even rounding at billing or repayment. Rounding residuals carry forward. No annual, late, cash-advance, tax or over-limit fees are charged. The cardholder sees these terms before accepting an application. New terms require a new product version and independent approval; existing cards keep their accepted version.

1. An administrator proposes terms; a different authorized employee approves them. Retiring a version stops new applications and approvals.
2. An adult individual with active CIF, current verified KYC, and an active self-operated savings/current account accepts the terms and applies. Only one pending application or open card per customer/product code is allowed.
3. A different employee reviews the application and approves a limit within both the request/product range and the employee's `CC_LIMIT` authority. Approval issues an inactive synthetic card.
4. Activate the card, then make simulated purchases. Expiry, freeze/block status, available credit, overdue minimum payments and KYC are checked. Parallel requests lock the card in the database.
5. Generate the next scheduled statement on or after its billing date. Each period has one immutable snapshot. If several periods are overdue, generate each in sequence before making another financial posting. No scheduler silently advances the business date.
6. Repay from the agreed deposit account. Accrued interest is posted first; repayments allocate to interest before principal. Insufficient funds, closed debit fences and overpayment reject the complete transaction. Frozen, blocked and expired cards can still be repaid.
7. Authorized staff may refund a complete simulated purchase once. Refunds reduce outstanding principal; excess returns to the linked deposit account. Interest already earned is not waived. Partial refunds, disputes and chargebacks are outside this workflow.
8. Freeze/unfreeze a card, permanently block it as staff, or close it after all principal and chargeable interest are repaid. Open cards and pending applications prevent closing their linked deposit account or CIF.

The settlement account is a **local simulation liability**, not a connection to a card network or merchant acquirer. Card references begin `SIM-`; the four displayed digits are synthetic. No PAN, CVV, PIN or magnetic-stripe data is created or stored. Physical issuance, external authorization/capture, live settlement, bureau underwriting, rewards, installments and regulatory certification require separate integrations.

## Database installation

The H2 local profile includes the generated fixture automatically. Restarting this profile resets all demo data.

For an existing Oracle installation with migrations 001–010, apply **`database/011-credit-cards.sql` once**, using F5 as the schema owner. It adds six tables, indexes and constraints, immutable product-term/entry/statement triggers, four ledger transaction types, three GL accounts and credit card permissions. It does not change existing financial balances. Oracle DDL commits implicitly; inspect and back up first. The application does not run Oracle migrations at startup. Never use `001-original-oracle.sql` to upgrade an existing database.

New tables:

| Table | Purpose |
| --- | --- |
| `M11_CC_PRODUCT` | Immutable versioned terms and independent decisions |
| `M11_CC_APPLICATION` | Accepted terms, account link and approved limit |
| `M11_CC_CARD` | Lifecycle, locked balance projection and billing cursor |
| `M11_CC_ENTRY` | Immutable purchases, refunds, repayments and interest, each linked to M05 |
| `M11_CC_STATEMENT` | Immutable monthly balances, minimum payment and source-entry cutoff |
| `M11_CC_REQUEST` | Actor-bound command hashes and replay responses |

The migration grants card permissions to the existing bank admin/checker and retail customer roles without assigning users. Sign in again after installing it. Configure an INR `CC_LIMIT` authority with a maximum amount and APR for the designated approval role through IAM. Oracle limits are deliberately not inferred from the local demo. Configure an effective `TRANSFER` / `CUSTOMER_LIABILITY` GL mapping on repayment deposit products. The three `CC_*` GL accounts must remain active in INR with their installed account classes.

## API behavior and controls

- Each POST body carries a `requestKey`. Reuse it only to retry that exact action. Same-key retries return the original result; changed payloads fail with `IDEMPOTENCY_CONFLICT`. A failed command rolls back its request record and every financial write. A different actor has a separate key namespace.
- Lifecycle and approval commands require the current `rowVersion`; reload after a version conflict. Purchases/repayments use database locking and stable request keys.
- `GET /{cardId}` includes principal, posted interest, unbilled interest, payoff amount, available credit, next statement date, overdue minimum, and `BILLING_REQUIRED`. If billing is overdue, the payoff is current only through the next statement cutoff; catch up billing first.
- `GET /{cardId}/transactions?afterEntry=0&limit=50` supports entry-ID pagination (maximum 200). Product catalogs return at most 200 versions; staff/customer directories scan the most recent 500 and return at most 100 authorized records. Statement lists return the latest 120 periods.
- `GET /{cardId}/statements/{statementId}` includes immutable source entries. `REMAINING_MINIMUM_DUE` is a current derived value; the original billed figures do not change.
- `GET /{cardId}/reconciliation` compares card projections, immutable entry deltas and the M05 card receivable postings. M05 journals always balance, and repayments use the existing account spending locks/fences.
- Every command and sensitive card/transaction/statement detail read is audited. Customers are limited to their linked CIFs; staff require relevant permissions and branch/product scope. Checker users may read and make independent approval decisions, not initiate purchases or refunds.

## Verification

Verified locally on 7 October 2026:

- **32 backend tests passed** with Maven `verify`: 12 credit card tests and 20 existing integration tests. Coverage includes immutable product versions, independent decisions and authority ceilings, customer/branch isolation, current KYC and adult eligibility, decimal validation, concurrent overspending, replay/conflict handling, frozen/blocked/expired cards, deposit debit fences, insufficient-funds rollback, excess refund credits, interest math, minimum payments, statement cutoffs and reconciliation.
- **14 frontend tests passed**, plus JavaScript checks and the standalone frontend build. The new asset-serving test verifies that the credit card ES module and stylesheet are available through the actual server allowlist.
- **Browser workflow passed** against the real local H2 backend: customer application → checker approval → customer activation → simulated purchase → repayment → matched ledger reconciliation. Accepted terms remained available. The screen was visually checked at narrow and desktop widths, with no browser warnings/errors in the completed workflow.
- The executable JAR and `frontend/dist` were rebuilt. Temporary browser-test servers were stopped after verification.

Oracle migration execution, Oracle triggers and locking behavior have **not** been verified against a live Oracle instance. Use [the read-only Oracle acceptance checks](../database/acceptance/credit-cards.sql) after installing migration 011, and exercise the concurrency/rollback flows in an isolated Oracle test schema before deployment. No real Oracle database was modified during this implementation.
