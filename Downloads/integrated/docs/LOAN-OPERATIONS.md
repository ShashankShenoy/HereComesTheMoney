# Loan operations in the Oracle ALPHA workspace

The customer applies from **Loans → Apply for a loan**. The customer selects one
active product, enters an amount within its displayed range and a term within
its displayed month range, and submits. The application appears under **Your
loan applications** with status `SUBMITTED`.

## Staff sequence

The generic operations catalogue uses different numeric IDs for applications,
offers, documents, facilities and disbursements. Look up each ID from the
preceding operation's result or its corresponding View operation.

1. Officer: **Loans Applications · View** with the customer's branch (for
   example `MUM001`). Record the **application ID**.
2. Officer: **Loans Applications Assessments · Create or action**. Supply the
   application ID, verified income and obligations, recommendation and reasons.
   The officer must assess affordability; the app does not make a credit decision.
3. Independent checker: **Loans Applications Decisions · Create or action**.
   Supply the application ID, decision, reasons, sanctioned amount, tenure,
   annual rate, `LOAN_SANCTION` authority code and evidence reference. The
   checker cannot be the application/assessment maker.
4. Officer: **Loans Applications Offers · Create or action** with the application
   ID, offer document reference and expiry. Record the returned **offer ID**.
5. Customer: **Your loan applications → View offers → Accept offer**. Select
   active INR account numbers for disbursement and repayment. Staff cannot
   accept an offer on the customer's behalf through the current API.
6. Officer: add the signed offer under **Loans Applications Documents · Create
   or action**. Record the returned **document ID**. Independent checker:
   verify that document with the application and document IDs.
7. Officer: convert the accepted application. Record the returned **facility
   ID**, request a disbursement, then record its **disbursement ID**.
8. Independent checker: approve that disbursement using `LOAN_SANCTION`.
   This posts the balanced journal, activates the facility and creates its
   monthly repayment schedule.
9. Servicing officer: accrue interest when installments become due, then
   apply repayments within the accrued due amount. Customer can see the active
   facility and repayment schedule on their Loans page.

An `Id` in **Loans Offers Accept** means **offer ID**, not application ID. Use
the customer **View offers** action instead; it presents eligible account
numbers, so the customer need not type internal account IDs.

## Oracle ALPHA readiness

Run `tools/check-loan-readiness.ps1` from PowerShell to inspect the Oracle
configuration. The check reads `.env.ps1` for the same connection settings as
the launcher but prints no password. It reports active loan GL mappings,
checker sanction authorities and the loan liability mapping for active INR
deposit-account versions.

`database/017-loan-operational-readiness.sql` is a separate, reviewable SQL*Plus
migration. It creates three INR loan GL accounts, maps the six active web loan
versions to principal receivable, interest receivable and interest income,
maps active customer deposit versions to their existing liability GL for loan
postings, and grants the `BANK_CHECKER` role `LOAN_SANCTION` authority capped at
INR 5,000,000 and 20% annual interest. Review this access and chart-of-accounts
change before running it. The script rolls back on an SQL error and can be
re-run.

This implementation supports fixed-rate, monthly, single-disbursement,
reducing-balance loans. It does not yet process early prepayment, rescheduling,
moratoria, tranches, fees or penalties through the servicing API. The active
product loan rules currently say prepayment, partial prepayment and foreclosure
are allowed; that product policy needs a governed revision or a separately
implemented servicing workflow before those actions are offered online.
