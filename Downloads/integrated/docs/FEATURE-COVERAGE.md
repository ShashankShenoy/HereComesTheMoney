# Requested feature coverage

Every requested domain has a menu entry and API-backed screens. “Integrated” means an implementation is connected in this codebase; only the scenarios in VERIFICATION.md have been executed end to end. “Boundary” means a restricted implementation, a retained interface/placeholder, or work requiring institution-specific validation. These distinctions matter before treating this as a core banking production release.

## IAM

| Requested feature | Implementation / boundary |
| --- | --- |
| Login, passwords, tokens, sessions | Shared M01 login, BCrypt credentials, opaque access/refresh sessions, expiry/revocation, lockout and MFA workflows; original IAM UI retained. |
| Users, roles, permissions | User creation, scoped role assignments, access requests and independent approvals; backend policy is authoritative. |
| Menus | Permission-filtered unified navigation, original IAM menu metadata and OJET CoreRouter. |
| Customer hash at creation | Both IAM user creation and `/customer-access/users` return a one-time random hash for a customer. Digest storage, rotation, ownership and hash-gated financial reads are implemented and tested. |

## CIF and KYC

| Requested feature | Implementation / boundary |
| --- | --- |
| Customer information, addresses, contacts | Supplied M02 repositories/services and customer screens connected to shared IAM. |
| PAN and identification | Identifier workflows retained with masking; no live government identity verification. |
| KYC documents and verification | Upload/reference and review workflow retained; account and loan eligibility read verified CIF state. Production document storage, malware scanning and retention enforcement need deployment integration. |

## Product Master

| Requested feature | Implementation / boundary |
| --- | --- |
| Code, name, type, currency, availability dates | Versioned M03 product/availability workflows; branch and customer-segment context passed during opening. Deposit posting remains INR. |
| Interest, fee, penalty, limit, eligibility rules | Rule-definition screens and validation retained. Fixed deposit fees and fixed monthly loan interest are executed; generalized deposit accrual, tax, penalty and complex pricing engines are not implemented. |
| Minimum balance, transaction restrictions | Opening-funding minimum, transfer policy, posting fences and available-funds checks are integrated. Maintenance minimum-balance charging and every policy family across every channel require additional engines. |
| Draft, approved, active, suspended, retired | Product lifecycle and effective-version resolution retained. |
| Maker-checker approval | Version-content hash and independent product approval retained. |
| Segment and branch availability | Effective branch/segment product resolution integrated. |
| Authorized pricing overrides | Account limit/interest proposal, independent policy/authority approval and exact-command application implemented. |
| Existing-account treatment on product change | Version adoption checks approved treatment and consent requirement; existing overrides must be resolved before adoption. No automatic bulk migration job. |

## Account Management

| Requested feature | Implementation / boundary |
| --- | --- |
| Unique account number, CIF/product linkage, branch/currency | M04 number generation, foreign keys, active/verified CIF and effective product checks. Savings/current INR accounts are openable. |
| Single, joint, nominee, signatory | Party and nominee workflows retained. Joint/guardian payment mandates without a full authorization workflow fail closed rather than treating one customer as sufficient. |
| Available, ledger, blocked, lien balances | M05 positions are authoritative; account views reflect ledger/hold/control state and require customer hash for amounts. |
| Status and history | Lifecycle and immutable event/history workflows retained. |
| Freeze, partial freeze, debit/credit block, unfreeze | Synchronous account-to-ledger control acknowledgement; debit block/release is tested. |
| Dormancy and reactivation | Supplied administrative/event workflows retained; no institution-specific dormancy scheduler configured. |
| Limits and overrides | Product and account transfer limits; payload-bound approved interest/limit exceptions. Supported transfer periods are transaction/day. |
| Closure validation and approval | Zero financial exposure, closed posting fence, no open payment/loan obligation and independent decision checks. |
| Privileged reason and audit | Domain reason capture plus module audit/outbox and MBX audit events. |

## Transactions

| Requested feature | Implementation / boundary |
| --- | --- |
| Cash deposit/withdrawal | Teller till plus balanced cash/customer journals; own till, business date, cash availability and independent close controls. Tested deposit/replay/close path. |
| Internal transfer, debit/credit checks, double entry | Connected M04/product/fence/funds validation and M05 ledger; balancing and insufficient funds tested. |
| Holds/reservations | Payment-linked ledger holds and treasury liquidity holds; terminal/evidence rules guard release. |
| Idempotency/duplicates | Canonical request keys/hashes, journal posting keys and database uniqueness. Tested across transfers, cash, fees, payments and loans. |
| Limits and velocity | Transfer daily amount/count and account overrides enforced. A universal cross-channel velocity/risk service is not implemented. |
| Fees | Effective untaxed fixed product fee assessment and balanced collection are tested. Percentage/tax/loan-fee allocation adapters fail explicitly. |
| Reversal/correction/adjustment | Independent transfer reversals and trusted service journals; immutable entries retained. No arbitrary employee journal editor. |
| Value date, booking date, channel | Persisted in M05; immediate transfers require the current business date. |
| Real-time balances | Transactional M05 position updates plus reconciliation views. |
| Teller cash control | Per-teller/day till, no negative cash and counted-cash independent closure. Vault/denomination management is not modeled. |
| Status/failure tracking | Transaction history, explicit errors/correlation IDs and module operational queues. Rejected rolled-back attempts are not represented as successful postings. |
| Kafka brokerage | Optional committed-outbox publisher for M05–M08, leases, retry and dead-letter states. Broker delivery is untested; core posting is synchronous and atomic, not a Kafka command processor. |

