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
        List<AccountSnapshot> rows = db.query("SELECT PRODUCT_VERSION_ID, LIFECYCLE_STATUS, CURRENCY_CODE FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?",
                (rs, n) -> new AccountSnapshot(rs.getLong(1), rs.getString(2), rs.getString(3)), accountId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account does not exist");
        AccountSnapshot account = rows.get(0);
        if (!"ACTIVE".equals(account.status()) || !"INR".equals(account.currency()))
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
    public void requireTransferAllowed(long productVersionId, String channelCode) {
        List<String> actions = db.query("SELECT ACTION_CODE FROM M03_PM_TRANSACTION_RULE WHERE PRODUCT_VERSION_ID=? AND OPERATION_CODE='INTERNAL_TRANSFER' AND (CHANNEL_CODE=? OR CHANNEL_CODE IS NULL) ORDER BY PRIORITY_NO FETCH FIRST 1 ROW ONLY",
                (rs, n) -> rs.getString(1), productVersionId, channelCode);
        if (actions.isEmpty() || !"ALLOW".equals(actions.get(0)))
            throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_RULE_DENIED", "Product policy does not allow this transfer");
    }

    /** Product and account facts needed to validate a transaction. */
    public record AccountSnapshot(long productVersionId, String status, String currency) { }
}

