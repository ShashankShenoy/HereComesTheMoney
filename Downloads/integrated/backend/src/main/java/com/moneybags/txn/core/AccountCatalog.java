package com.moneybags.txn.core;

import com.moneybags.txn.api.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only adapter for Module 4 accounts and Module 3 transaction policy. */
@Component
public class AccountCatalog {
    private final JdbcTemplate db;

    /** Injects the shared-schema adapter; replace this class with service clients after extraction. */
    public AccountCatalog(JdbcTemplate db) { this.db = db; }

    /** Returns the immutable product version and current account state for a posting. */
    public AccountSnapshot requireActive(long accountId) {
        List<AccountSnapshot> rows = db.query("SELECT A.PRODUCT_VERSION_ID,A.LIFECYCLE_STATUS,A.CURRENCY_CODE,P.STATUS,V.VERSION_STATE FROM M04_BANK_ACCOUNT A JOIN M03_PM_PRODUCT P ON P.PRODUCT_ID=A.PRODUCT_ID JOIN M03_PM_PRODUCT_VERSION V ON V.PRODUCT_VERSION_ID=A.PRODUCT_VERSION_ID WHERE A.ACCOUNT_ID=?",
                (rs, n) -> new AccountSnapshot(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), accountId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account does not exist");
        AccountSnapshot account = rows.get(0);
        if (!"ACTIVE".equals(account.status()) || !"INR".equals(account.currency()) ||
                !List.of("ACTIVE", "RETIRED").contains(account.productStatus()) ||
                !List.of("ACTIVE", "RETIRED").contains(account.versionState()))
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_NOT_ACTIVE", "Account is not active for INR posting");
        return account;
    }

    /** Confirms a new control snapshot refers to an account owned by Module 4. */
    public void requireExists(long accountId) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?", Integer.class, accountId);
        if (count == null || count == 0)
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account does not exist");
    }

    /** Applies Product Master's priority-ordered transfer rule and denies when none is published. */
    public void requireTransferAllowed(long productVersionId, String channelCode, String directionCode) {
        if (!List.of("DEBIT", "CREDIT").contains(directionCode))
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIRECTION", "Unsupported transfer direction");
        Integer conditional = db.queryForObject("SELECT COUNT(*) FROM M03_PM_TRANSACTION_RULE WHERE PRODUCT_VERSION_ID=? AND OPERATION_CODE='INTERNAL_TRANSFER' AND DIRECTION_CODE IN (?, 'BOTH') AND (CHANNEL_CODE=? OR CHANNEL_CODE IS NULL) AND (COUNTERPARTY_CODE IS NOT NULL OR GEOGRAPHY_CODE IS NOT NULL OR TIME_WINDOW_CODE IS NOT NULL)",
                Integer.class, productVersionId, directionCode, channelCode);
        if (conditional != null && conditional > 0)
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_ADAPTER_REQUIRED", "A conditional product rule requires a policy adapter");
        List<String> actions = db.query("SELECT ACTION_CODE FROM M03_PM_TRANSACTION_RULE WHERE PRODUCT_VERSION_ID=? AND OPERATION_CODE='INTERNAL_TRANSFER' AND DIRECTION_CODE IN (?, 'BOTH') AND (CHANNEL_CODE=? OR CHANNEL_CODE IS NULL) ORDER BY PRIORITY_NO FETCH FIRST 1 ROW ONLY",
                (rs, n) -> rs.getString(1), productVersionId, directionCode, channelCode);
        if (actions.isEmpty() || !"ALLOW".equals(actions.get(0)))
            throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_RULE_DENIED", "Product policy does not allow this transfer");
    }

    /** Product and account facts needed to validate a transaction. */
    public record AccountSnapshot(long productVersionId, String status, String currency, String productStatus, String versionState) { }
}

