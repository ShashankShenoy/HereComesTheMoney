package com.moneybags.payments.core;

import com.moneybags.payments.api.ApiException;
import com.moneybags.payments.api.Contracts;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Replaceable read-only contract for Modules 2, 3, 4, 5 and 7 in a split deployment. */
public interface IntegrationChecks {
    /** Verifies customer, account, posting fence and a simple effective product payment rule. */
    void authorize(Contracts.Payment payment, String channelCode, Long accountId, String cifId);

    /** Verifies that an existing Module 5 hold belongs to this payment and has the expected amount. */
    void hold(long paymentId, long holdId, BigDecimal amount);

    /** Verifies that a posted debit consumed the same payment's funds hold. */
    void consumedHold(long paymentId, long holdId, long journalId);

    /** Verifies a Module 7 reserve hold when the payment entered liquidity pending state. */
    void liquidityHold(long paymentId, long holdId, BigDecimal amount, String railCode);

    /** Verifies that a Module 5 journal is posted for exactly this payment. */
    void journal(long paymentId, long journalId, BigDecimal amount, String expectedType);

    /** Verifies the Module 7 reserve movement and its settlement evidence. */
    void treasury(long paymentId, long entryId, BigDecimal amount);
}

/** Shared-schema adapter; replace with typed authenticated service clients when modules separate. */
@Component
final class SharedOracleIntegrationChecks implements IntegrationChecks {
    private final JdbcTemplate jdbc;

    /** Receives the read-only query gateway. */
    SharedOracleIntegrationChecks(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Applies conservative local authorization; complex Module 3 predicates need a policy service. */
    @Override
    public void authorize(Contracts.Payment payment, String channel, Long accountId, String cifId) {
        if (accountId == null || cifId == null) throw conflict("PARTY_REQUIRED", "Account and CIF are required");
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.ACCOUNT_STATUS,a.PRIMARY_CIF_ID,a.PRODUCT_VERSION_ID,c.STATUS CIF_STATUS,c.KYC_STATUS,f.DEBIT_STATUS,f.CREDIT_STATUS,v.VERSION_STATE,v.DEFAULT_TXN_ACTION FROM M04_BANK_ACCOUNT a JOIN M02_CIF_CUSTOMER c ON c.CIF_ID=a.PRIMARY_CIF_ID JOIN M03_PM_PRODUCT_VERSION v ON v.PRODUCT_VERSION_ID=a.PRODUCT_VERSION_ID JOIN M05_POSTING_FENCE f ON f.BANK_ACCOUNT_ID=a.ACCOUNT_ID WHERE a.ACCOUNT_ID=? AND a.PRIMARY_CIF_ID=? AND v.EFFECTIVE_FROM_AT<=SYSTIMESTAMP AND (v.EFFECTIVE_TO_AT IS NULL OR v.EFFECTIVE_TO_AT>SYSTIMESTAMP)", accountId, cifId);
        if (rows.size() != 1) throw conflict("ACCOUNT_NOT_ELIGIBLE", "Account, customer or product version is unavailable");
        var row = rows.get(0);
        String side = payment.direction().equals("OUTBOUND") ? "DEBIT_STATUS" : "CREDIT_STATUS";
        if (!"ACTIVE".equals(row.get("ACCOUNT_STATUS")) || !"ACTIVE".equals(row.get("CIF_STATUS"))
                || !"VERIFIED".equals(row.get("KYC_STATUS")) || !"ACTIVE".equals(row.get("VERSION_STATE"))
                || !"OPEN".equals(row.get(side)))
            throw conflict("ACCOUNT_NOT_ELIGIBLE", "Customer, account, product or posting fence is not active");
        Integer complex = jdbc.queryForObject("SELECT COUNT(*) FROM M03_PM_TRANSACTION_RULE WHERE PRODUCT_VERSION_ID=? AND OPERATION_CODE='PAYMENT' AND DIRECTION_CODE=? AND (CHANNEL_CODE IS NULL OR CHANNEL_CODE=?) AND (COUNTERPARTY_CODE IS NOT NULL OR GEOGRAPHY_CODE IS NOT NULL OR TIME_WINDOW_CODE IS NOT NULL)",
                Integer.class, row.get("PRODUCT_VERSION_ID"), payment.direction(), channel);
        if (complex != null && complex > 0)
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_ADAPTER_REQUIRED", "A conditional Module 3 rule requires a policy adapter");
        List<java.util.Map<String, Object>> rules = jdbc.queryForList(
                "SELECT ACTION_CODE FROM M03_PM_TRANSACTION_RULE WHERE PRODUCT_VERSION_ID=? AND OPERATION_CODE='PAYMENT' AND DIRECTION_CODE=? AND (CHANNEL_CODE IS NULL OR CHANNEL_CODE=?) ORDER BY PRIORITY_NO FETCH FIRST 1 ROWS ONLY",
                row.get("PRODUCT_VERSION_ID"), payment.direction(), channel);
        if (!rules.isEmpty()) {
            var rule = rules.get(0);
            if (!"ALLOW".equals(rule.get("ACTION_CODE"))) throw conflict("POLICY_DENIED", "Product rule denies this payment");
        } else if (!"ALLOW".equals(row.get("DEFAULT_TXN_ACTION"))) {
            throw conflict("POLICY_DENIED", "No effective product payment permission");
        }
    }

    /** Confirms an ACTIVE or CONSUMED hold without trusting an ID from the caller. */
    @Override
    public void hold(long paymentId, long holdId, BigDecimal amount) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM M05_FUNDS_HOLD WHERE HOLD_ID=? AND PAYMENT_ID=? AND AMOUNT=? AND STATUS IN ('ACTIVE','CONSUMED')", Integer.class, holdId, paymentId, amount);
        if (count == null || count != 1) throw conflict("HOLD_MISMATCH", "Module 5 hold is not valid for this payment");
    }

