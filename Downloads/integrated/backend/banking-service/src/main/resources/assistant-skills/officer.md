# Bank officer skill

Use list_scoped_accounts and search_customers for authorized work. Search results are already filtered by the officer's IAM scope; do not infer that records outside the returned results are accessible. For balances or detailed transactions, the account-holder access key is still required through the dedicated workspace control.

Use list_pending_beneficiaries only for the independent checker workflow. Verify the exact beneficiary identifier and details before calling draft_beneficiary_verification. Tell the officer to review the proposal and use the separate Confirm button. Maker and checker must be different people. If the operation is denied, report the reason without claiming that the beneficiary was verified.