## Payments, clearing and settlement

| Requested feature | Implementation / boundary |
| --- | --- |
| Beneficiaries | Customer ownership, pending verification and independent activation; account tokens omitted from directory results. No live beneficiary-name verification. |
| Rail choice and limits | UPI/IMPS/NEFT/RTGS codes and explicit simulator bounds. These are illustrative application policies, not an assertion of current network limits. |
| Initiation/authorization | Own account/beneficiary validation and separate simulation checker. |
| ISO 20022-style canonical model | Supplied canonical instruction, references and state/evidence records retained; no ISO message certification or external signing adapter. |
| Async status/duplicate messages | Durable dispatch and rail evidence plus outboxes/inboxes. Simulator outcomes are operator-driven; no live rail consumer connected. |
| Timeout/retry/return/reject/reversal | Original dispatch/exception APIs and simulator UNKNOWN/REJECTED/REFUNDED paths; settled-payment return processing needs network-specific adapters. |
| NEFT-style clearing batches | Batch/item/close/reconciliation APIs and screens retained. Automated batch scheduling and net-cycle posting are not part of the tested simulation. |
| Real-time simulations | Explicit authorize → dispatch → outcome commands link customer debit, suspense and reserve. UPI settlement/replay tested; other rail-specific scenarios need acceptance tests. |
| Settlement/suspense, reconciliation, exceptions | Original M06/M07 workflows retained; tested gross settlement clears suspense and reduces reserve once. Whole-bank queues require global authority. |

## Treasury and reserve ledger

| Requested feature | Implementation / boundary |
| --- | --- |
| RBI/E-Kuber account representation | Simulated M07 reserve account linked to a real GL asset; no real RBI connection. |
| Opening/available/projected/closing liquidity | Reserve positions, holds, settlement cycles and work-item screens. Demo opening reserve is a balanced synthetic funding journal. |
| Rail incoming/outgoing, intraday/EOD cycles | Supplied direction/rail/cycle models and endpoints retained; institutional EOD scheduler is not configured. |
| Prefunding, limits, net/gross positions | Original liquidity and cycle validation retained. Funding adapters and full liquidity stress/concurrency scenarios require Oracle acceptance. |
| Journals/insufficient liquidity | Ledger-backed simulation and controlled liquidity hold/exception flows. |
| Clearing reconciliation/reports/dashboard | Reserve/cycle/exception/work-item workspaces and position/reconciliation APIs. A regulatory settlement-report catalog is not supplied. |

## Loans

| Requested feature | Implementation / boundary |
| --- | --- |
| Application/documents, eligibility | CIF/product validation, application and document capture, submission and independent document verification. |
| Income/obligations/collateral/affordability | Supplied assessment and collateral records retained; financial underwriting is an officer workflow, not an automated credit decision. |
| Credit scoring integration placeholder | Assessment placeholder retained; no bureau credentials or live scoring. |
| Multi-stage officer workflow | Create → submit → assess → decide → offer → accept → document verify → convert. |
| Sanction/reject/refer/reasons | Supplied decision workflow and reason codes retained. |
| Maker-checker/approval limits | IAM amount/rate authority and distinct sanction/disbursement checker; tested. |
| Acceptance/document completion | Customer ownership and signed-offer verification before conversion; tested. |
| Loan facility/disbursement | M08 facility and balanced principal receivable/customer deposit journals; tested with idempotency. |
| Schedule and interest accrual | Fixed-rate, monthly equal-principal reducing-balance schedules; ACT/365 or 30/360 conventions. Due installment accrual and repayment are tested. Floating resets, daily accrual, tranches, moratoria and prepayment/rescheduling are not implemented. |
| Delinquency/collections placeholders | Overdue installment queue; full collection strategy, notices, impairment/provisioning and recoveries need separate workflows. |

## Credit cards