    /** Confirms that the debit journal consumed the payment's original hold. */
    @Override
    public void consumedHold(long paymentId, long holdId, long journalId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM M05_FUNDS_HOLD WHERE HOLD_ID=? AND PAYMENT_ID=? AND STATUS='CONSUMED' AND CONSUMED_BY_JOURNAL_ID=?", Integer.class, holdId, paymentId, journalId);
        if (count == null || count != 1) throw conflict("HOLD_NOT_CONSUMED", "Module 5 hold was not consumed by this journal");
    }

    /** Confirms treasury reserved the same payment and amount before held state. */
    @Override
    public void liquidityHold(long paymentId, long holdId, BigDecimal amount, String railCode) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM M07_TREASURY_LIQUIDITY_HOLD WHERE LIQUIDITY_HOLD_ID=? AND PAYMENT_ID=? AND AMOUNT=? AND RAIL_CODE=? AND STATUS IN ('RESERVED','COMMITTED')",
                Integer.class, holdId, paymentId, amount, railCode);
        if (count == null || count != 1) throw conflict("LIQUIDITY_HOLD_MISMATCH", "Module 7 liquidity reserve is not valid for this payment");
    }

    /** Confirms the posted journal, amount and journal purpose. */
    @Override
    public void journal(long paymentId, long journalId, BigDecimal amount, String expectedType) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM M05_GL_JOURNAL j JOIN M06_PAYMENT_INSTRUCTION i ON i.PAYMENT_ID=j.PAYMENT_ID WHERE j.JOURNAL_ID=? AND j.PAYMENT_ID=? AND j.JOURNAL_TYPE=? AND EXISTS (SELECT 1 FROM M05_GL_POSTING p WHERE p.JOURNAL_ID=j.JOURNAL_ID AND p.AMOUNT=? AND p.BANK_ACCOUNT_ID=CASE WHEN i.PAYMENT_DIRECTION='OUTBOUND' THEN i.SOURCE_ACCOUNT_ID ELSE i.DESTINATION_ACCOUNT_ID END AND p.ENTRY_SIDE=CASE WHEN i.PAYMENT_DIRECTION='OUTBOUND' AND j.JOURNAL_TYPE='PAYMENT_PROVISIONAL' THEN 'DR' ELSE 'CR' END)", Integer.class,
                journalId, paymentId, expectedType, amount);
        if (count == null || count != 1) throw conflict("JOURNAL_MISMATCH", "Module 5 journal does not match this payment");
    }

    /** Confirms a settled reserve movement with verified source evidence. */
    @Override
    public void treasury(long paymentId, long entryId, BigDecimal amount) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER t JOIN M07_SETTLEMENT_EVIDENCE e ON e.EVIDENCE_ID=t.EVIDENCE_ID AND e.RESERVE_ACCOUNT_ID=t.RESERVE_ACCOUNT_ID JOIN M06_PAYMENT_INSTRUCTION i ON i.PAYMENT_ID=t.PAYMENT_ID WHERE t.TREASURY_ENTRY_ID=? AND t.PAYMENT_ID=? AND t.AMOUNT=? AND t.RAIL_CODE=i.RAIL_CODE AND t.MOVEMENT_SIDE=CASE WHEN i.PAYMENT_DIRECTION='OUTBOUND' THEN 'OUT' ELSE 'IN' END AND e.EVIDENCE_STATUS='VERIFIED' AND (i.LIQUIDITY_HOLD_ID IS NULL OR EXISTS (SELECT 1 FROM M07_TREASURY_LIQUIDITY_HOLD h WHERE h.LIQUIDITY_HOLD_ID=i.LIQUIDITY_HOLD_ID AND h.PAYMENT_ID=i.PAYMENT_ID AND h.STATUS='CONSUMED' AND h.CONSUMED_BY_ENTRY_ID=t.TREASURY_ENTRY_ID))", Integer.class,
                entryId, paymentId, amount);
        if (count == null || count != 1) throw conflict("TREASURY_MISMATCH", "Module 7 reserve settlement is unverified or mismatched");
    }

    /** Constructs a safe domain conflict. */
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}

