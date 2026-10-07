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

### Oracle ALPHA verification — 7 October 2026

Migration 011 was already installed in Oracle ALPHA when this verification began. A separate read-only JDBC session confirmed committed configuration and balances and ran [the Oracle acceptance checks](../database/acceptance/credit-cards.sql): six `M11_CC_*` tables, three enabled and valid credit card triggers, three active INR credit card ledger accounts, and zero rows from every invalid-object/accounting exception query.

Configuration completed through the live application's staff screens:

- `admin` proposed **Moneybags Classic / MB_CLASSIC / version 1**, using the terms above. `checker1` independently approved it.
- The existing `BANK_CHECKER` approval role has an INR `CC_LIMIT` ceiling of 200,000 and maximum APR of 24%. No application user was given a new role during this verification.
- `admin` applied on behalf of the existing synthetic MCP customer linked to `MCPALPHA0001`. `checker1` approved INR 25,000, issuing synthetic card ending **5435**. The card was activated and remains active with zero principal/interest and INR 25,000 available credit. Its next statement is **1 November 2026**.

| Live Oracle check | Result |
| --- | --- |
| Simulated purchase | INR 100 posted to the simulated merchant ledger; card balance became INR 100. |
| Freeze and repay | Frozen card spending was rejected. An INR 40 repayment succeeded while frozen. |
| Full refund | The INR 100 refund cleared INR 60 remaining principal and credited INR 40 back to the deposit account. |
| Financial state after verification | Exactly three card entries remain: purchase, repayment and refund. Deposit balance returned to its starting INR 1,050; card principal, interest and receivable are zero. Reconciliation is `MATCHED`. |
| Request replay | Retrying the committed repayment key returned the original entry from Oracle's CLOB response without another debit or entry. Reusing the key with changed amount returned `IDEMPOTENCY_CONFLICT`. |
| Backend rejection checks | Restricted checker product creation, purchases and repayments returned HTTP 403. Maker self-approval, frozen purchases, duplicate refund, over-limit purchase and early statement generation returned HTTP 409. No rejected command added a card entry. |
| Launchers | Oracle startup and loopback frontend/backend health passed on VPN. Startup failure cleanup, actual JVM process tracking and Windows PowerShell basic HTTP parsing were checked. |

The user-supplied `checker` account currently has `BANK_ADMIN`, so it was not used as the restricted checker. `checker1` has `BANK_CHECKER`: it can read and independently approve within authority, but cannot initiate products, purchases or repayments. The officer's existing branch role was inspected, but a full officer workflow was not exercised.

This live workflow used administrator actions on an existing **synthetic** customer. No live Oracle customer login was tested because that fixture's customer password was unavailable; customer ownership flows were tested in H2. Product configuration, the synthetic card and immutable audit/ledger entries persist in Oracle after stopping the app. The completed refund restores the starting deposit balance without deleting the audit trail.

The first scheduled statement was not issued early. Interest/monthly statement cutoff behavior and concurrent overspending passed the local tests; live Oracle concurrency, trigger rejection behavior, later billing and failure injection still need isolated Oracle tests before deployment. Trigger existence, enabled state and compilation validity were checked, not every trigger's enforcement branch. No card-network connection was exercised.

### Saving Oracle changes

Application commands commit their successful transactions automatically. Migration 011 already ends with `COMMIT;`, and Oracle DDL has implicit commits. No extra manual commit is required after a successful migration or UI action. Manual SQL DML requires a commit in that same SQL session unless autocommit is enabled; the acceptance `SELECT` queries do not. See [startup/VPN and commit guidance](../README.md#oracle-startup-and-vpn-recovery).