| Feature | Implementation / boundary |
| --- | --- |
| Versioned products | Dedicated M11 catalog, immutable agreed terms, independent approval and withdrawal from sale. |
| Applications and issuance | Adult/current-KYC eligibility, customer-owned repayment account, accepted terms, independent credit-limit authority, synthetic card references. |
| Controls and spending | Activation, freeze/unfreeze, permanent staff block, expiry checks, overdue minimum-payment check, locked available-credit enforcement. |
| Purchases and refunds | Immediate simulated merchant posting; full refund once, with excess credited to the linked deposit account. Balanced M05 journals. |
| Repayments | Atomic deposit debit; existing funds/fence checks; interest-first allocation; replay protection and overpayment rejection. |
| Interest and billing | Disclosed simple ACT/365 policy without purchase grace or compounding; monthly immutable statements and source cutoffs; explicit catch-up billing. |
| Verification | 12 credit card backend tests, existing regression suite, frontend asset test and the H2 browser workflow passed. Oracle ALPHA staff configuration, independent approvals, purchase/freeze/repayment/refund/unfreeze, replay and reconciliation passed; schema/trigger validity and acceptance exception queries passed. See the verification record for scope. |
| Boundaries | No live card network, physical issuance, PAN/CVV/PIN, cash advances, partial refunds, disputes, rewards or automated bureau underwriting. Live Oracle concurrency, trigger enforcement branches, future monthly billing and failure injection remain deployment checks. |

See [credit card setup and policy](CREDIT-CARDS.md).

## Statements and reporting

| Requested feature | Implementation / boundary |
| --- | --- |
| Date range/chronology/balances | M10 immutable source cut and ledger lines; opening/running/closing balances reconcile with M05, including reversal originals and provisional payment postings. |
| PDF/structured downloads | Deterministic paginated PDF, Unicode CSV and HTML, audited on download. PDF visually inspected in local testing. |
| Multi-language descriptions | Locale/narration catalog retained; HTML/CSV support Unicode. Built-in PDF uses a standard Latin font and rejects non-Latin narration rather than corrupting it. Embedded multilingual fonts remain to be added. |
| Masking | Approved audience/channel mask profiles and customer-hash gate; no-secret response headers. |
| Interest/fee summaries | Included posted lines and classifications; no separate customer-facing totals panel beyond the immutable statement model. |
| Historical regeneration | Immutable cut, snapshot revisions and additive revision-intent table. Historical catalog/mask retention remains an operational requirement. |
| Secure delivery/download audit | Authorized, hash-gated downloads implemented. External email/SMS/storage delivery is disabled until a secure adapter is configured. |
| Customer/employee access | Shared sessions and account authorization; customer and employee statement workflows. Joint-party reporting needs broader mandate acceptance tests. |

## Privacy, consent, audit and compliance

| Requested feature | Implementation / boundary |
| --- | --- |
| Purpose/consent registry | Supplied M09 workflows, optimistic versions and approvals; shared IAM principal resolver. |
| Minimization/classification | Policy registry and masked financial/customer displays. A universal policy-enforcement hook for every original read/write is not implemented. |
| Retention/legal holds | Registry, hold lifecycle and case workflows retained. No automatic production erasure job has been enabled. |
| Data access/correction | Casework, identity verification and evidence-export workflows retained; execution by each source domain must be institutionally validated. |
| Anonymization/pseudonymization | Policy/workflow metadata retained. Actual irreversible erasure/pseudonymization across all domains is not implemented. |
| Immutable audit/privileged monitoring | Original Oracle audit controls plus append-only MBX trigger; purpose-bound financial access events. SIEM export/alert rules require deployment configuration. |
| Segregation of duties | IAM, product, loan, FX, till, override and other module approvals enforce distinct identities. No formal full-system SoD certification. |
| Evidence export | Supplied approved export workflow and download/reference endpoints retained; protected storage/retention must be configured. |
| UI/log/nonproduction masking | Hash-gated transaction details, secret omission and safe public errors; local fixture contains synthetic data only. No anonymized production-data import is provided. |
| Indian data protection/RBI matrix | **Not validated.** A separate legal/compliance and control-evidence matrix is required. No regulatory-compliance claim is made. |

## Currency and external integration

| Requested feature | Implementation / boundary |
| --- | --- |
| Currency master | Supported-currency directory; core account ledger remains INR under the supplied schema. |
| Provider adapter | Configurable HTTPS JSON contract, bounded response, timeout and validation; external network test pending. |
| Source/timestamp/validity, buy/sell/mid | Stored and validated; latest approved quotes expose explicit staleness. |
| Fallback/circuit/retry | Cached approved records, expiry rejection, two attempts, circuit opening after repeated failures. |
| Credentials/certificates | Token from environment; normal JVM truststore and certificate validation. HSM, dynamic credential rotation and mutual TLS are deployment work. |
| Request/response audit | Outcome/reference audit without credentials or raw provider body. |
| Manual authorized override | Rate proposal plus distinct checker; tested. |
| Ledger reconciliation | FX quotes cannot directly change balances. Actual cross-currency execution and resulting ledger reconciliation are **not implemented**, because the supplied posting model enforces INR. |
