# Main branch integration — 23 October 2026

Compared the local workspace with `ShashankShenoy/HereComesTheMoney` main at `88e0df3b79463aa65a7c2391b1879c0a6692a264` (23 October 2026, 00:01 IST). A second remote check confirmed the same head during integration.

## Comparison and changes

The workspace already includes the main-branch work through `74a856d`, including pending loan disbursement renewal and fixed-deposit account lookup. It also contains additional local assistant, product-form, demo-funding, teller, permissions and UI work. Those additions were retained.

The remaining upstream change is the loan account liability mapping fix:

- Added `LoanAccountLiabilityGl` to resolve the account product's active, currently effective liability mapping. It prefers a loan mapping and falls back to a deposit mapping, requiring an active liability ledger account.
- Integrated that resolver into loan disbursement, scheduled repayment and early repayment.
- Merged the upstream `IntegratedContextTest` coverage for disbursement and repayment when the loan-specific account liability mapping is retired.
- Extended the existing local partial-prepayment and retry test to exercise the same deposit-only mapping case. Its transaction rolls back the fixture change.

The three production loan files match fetched main after normalizing line endings. A three-way merge retained the local additions in `IntegratedContextTest`. Only four existing files changed: the two loan controllers and two acceptance test files. One production helper was added. Hash comparison confirmed 613 other existing source/configuration files were unchanged, with none removed.

## Recovery and repository state

Original copies of all four changed existing files are under `.merge-main/local-backup/`, relative to the workspace root. `.merge-main/integration.json` records the upstream revision and file list; `.merge-main/preservation-check.json` records the preservation check. The comparison checkout and verification logs remain in `.merge-main/`.

At integration time this downloaded workspace had no project-local Git metadata; the discoverable repository metadata was in the user-profile directory. Integration was therefore applied to the workspace files using a separate fetched checkout. That integration did not change the parent repository branch, index or commit.

During the subsequent push repair, a project-local repository was initialized from current upstream main. The user's earlier commit was retained on `codex/backup-local-before-push-fix`, and the working files were retained in the correct upstream folder layout. The local comparison checkout is excluded from version control. The parent repository remains untouched.

## Validation

- Frontend: 127 tests passed; syntax checks and standalone build passed.
- Gateway: 25 tests passed.
- Mocked Oracle startup lifecycle: 21 checks passed using a temporary process-local helper workaround and normal Windows cryptography access. The unmodified startup test script has a `New-FakeProcess` module-scope lookup error at line 161, reproduced on fetched main. The workaround did not alter application or test source.
- Backend: full Maven `verify` succeeded across all 17 reactor projects. There were 313 passing tests, zero failures/errors, and 60 skipped Oracle opt-in tests (373 discovered). This includes 93 integrated banking tests and 22 assistant statement/loan tests. Restricted initial attempts encountered dependency-cache access errors; verification succeeded with normal cache access. Evidence: `.merge-main/backend-verified.log` and the modules' `target/surefire-reports/` XML files.

Oracle-connected opt-in acceptance tests and live model-provider tests were not requested or enabled. The checks use isolated local fixtures and mocks; the live application was not restarted.
