package com.moneybags.iam.service;

import java.util.List;

/** Human checker rights. Approval decisions may have financial effects, but
 * this role cannot initiate or maintain unrelated business records. */
public final class BankRolePolicy {
    private BankRolePolicy() {}

    public static final String CHECKER = "BANK_CHECKER";

    public static final List<String> CHECKER_PERMISSIONS = List.of(
        "SYSTEM_SCHEMA_READ", "IAM_USER_READ", "IAM_AUDIT_READ", "CIF_READ",
        "PRODUCT_READ", "ACCOUNT_READ", "TXN_READ", "GL_READ", "GL_RECONCILE",
        "PAYMENT_READ", "TREASURY_READ", "LOAN_READ", "STATEMENT_READ",
        "STATEMENT_CATALOG_READ",
        "PRIVACY_PURPOSE_VIEW", "PRIVACY_CONSENT_VIEW", "PRIVACY_CASE_VIEW",
        "PRIVACY_HOLD_VIEW", "PRIVACY_POLICY_VIEW", "PRIVACY_AUDIT_VIEW",
        "PRIVACY_EXPORT_VIEW", "FX_READ", "TELLER_READ",
        "IAM_ACCESS_APPROVE", "KYC_REVIEW", "PRODUCT_APPROVE",
        "ACCOUNT_APPROVE", "TXN_REVERSE_APPROVE", "GL_CLOSE_APPROVE",
        "PAYMENT_APPROVE", "TREASURY_WORK_APPROVE", "LOAN_DECIDE", "LOAN_DOCUMENT_VERIFY",
        "LOAN_DISBURSE_APPROVE", "TELLER_APPROVE", "FX_APPROVE",
        "BENEFICIARY_VERIFY", "PRIVACY_PURPOSE_APPROVE",
        "PRIVACY_HOLD_APPROVE", "PRIVACY_EXPORT_APPROVE",
        "STATEMENT_APPROVE"
    );
}
