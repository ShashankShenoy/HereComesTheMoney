package com.moneybags.txn.core;

import com.moneybags.txn.api.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only Module 6 adapter used to prevent releasing externally uncertain funds. */
@Component
public class PaymentCatalog {
    private final JdbcTemplate db;

    /** Injects the shared-schema read adapter; use a Module 6 client after extraction. */
    public PaymentCatalog(JdbcTemplate db) { this.db = db; }

    /** Ensures Module 6 authorized exactly this outbound amount and source account. */
    public void requireAuthorizedHold(long paymentId, long accountId, BigDecimal amount) {
        List<Payment> rows = db.query("SELECT SOURCE_ACCOUNT_ID,AMOUNT,STATUS FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=? AND PAYMENT_DIRECTION='OUTBOUND'",
                (rs, n) -> new Payment(rs.getLong(1), rs.getBigDecimal(2), rs.getString(3)), paymentId);
        if (rows.isEmpty() || rows.get(0).accountId() != accountId || rows.get(0).amount().compareTo(amount) != 0 ||
                !List.of("AUTHORIZED", "LIQUIDITY_PENDING", "HELD").contains(rows.get(0).status()))
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_AUTHORIZED", "Module 6 payment does not authorize this hold");
    }

    /** Allows release only after Module 6 has a terminal, non-uncertain status. */
    public void requireTerminalFailure(long paymentId) {
        List<String> statuses = db.query("SELECT STATUS FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?", (rs, n) -> rs.getString(1), paymentId);
        if (statuses.isEmpty() || !List.of("REJECTED", "CANCELLED").contains(statuses.get(0)))
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_UNCERTAIN", "Payment is not confirmed rejected or cancelled");
    }

    /** Minimal Module 6 payment facts needed by Module 5. */
    private record Payment(long accountId, BigDecimal amount, String status) { }
}
