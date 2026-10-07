# Credit card integration with updated main

## Preserved histories

The integration combines remote main commit `da8929e` (payments, teller, treasury and ledger changes) with credit card feature commit `78ea458`, including implementation commit `870df77`. It uses a merge commit, preserving both histories. The original credit card tip is also saved locally as `backup/credit-card-before-main-20261007`.

[Pull request #8](https://github.com/ShashankShenoy/HereComesTheMoney/pull/8) was merged as `495888b`. Both source commits are ancestors of main; the merged code tree matches the reviewed integration commit `77ab516`. On 7 October 2026, the local checkout and GitHub main were confirmed at `495888b` before the documentation/build-helper follow-up.

The combined code retains main's GL inquiry, teller/vault cash allocation, replenishment, simulated RBI delivery/opening, translations and administrator account access changes. Credit cards add their dedicated screen and scoped approval routes to the existing application. The local schema is regenerated from all combined Oracle scripts so its constraints follow the final installation order.

## Existing Oracle databases

Git merging does not install Oracle tables. Review the installed schema before using the combined application. No shared Oracle migration is executed by the integration or application startup.

| Existing installation | Required additions |
| --- | --- |
| Main migrations through 014, no credit card tables | Apply `015-credit-cards.sql` once, configure `CC_LIMIT`, then run `database/acceptance/credit-cards.sql`. |
| Credit cards already installed using the former `011-credit-cards.sql` | Skip 015. Apply only missing teller/RBI migrations 011–014 in their dependency order, then run both card acceptance and RBI readiness checks. |
| Both branches' schema additions already installed | Run acceptance checks; no migration is needed solely because Git branches merged. |
| Partial or uncertain installation | Inspect the exact objects and review the failed/partial migration before continuing. DDL scripts are not general repeatable installers. |

The current credit card script is **015-credit-cards.sql**. Teller keeps **011-teller-vault-allocation.sql**. This resolves the competing 011 numbers without recreating already installed credit card objects. For the teller additions, install 011 → 012-rbi-cash-delivery → 013 → 014. The 012 readiness script is read-only.

## SQL file number and Oracle table names

| Identifier | Meaning | Action for an already installed credit card schema |
| --- | --- | --- |
| `database/015-credit-cards.sql` | Current repository installation sequence, after main's 011–014 scripts | Skip this installer if its former filename was already executed successfully. |
| Former `database/011-credit-cards.sql` | Historical filename on the credit card feature branch; the same credit card installation | Keep the installed objects and data. If you keep a manual deployment log, note that this installation now corresponds to 015. |
| `M11_CC_*` | Permanent credit card module namespace used by SQL and Java | Keep all six names unchanged. There are no M15 replacement tables. |
| `database/011-teller-vault-allocation.sql` | Your friend's separate teller migration | Check its objects independently; credit cards never installed this teller migration. |

Oracle does not derive a module version from a SQL filename. This application does not automatically execute or track numbered Oracle migrations at startup. A Git commit records code; an Oracle commit records a database transaction. Neither renames tables when the repository file is renamed.

For a completed, committed former credit card installation:

1. Do not rerun the credit card installer or rename `M11_CC_*` tables.
2. Run `database/acceptance/credit-cards.sql` with F5 in SQL Developer. Expect six tables, three enabled/valid triggers, three active credit card GL accounts and zero exception rows.
3. Run `database/acceptance/integrated-main.sql` to check main's teller/RBI tables, linkage columns, triggers and accounting controls. Install only genuinely missing migrations after reviewing their dependencies. The read-only `database/012-rbi-cash-delivery-readiness.sql` reports reserve/vault/till readiness.
4. Rebuild the combined code, restart against Oracle and sign in again. Existing card products, cards, balances and audit history remain available.

Do not run `001-original-oracle.sql` against needed data. None of the additive installations intentionally posts customer, reserve, vault or card balances. Existing approved card terms and cards remain in Oracle. A new application start does not import the H2 fixtures.

## Build and run

Stop the current application before rebuilding its checkout. If the database VPN blocks dependencies, build with `build-before-start.ps1` on the network that permits Maven/npm, reconnect VPN, and start with `start-oracle-alpha.ps1 -SkipBuild`. The updated fingerprint calculation was checked across Windows PowerShell 5.1 and PowerShell 7. The combined restart helper uses the standard versioned JAR and verifies its recorded build before stopping the previous application.

An offline rebuild is also available as `build-before-start.ps1 -Offline` when Maven/npm dependencies are already cached. This still runs `clean verify`, frontend syntax checks, frontend tests and the frontend build. Missing cached dependencies fail the build; no successful build record is written on failure.

### What the Oracle restart helper does

`restart-oracle-alpha.ps1` performs three steps:

1. Loads private `.env.ps1` settings and verifies the source/JAR/frontend fingerprints.
2. Stops only the recorded application processes, checking their recorded start times.
3. Runs `start-oracle-alpha.ps1 -SkipBuild` with the standard Maven JAR, `backend/target/moneybags-integrated-1.0.0.jar`.

That JAR contains the combined banking modules, including your friend's changes and credit cards. The helper previously looked for a separately named GL-ledger JAR; it now uses the project's actual Maven output. VPN preflight, actual JVM tracking and failed-start cleanup remain in the launcher. None of these steps execute SQL, reset Oracle or change financial balances. A pull or source change requires a fresh verified build before restarting.

## Integration verification

Verified on 7 October 2026 in an isolated checkout:

- Maven offline `clean verify`: **34 tests passed**, zero failures/errors/skips. Both the newer main regression tests and the credit card tests are included. The executable standard JAR was packaged successfully.
- Frontend offline dependency installation, JavaScript checks, **14 tests** and standalone build passed.
- Regenerated H2 fixture: 188 tables and 255 foreign keys from the combined Oracle scripts. The existing teller/RBI constraints and credit card constraints were exercised by the merged backend suites.
- The combined application started with a disposable H2 database on separate ports 8092/5175: backend health `UP`, frontend HTTP 200.
- Browser checks retained main's administrator Accounts screen without the removed account-number lookup, the three Transactions/GL/Operations tabs, reserve GL paging and balanced journal details.
- Browser credit card workflow: administrator application → independent checker approval → customer activation → INR 37.50 simulated purchase → full customer repayment → `MATCHED` reconciliation with zero principal/interest/receivable. The existing account and GL workflows remained accessible.
- The source files for main's BankingController, BankingAccess, TellerController, reserve-opening controller, treasury repository/service, translations, CSS and IntegratedContextTest were retained without content changes from `da8929e`.

These initial merge checks used a disposable H2 database; its processes were stopped afterwards. The following separate checks exercised the combined application against Oracle rather than treating H2 as proof of Oracle behavior.

## Combined application on Oracle ALPHA — 7 October 2026

- Existing installation: all six credit card tables, all six new main teller/RBI tables and four treasury linkage columns were already present. No credit card or teller/RBI installer was rerun. The former credit card filename is equivalent to 015.
- The original checkout was rebuilt on the database VPN using `build-before-start.ps1 -Offline`: **34 backend tests** and **14 frontend tests**, syntax checks and packaging passed. Dependencies were already cached. Windows PowerShell 5.1 built the record; PowerShell 7 also accepted `-CheckOnly`.
- The corrected restart helper launched the current standard JAR against the default Oracle profile. Backend health was `UP`, and the actual Oracle JET frontend loaded at `http://localhost:5174`.
- Existing Moneybags Classic remained approved. Admin proposed the clearly named synthetic `CC_MERGE_VERIFY` product; maker self-approval returned 409, `checker1` independently approved, and admin retired it after the test. No card was issued from this test product.
- `checker1` read the existing synthetic card. Its product creation, purchases, repayments and product retirement returned HTTP 403. Its updated password was supplied privately for this test, with no credential or role reset.
- Existing synthetic card ending 5435: INR 35 purchase → freeze → rejected frozen purchase → INR 10 repayment while frozen → identical repayment replay → rejected changed replay → full INR 35 refund → unfreeze. The refund reduced INR 25 principal and returned INR 10 excess to the deposit account.
- Final persisted state: card active, principal/interest/receivable zero, INR 25,000 available credit, deposit balance restored to INR 1,050. There are six immutable entries: three from the earlier verification and three from this one. No rejected/replayed request added entries. Reconciliation is `MATCHED` in both the API and UI.
- Main's Accounts, paged GL register, teller vault/till/replenishment/delivery and treasury reserve/opening inquiry APIs returned HTTP 200. The existing Accounts layout and three Transactions/GL/Operations tabs remained present on Oracle.
- Rollback-only Oracle checks verified immutable product terms (ORA-20060), entry update/delete rejection (ORA-20061), statement-level trigger rejection with a zero-row update (ORA-20062), and an independent session's card lock conflict (ORA-54). The lock was available after rollback. No statement was issued early.
- Broader readiness found two existing treasury triggers marked invalid after table alterations, with no stored compilation errors. `ALTER TRIGGER ... COMPILE` repaired both without changing source or data. Both became enabled/valid and rejected zero-row delete statements with ORA-20021/20022.
- Final credit card acceptance exception queries returned zero rows. The entire Oracle schema had zero invalid objects; journal balance, vault-to-GL and reserve reconciliation mismatch queries also returned zero rows.

The Oracle checks covered staff configuration, approval, servicing, inquiries, trigger enforcement and basic row locking on synthetic fixtures. Customer sign-in, all teller/RBI financial commands, full concurrent posting stress, future statement issuance, query plans and crash/failure recovery were not retested on live Oracle. Automated isolated tests cover additional scenarios; this record does not claim production certification.
