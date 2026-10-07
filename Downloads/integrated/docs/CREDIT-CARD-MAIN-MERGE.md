# Credit card integration with updated main

## Preserved histories

The integration combines remote main commit `da8929e` (payments, teller, treasury and ledger changes) with credit card feature commit `78ea458`, including implementation commit `870df77`. It uses a merge commit, preserving both histories. The original credit card tip is also saved locally as `backup/credit-card-before-main-20261007`.

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

Do not run `001-original-oracle.sql` against needed data. None of the additive installations intentionally posts customer, reserve, vault or card balances. Existing approved card terms and cards remain in Oracle. A new application start does not import the H2 fixtures.

## Build and run

Stop the current application before rebuilding its checkout. If the database VPN blocks dependencies, build with `build-before-start.ps1` on the network that permits Maven/npm, reconnect VPN, and start with `start-oracle-alpha.ps1 -SkipBuild`. Use the same Windows PowerShell environment for both commands. The combined restart helper uses the standard versioned JAR and verifies its recorded build before stopping the previous application.

## Integration verification

Verified on 7 October 2026 in an isolated checkout:

- Maven offline `clean verify`: **34 tests passed**, zero failures/errors/skips. Both the newer main regression tests and the credit card tests are included. The executable standard JAR was packaged successfully.
- Frontend offline dependency installation, JavaScript checks, **14 tests** and standalone build passed.
- Regenerated H2 fixture: 188 tables and 255 foreign keys from the combined Oracle scripts. The existing teller/RBI constraints and credit card constraints were exercised by the merged backend suites.
- The combined application started with a disposable H2 database on separate ports 8092/5175: backend health `UP`, frontend HTTP 200.
- Browser checks retained main's administrator Accounts screen without the removed account-number lookup, the three Transactions/GL/Operations tabs, reserve GL paging and balanced journal details.
- Browser credit card workflow: administrator application → independent checker approval → customer activation → INR 37.50 simulated purchase → full customer repayment → `MATCHED` reconciliation with zero principal/interest/receivable. The existing account and GL workflows remained accessible.
- The source files for main's BankingController, BankingAccess, TellerController, reserve-opening controller, treasury repository/service, translations, CSS and IntegratedContextTest were retained without content changes from `da8929e`.

Earlier Oracle credit card checks are documented separately in [CREDIT-CARDS.md](CREDIT-CARDS.md). No shared Oracle migration or financial posting was made for this Git integration. The disposable local test processes were stopped after verification. These merged H2 checks do not certify the newly combined Oracle teller/RBI release; review the installed schema and run its acceptance checks before starting the updated Oracle application.
