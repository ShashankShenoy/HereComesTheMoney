# Integrated API catalog

Generated from the running assembled backend on 2026-10-05. All API paths below are relative to the same backend origin. Use `/v3/api-docs` for schemas and required headers. Service-only endpoints remain restricted even when listed here.

| Method | Path | Workflow |
| --- | --- | --- |
| GET | `/api/v1/accounts` | List accounts for a CIF |
| POST | `/api/v1/accounts` | Open a pending savings or current account |
| GET | `/api/v1/accounts/by-number/{number}` | Find an account by account number |
| POST | `/api/v1/accounts/{accountId}/controls` | Apply Module 4 control |
| GET | `/api/v1/accounts/{accountId}/position` | Get account position |
| GET | `/api/v1/accounts/{id}` | Get account details |
| POST | `/api/v1/accounts/{id}/activate` | Request activation and posting-fence opening |
| POST | `/api/v1/accounts/{id}/cancel-opening` | Cancel a pending opening after funding reversal |
| POST | `/api/v1/accounts/{id}/closures` | Request account closure |
| POST | `/api/v1/accounts/{id}/closures/{requestId}/approve` | Approve a cleared closure request |
| POST | `/api/v1/accounts/{id}/closures/{requestId}/reject` | Reject a closure request |
| POST | `/api/v1/accounts/{id}/interest-overrides` | Create a version-bound interest override |
| POST | `/api/v1/accounts/{id}/limits` | Create an account limit |
| POST | `/api/v1/accounts/{id}/majority-reviews` | Start majority review |
| POST | `/api/v1/accounts/{id}/majority-reviews/decisions` | Complete or escalate majority review |
| PUT | `/api/v1/accounts/{id}/nominees` | Replace active nominees, with shares totaling 100% |
| GET | `/api/v1/accounts/{id}/override-approvals` | Review account override proposals |
| POST | `/api/v1/accounts/{id}/override-approvals` | Propose an account limit or interest override |
| POST | `/api/v1/accounts/{id}/override-approvals/{request}/decision` | Independently decide an account override |
| POST | `/api/v1/accounts/{id}/parties` | Add a non-primary account party |
| DELETE | `/api/v1/accounts/{id}/parties/{partyId}` | End a non-primary account party role |
| POST | `/api/v1/accounts/{id}/product-version-adoptions` | Adopt a product version |
| POST | `/api/v1/accounts/{id}/restrictions` | Request a restriction; effective only after Module 5 acknowledgement |
| POST | `/api/v1/accounts/{id}/restrictions/{restrictionId}/release` | Request release of an active restriction |
| GET | `/api/v1/accounts/{id}/{collection}` | List parties, nominees, restrictions, limits, history, or workflow records |
| GET | `/api/v1/approvals` | List approval tasks |
| POST | `/api/v1/approvals` | Request maker-checker approval |
| POST | `/api/v1/approvals/{id}/decision` | Decide approval |
| POST | `/api/v1/auth/login` | login |
| POST | `/api/v1/auth/logout` | logout |
| GET | `/api/v1/auth/me` | me |
| POST | `/api/v1/auth/refresh` | refresh_1 |
| GET | `/api/v1/auth/sessions` | sessions |
| DELETE | `/api/v1/auth/sessions/{sessionId}` | revoke |
| GET | `/api/v1/banking/accounts` | accounts_1 |
| GET | `/api/v1/banking/audit` | audit_3 |
| GET | `/api/v1/banking/facilities` | facilities |
| GET | `/api/v1/banking/gl-accounts` | gl |
| GET | `/api/v1/banking/overview` | overview |
| GET | `/api/v1/banking/transactions` | transactions |
| GET | `/api/v1/beneficiaries` | beneficiaries |
| POST | `/api/v1/beneficiaries` | beneficiary |
| GET | `/api/v1/beneficiaries/pending` | pending |
| POST | `/api/v1/beneficiaries/{id}/verify` | verify_2 |
| GET | `/api/v1/catalog/masking-profiles` | List masking profiles |
| POST | `/api/v1/catalog/masking-profiles` | Create masking draft |
| POST | `/api/v1/catalog/masking-profiles/{id}/approve` | Approve masking version |
| POST | `/api/v1/catalog/masking-profiles/{id}/retire` | Retire masking version |
| GET | `/api/v1/catalog/narrations` | List narration versions |
| POST | `/api/v1/catalog/narrations` | Create narration draft |
| POST | `/api/v1/catalog/narrations/{id}/approve` | Approve narration |
| POST | `/api/v1/catalog/narrations/{id}/retire` | Retire narration |
| GET | `/api/v1/cif/cases/{id}` | caseDetail |
| POST | `/api/v1/cif/cases/{id}/assign` | assign |
| POST | `/api/v1/cif/cases/{id}/documents` | upload |
| POST | `/api/v1/cif/cases/{id}/review` | review |
| POST | `/api/v1/cif/cases/{id}/submit` | submit_3 |
| GET | `/api/v1/cif/customers` | list_6 |
| POST | `/api/v1/cif/customers` | create_9 |
| GET | `/api/v1/cif/customers/{id}` | detail_1 |
| POST | `/api/v1/cif/customers/{id}/addresses` | address |
| GET | `/api/v1/cif/customers/{id}/audit` | audit_2 |
| POST | `/api/v1/cif/customers/{id}/cases` | open_1 |
| POST | `/api/v1/cif/customers/{id}/consents` | consent |
| DELETE | `/api/v1/cif/customers/{id}/consents/{consent}` | withdraw_2 |
| POST | `/api/v1/cif/customers/{id}/contacts` | contact |
| GET | `/api/v1/cif/customers/{id}/eligibility-facts` | facts |
| POST | `/api/v1/cif/customers/{id}/identifiers` | identifier |
| PUT | `/api/v1/cif/customers/{id}/profile` | profile |
| POST | `/api/v1/cif/customers/{id}/relationships` | relation |
| DELETE | `/api/v1/cif/customers/{id}/relationships/{relation}` | end |
| PATCH | `/api/v1/cif/customers/{id}/status` | status_1 |
| GET | `/api/v1/cif/documents/{id}/content` | download_1 |
| POST | `/api/v1/cif/documents/{id}/review` | verify_1 |
| POST | `/api/v1/cif/reviews/expire-due` | expire |
| GET | `/api/v1/clearing-batches` | List clearing batches |
| POST | `/api/v1/clearing-batches` | Open clearing batch |
| GET | `/api/v1/clearing-batches/{id}` | Get clearing batch |
| POST | `/api/v1/clearing-batches/{id}/close` | Close clearing batch |
| GET | `/api/v1/clearing-batches/{id}/items` | List clearing items |
| POST | `/api/v1/clearing-batches/{id}/items` | Add clearing item |
| POST | `/api/v1/clearing-batches/{id}/settlement` | Link Module 7 settlement cycle |
| GET | `/api/v1/currencies` | currencies |
| POST | `/api/v1/customer-access/rotate` | rotate |
| POST | `/api/v1/customer-access/users` | create_8 |
| GET | `/api/v1/dispatches` | List rail adapter work |
| GET | `/api/v1/dispatches/{id}/attempts` | List dispatch attempts |
| POST | `/api/v1/dispatches/{id}/attempts` | Record dispatch attempt |
| GET | `/api/v1/docs/openapi` | openapi |
| GET | `/api/v1/fees` | List assessed account fees |
| POST | `/api/v1/fees` | Assess a fee |
| POST | `/api/v1/fees/{id}/collect` | Collect an assessed fee into the ledger |
| POST | `/api/v1/fx/provider/refresh` | refresh |
| GET | `/api/v1/fx/provider/status` | status_2 |
| GET | `/api/v1/fx/quote` | quote |
| GET | `/api/v1/fx/rates` | rates |
| POST | `/api/v1/fx/rates` | rate |
| POST | `/api/v1/fx/rates/{id}/decision` | decideRate |
| POST | `/api/v1/gl/accounts` | Create GL account |
| POST | `/api/v1/gl/mappings` | Create product GL mapping |
| POST | `/api/v1/holds` | Place a payment hold |
| POST | `/api/v1/holds/{holdId}/release` | Release a confirmed payment hold |
| GET | `/api/v1/iam/access-requests` | requests_1 |
| POST | `/api/v1/iam/access-requests` | request_2 |
| GET | `/api/v1/iam/access-requests/{id}` | requestDetail |
| POST | `/api/v1/iam/access-requests/{id}/decision` | decision_1 |
| GET | `/api/v1/iam/audit` | audit_1 |
| POST | `/api/v1/iam/authorization/check` | check |
| GET | `/api/v1/iam/dashboard` | dashboard |
| GET | `/api/v1/iam/factors` | factors |
| POST | `/api/v1/iam/factors/totp` | enroll |
| POST | `/api/v1/iam/factors/{id}/confirm` | confirm_1 |
| POST | `/api/v1/iam/factors/{id}/revoke` | revokeFactor |
| GET | `/api/v1/iam/menus` | menus |
| POST | `/api/v1/iam/menus` | menu |
| PUT | `/api/v1/iam/menus/{id}` | menuUpdate |
| GET | `/api/v1/iam/outbox` | outbox_1 |
| POST | `/api/v1/iam/password` | password |
| GET | `/api/v1/iam/permissions` | permissions |
| POST | `/api/v1/iam/permissions` | permission |
| GET | `/api/v1/iam/roles` | roles |
| POST | `/api/v1/iam/roles` | role |
| GET | `/api/v1/iam/roles/{id}` | roleDetail |
| PUT | `/api/v1/iam/roles/{id}` | updateRole |
| POST | `/api/v1/iam/roles/{id}/authorities` | authority |
| DELETE | `/api/v1/iam/roles/{id}/authorities/{authority}` | expire_1 |
| POST | `/api/v1/iam/setup/checker` | checker |
| POST | `/api/v1/iam/step-up` | stepUp |
| GET | `/api/v1/iam/users` | users |
| POST | `/api/v1/iam/users` | create_7 |
| GET | `/api/v1/iam/users/{id}` | user |
| POST | `/api/v1/iam/users/{id}/customer-links` | link |
| DELETE | `/api/v1/iam/users/{id}/customer-links/{link}` | unlink |
| POST | `/api/v1/iam/users/{id}/factor-reset` | resetFactors |
| POST | `/api/v1/iam/users/{id}/password-reset` | reset |
| DELETE | `/api/v1/iam/users/{id}/sessions/{session}` | session |
| PATCH | `/api/v1/iam/users/{id}/status` | status |
| GET | `/api/v1/internal/accounts/{id}/context` | Read account context for a peer module |
| GET | `/api/v1/internal/accounts/{id}/parties` | Read current account parties for a peer module |
| POST | `/api/v1/internal/events/control-acks` | Consume a Module 5 control acknowledgement |
| POST | `/api/v1/internal/events/financial-projections` | Consume an ordered Module 5 financial event |
| POST | `/api/v1/internal/privacy/domain-events` | Consume a source audit event through the Module 9 inbox |
| POST | `/api/v1/journals` | Post balanced journal |
| GET | `/api/v1/journals/{journalId}/control` | Get journal balance control |
| GET | `/api/v1/loans/applications` | applications |
| POST | `/api/v1/loans/applications` | create_6 |
| GET | `/api/v1/loans/applications/{id}` | application |
| GET | `/api/v1/loans/applications/{id}/assessments` | assessments |
| POST | `/api/v1/loans/applications/{id}/assessments` | assess |
| POST | `/api/v1/loans/applications/{id}/convert` | convert |
| GET | `/api/v1/loans/applications/{id}/decisions` | decisions |
| POST | `/api/v1/loans/applications/{id}/decisions` | decide_2 |
| GET | `/api/v1/loans/applications/{id}/documents` | documents |
| POST | `/api/v1/loans/applications/{id}/documents` | addDocument |
| POST | `/api/v1/loans/applications/{id}/documents/{documentId}/verify` | verifyDocument |
| GET | `/api/v1/loans/applications/{id}/offers` | offers |
| POST | `/api/v1/loans/applications/{id}/offers` | offer |
| POST | `/api/v1/loans/applications/{id}/submit` | submit_2 |
| GET | `/api/v1/loans/collections` | collections |
| POST | `/api/v1/loans/disbursements/{id}/approve` | approve_2 |
| GET | `/api/v1/loans/facilities/{id}` | facility |
| POST | `/api/v1/loans/facilities/{id}/accruals` | accrue |
| GET | `/api/v1/loans/facilities/{id}/disbursements` | disbursements |
| POST | `/api/v1/loans/facilities/{id}/disbursements` | request_1 |
| POST | `/api/v1/loans/facilities/{id}/repayments` | repay |
| GET | `/api/v1/loans/facilities/{id}/schedule` | schedule |
| POST | `/api/v1/loans/offers/{id}/accept` | accept |
| GET | `/api/v1/payments` | List recent payments |
| POST | `/api/v1/payments` | Create payment instruction |
| POST | `/api/v1/payments/initiate` | initiate |
| GET | `/api/v1/payments/{id}` | Get payment |
| POST | `/api/v1/payments/{id}/authorize-simulation` | authorize |
| POST | `/api/v1/payments/{id}/dispatch` | Prepare outbound rail dispatch |
| GET | `/api/v1/payments/{id}/history` | Get payment status history |
| GET | `/api/v1/payments/{id}/rail-evidence` | List rail evidence |
| POST | `/api/v1/payments/{id}/rail-evidence` | Record rail status evidence |
| GET | `/api/v1/payments/{id}/reconciliation` | Get current reconciliation |
| GET | `/api/v1/payments/{id}/reconciliation-runs` | List reconciliation runs |
| POST | `/api/v1/payments/{id}/reconciliations` | Run payment reconciliation |
| POST | `/api/v1/payments/{id}/simulate-outcome` | outcome_1 |
| POST | `/api/v1/payments/{id}/transitions` | Advance payment state |
| POST | `/api/v1/period-closes` | Request period close |
| POST | `/api/v1/period-closes/{id}/decision` | Decide period close |
| GET | `/api/v1/privacy/audit-events` | Search audit events by case, resource, or actor |
| POST | `/api/v1/privacy/audit-events` | Ingest a minimized immutable audit event |
| GET | `/api/v1/privacy/cases` | List compliance cases |
| POST | `/api/v1/privacy/cases` | Open and route a compliance case |
| GET | `/api/v1/privacy/cases/{caseId}/tasks/{taskId}` | Get a task assigned to the authenticated owning service |
| POST | `/api/v1/privacy/cases/{caseId}/tasks/{taskId}/completion` | Complete a routed service task |
| GET | `/api/v1/privacy/cases/{id}` | Get a case and its routed tasks |
| POST | `/api/v1/privacy/cases/{id}/closure` | Close a completed compliance case |
| POST | `/api/v1/privacy/cases/{id}/identity-verification` | Record identity verification |
| POST | `/api/v1/privacy/consents/decisions` | Capture a consent grant or denial |
| GET | `/api/v1/privacy/consents/evaluations` | Evaluate current consent |
| GET | `/api/v1/privacy/consents/history` | Get consent history |
| POST | `/api/v1/privacy/consents/withdrawals` | Withdraw optional consent |
| POST | `/api/v1/privacy/evidence-exports` | Request a privacy evidence export |
| GET | `/api/v1/privacy/evidence-exports/{id}` | Get evidence export status and hashes |
| POST | `/api/v1/privacy/evidence-exports/{id}/approval` | Approve an evidence export |
| POST | `/api/v1/privacy/evidence-exports/{id}/completion` | Record an encrypted evidence package from the vault |
| GET | `/api/v1/privacy/holds` | List legal holds |
| POST | `/api/v1/privacy/holds` | Request a legal hold |
| POST | `/api/v1/privacy/holds/evaluations` | Check whether a record is preserved before disposition |
| GET | `/api/v1/privacy/holds/{id}` | Get a scoped legal hold |
| POST | `/api/v1/privacy/holds/{id}/activation` | Approve and activate a legal hold |
| POST | `/api/v1/privacy/holds/{id}/release-approval` | Approve a requested release as an independent checker |
| POST | `/api/v1/privacy/holds/{id}/release-request` | Request release of an active hold |
| GET | `/api/v1/privacy/policies/assets` | List registered data assets |
| GET | `/api/v1/privacy/policies/assets/{assetId}/fields` | Get field protection controls |
| POST | `/api/v1/privacy/policies/disposition-evaluations` | Evaluate retention maturity and preservation hold |
| GET | `/api/v1/privacy/policies/retention` | Get the effective retention schedule |
| GET | `/api/v1/privacy/purposes` | List processing-purpose versions |
| POST | `/api/v1/privacy/purposes` | Create a draft purpose version |
| GET | `/api/v1/privacy/purposes/{id}` | Get one processing-purpose version |
| POST | `/api/v1/privacy/purposes/{id}/approval` | Approve and activate a purpose version |
| GET | `/api/v1/products` | list |
| POST | `/api/v1/products` | create_1 |
| DELETE | `/api/v1/products/approvals/{id}` | withdraw_1 |
| POST | `/api/v1/products/approvals/{id}/decision` | decision |
| GET | `/api/v1/products/compare` | compare |
| GET | `/api/v1/products/customer-offers` | customerOffers |
| GET | `/api/v1/products/discover` | discover |
| GET | `/api/v1/products/rule-schema` | schema_1 |
| GET | `/api/v1/products/versions/{id}` | version |
| PUT | `/api/v1/products/versions/{id}` | edit |
| POST | `/api/v1/products/versions/{id}/activate` | activate |
| POST | `/api/v1/products/versions/{id}/rules/{family}` | rule |
| DELETE | `/api/v1/products/versions/{id}/rules/{family}/{key}` | remove |
| PUT | `/api/v1/products/versions/{id}/rules/{family}/{key}` | update |
| POST | `/api/v1/products/versions/{id}/submit` | submit_1 |
| GET | `/api/v1/products/{id}` | detail |
| GET | `/api/v1/products/{id}/audit` | audit |
| GET | `/api/v1/products/{id}/effective` | effective |
| POST | `/api/v1/products/{id}/eligibility` | eligibility |
| POST | `/api/v1/products/{id}/lifecycle` | lifecycle |
| POST | `/api/v1/products/{id}/treatments` | treatment |
| POST | `/api/v1/products/{id}/versions` | draft |
| GET | `/api/v1/reconciliation-exceptions` | List reconciliation exceptions |
| POST | `/api/v1/reconciliation-exceptions/{id}/actions` | Act on reconciliation exception |
| GET | `/api/v1/reconciliation/journals` | List unbalanced journals |
| GET | `/api/v1/reconciliation/positions` | List account position exceptions |
| GET | `/api/v1/reporting/deliveries/{id}` | Get delivery status |
| POST | `/api/v1/reporting/internal/deliveries/{id}/outcome` | Record delivery outcome |
| POST | `/api/v1/reporting/internal/events` | Consume owner event |
| GET | `/api/v1/reporting/internal/outbox` | List pending outbox events |
| POST | `/api/v1/reporting/internal/outbox/{id}/published` | Acknowledge published event |
| POST | `/api/v1/reporting/internal/statement-requests/{id}/process` | Process authorized request |
| GET | `/api/v1/reporting/statement-requests` | List account requests |
| POST | `/api/v1/reporting/statement-requests` | Create statement request |
| GET | `/api/v1/reporting/statement-requests/{id}` | Get request status |
| POST | `/api/v1/reporting/statement-requests/{id}/cancel` | Cancel a pending request |
| POST | `/api/v1/reporting/statement-requests/{id}/process` | Issue an authorized statement |
| GET | `/api/v1/reporting/statements` | List issued statements |
| GET | `/api/v1/reporting/statements/{id}` | View issued statement |
| GET | `/api/v1/reporting/statements/{id}/deliveries` | List delivery evidence |
| POST | `/api/v1/reporting/statements/{id}/deliveries` | Request a statement delivery |
| GET | `/api/v1/reporting/statements/{id}/download` | Download a statement |
| GET | `/api/v1/system/modules` | modules |
| GET | `/api/v1/system/schema` | schema |
| POST | `/api/v1/teller/cash` | cash |
| GET | `/api/v1/teller/tills` | tills |
| POST | `/api/v1/teller/tills` | open |
| POST | `/api/v1/teller/tills/{id}/close` | close |
| POST | `/api/v1/transactions/transfers` | Post an internal transfer |
| GET | `/api/v1/transactions/{id}` | Get a transaction |
| POST | `/api/v1/transactions/{id}/reversal-decision` | Decide transfer reversal |
| POST | `/api/v1/transactions/{id}/reversal-requests` | Request transfer reversal |
| GET | `/api/v1/treasury/liquidity-holds` | holds |
| POST | `/api/v1/treasury/liquidity-holds` | Reserve liquidity |
| POST | `/api/v1/treasury/liquidity-holds/{id}/decision` | decideHold |
| GET | `/api/v1/treasury/reconciliation-exceptions` | exceptions |
| POST | `/api/v1/treasury/reconciliation-exceptions` | openException |
| POST | `/api/v1/treasury/reconciliation-exceptions/{id}/resolution` | resolveException |
| GET | `/api/v1/treasury/reserve-accounts` | accounts |
| POST | `/api/v1/treasury/reserve-accounts` | Create a reserve account and zero position |
| GET | `/api/v1/treasury/reserve-accounts/{id}/position` | position |
| POST | `/api/v1/treasury/reserve-movements/confirm` | Confirm reserve movement |
| GET | `/api/v1/treasury/settlement-cycles` | cycles |
| POST | `/api/v1/treasury/settlement-cycles` | createCycle |
| POST | `/api/v1/treasury/settlement-cycles/{id}/close` | closeCycle |
| GET | `/api/v1/treasury/settlement-cycles/{id}/items` | items |
| POST | `/api/v1/treasury/settlement-cycles/{id}/items` | addItems |
| POST | `/api/v1/treasury/settlement-evidence` | evidence |
| GET | `/api/v1/treasury/work-items` | work |
| POST | `/api/v1/treasury/work-items` | createWork |
| POST | `/api/v1/treasury/work-items/{id}/decision` | decide |
| POST | `/api/v1/treasury/work-items/{id}/submit` | submit |
