package com.moneybags.payments.core;

import com.moneybags.payments.api.ApiException;
import com.moneybags.payments.api.Contracts;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Oracle persistence for the Module 6 aggregate; callers own transaction boundaries. */
@Repository
public class PaymentRepository {
    private final JdbcTemplate jdbc;

    /** Receives the shared Oracle connection pool. */
    public PaymentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Maps the stable public payment view from a row. */
    private Contracts.Payment payment(ResultSet rs, int row) throws SQLException {
        return new Contracts.Payment(rs.getLong("PAYMENT_ID"), rs.getString("END_TO_END_ID"),
                rs.getString("PAYMENT_DIRECTION"), rs.getString("PAYMENT_KIND"), rs.getString("RAIL_CODE"),
                rs.getBigDecimal("AMOUNT"), rs.getString("STATUS"), rs.getInt("VERSION_NO"),
                nullableLong(rs, "HOLD_ID"), nullableLong(rs, "CUSTOMER_TXN_ID"),
                nullableLong(rs, "CLEARING_BATCH_ID"), nullableLong(rs, "SETTLEMENT_CYCLE_ID"),
                nullableLong(rs, "LIQUIDITY_HOLD_ID"), rs.getString("RAIL_MESSAGE_ID"),
                rs.getString("RAIL_REFERENCE"), rs.getString("REASON_CODE"));
    }

    /** Distinguishes a nullable Oracle number from numeric zero. */
    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** Creates the instruction in RECEIVED state and returns its generated identity. */
    public long insert(Contracts.CreatePayment request, byte[] requestHash) {
        String sql = "INSERT INTO M06_PAYMENT_INSTRUCTION (ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,END_TO_END_ID,PAYMENT_DIRECTION,PAYMENT_KIND,ORIGINAL_PAYMENT_ID,SOURCE_ACCOUNT_ID,SOURCE_CIF_ID,DESTINATION_ACCOUNT_ID,DESTINATION_CIF_ID,BENEFICIARY_TOKEN_REF,BENEFICIARY_BANK_CODE,RAIL_CODE,AMOUNT,CURRENCY_CODE,REQUESTED_EXECUTION_DATE,STATUS) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'INR',?,'RECEIVED')";
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"PAYMENT_ID"});
            ps.setString(1, request.originatorId()); ps.setString(2, request.channelCode());
            ps.setString(3, request.requestKey()); ps.setBytes(4, requestHash);
            ps.setString(5, request.correlationId()); ps.setString(6, request.endToEndId());
            ps.setString(7, request.direction()); ps.setString(8, request.kind());
            ps.setObject(9, request.originalPaymentId()); ps.setObject(10, request.sourceAccountId());
            ps.setString(11, request.sourceCifId()); ps.setObject(12, request.destinationAccountId());
            ps.setString(13, request.destinationCifId()); ps.setString(14, request.beneficiaryTokenRef());
            ps.setString(15, request.beneficiaryBankCode()); ps.setString(16, request.railCode());
            ps.setBigDecimal(17, request.amount()); ps.setDate(18, java.sql.Date.valueOf(request.requestedExecutionDate()));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    /** Reads a payment or raises a stable not-found response. */
    public Contracts.Payment get(long id) {
        List<Contracts.Payment> rows = jdbc.query("SELECT * FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?", this::payment, id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment does not exist");
        return rows.get(0);
    }

    /** Locks one aggregate so evidence and status changes cannot interleave. */
    public Contracts.Payment lock(long id) {
        List<Contracts.Payment> rows = jdbc.query("SELECT * FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=? FOR UPDATE", this::payment, id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment does not exist");
        return rows.get(0);
    }

    /** Lists a bounded queue ordered by the most recent update. */
    public List<Contracts.Payment> list(String status) {
        return status == null || status.isBlank()
                ? jdbc.query("SELECT * FROM M06_PAYMENT_INSTRUCTION ORDER BY UPDATED_AT DESC FETCH FIRST 100 ROWS ONLY", this::payment)
                : jdbc.query("SELECT * FROM M06_PAYMENT_INSTRUCTION WHERE STATUS=? ORDER BY UPDATED_AT DESC FETCH FIRST 100 ROWS ONLY", this::payment, status);
    }

    /** Returns the existing request scope to distinguish a safe replay from a conflicting key. */
    public List<ExistingRequest> existing(String originator, String channel, String key) {
        return jdbc.query("SELECT PAYMENT_ID, REQUEST_HASH FROM M06_PAYMENT_INSTRUCTION WHERE ORIGINATOR_ID=? AND CHANNEL_CODE=? AND REQUEST_KEY=?",
                (rs, row) -> new ExistingRequest(rs.getLong(1), rs.getBytes(2)), originator, channel, key);
    }

    /** Reads the direction-specific transition flags seeded by the complete installer. */
    public List<TransitionRule> transitionRule(String direction, String from, String to) {
        return jdbc.query("SELECT REQUIRES_RAIL_EVIDENCE,REQUIRES_LEDGER_EVIDENCE FROM M06_PAYMENT_DIRECTION_TRANSITION WHERE PAYMENT_DIRECTION=? AND FROM_STATUS=? AND TO_STATUS=?",
                (rs, row) -> new TransitionRule("Y".equals(rs.getString(1)), "Y".equals(rs.getString(2))), direction, from, to);
    }

    /** Advances exactly one version and binds evidence references to the payment. */
    public void advance(Contracts.Payment payment, Contracts.Transition command) {
        int changed = jdbc.update("UPDATE M06_PAYMENT_INSTRUCTION SET STATUS=?,VERSION_NO=VERSION_NO+1,HOLD_ID=COALESCE(?,HOLD_ID),LIQUIDITY_HOLD_ID=COALESCE(?,LIQUIDITY_HOLD_ID),REASON_CODE=?,UPDATED_AT=SYSTIMESTAMP WHERE PAYMENT_ID=? AND VERSION_NO=?",
                command.toStatus(), command.holdId(), command.liquidityHoldId(), command.reasonCode(), payment.paymentId(), payment.version());
        if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Payment version changed");
        jdbc.update("INSERT INTO M06_PAYMENT_STATUS_HISTORY (PAYMENT_ID,FROM_STATUS,TO_STATUS,ACTOR_TYPE,ACTOR_ID,REASON_CODE,EVIDENCE_ID,PAYMENT_DIRECTION,LEDGER_JOURNAL_ID,TREASURY_ENTRY_ID) VALUES (?,?,?,?,?,?,?,?,?,?)",
                payment.paymentId(), payment.status(), command.toStatus(), Actor.type(), Actor.id(),
                command.reasonCode(), command.evidenceId(), payment.direction(), command.ledgerJournalId(), command.treasuryEntryId());
    }

    /** Stores the initial history row; its nullable from-state is intentionally outside the transition FK. */
    public void received(long paymentId, String direction) {
        jdbc.update("INSERT INTO M06_PAYMENT_STATUS_HISTORY (PAYMENT_ID,FROM_STATUS,TO_STATUS,ACTOR_TYPE,ACTOR_ID,PAYMENT_DIRECTION) VALUES (?,NULL,'RECEIVED',?,?,?)",
                paymentId, Actor.type(), Actor.id(), direction);
    }

    /** Persists a replay record for the same request key and payment. */
    public void idempotency(Contracts.CreatePayment request, byte[] hash, long id) {
        jdbc.update("INSERT INTO M06_IDEMPOTENCY_RECORD (ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,PAYMENT_ID,HTTP_STATUS,RESPONSE_REF) VALUES (?,?,?,?,?,201,?)",
                request.originatorId(), request.channelCode(), request.requestKey(), hash, id, "/api/v1/payments/" + id);
    }

    /** Reads selected immutable rail evidence and verifies that it belongs to this instruction. */
    public List<String> railStatus(long paymentId, long evidenceId) {
        return jdbc.query("SELECT RAIL_STATUS FROM M06_RAIL_STATUS_EVIDENCE WHERE PAYMENT_ID=? AND EVIDENCE_ID=?", (rs, row) -> rs.getString(1), paymentId, evidenceId);
    }

    /** Inserts a rail observation and returns its generated evidence ID. */
    public long evidence(long paymentId, String rail, Contracts.RailEvidence request, byte[] hash) {
        String sql = "INSERT INTO M06_RAIL_STATUS_EVIDENCE (PAYMENT_ID,RAIL_CODE,RAIL_MESSAGE_ID,EXTERNAL_REFERENCE,EVIDENCE_TYPE,RAIL_STATUS,REASON_CODE,EVIDENCE_HASH,EVIDENCE_REF,OCCURRED_AT) VALUES (?,?,?,?,?,?,?,?,?,?)";
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement(sql, new String[]{"EVIDENCE_ID"});
            ps.setLong(1, paymentId); ps.setString(2, rail); ps.setString(3, request.railMessageId());
            ps.setString(4, request.externalReference()); ps.setString(5, request.evidenceType());
            ps.setString(6, request.railStatus()); ps.setString(7, request.reasonCode());
            ps.setBytes(8, hash); ps.setString(9, request.evidenceRef());
            ps.setObject(10, request.occurredAt()); return ps;
        }, key);
        return key.getKey().longValue();
    }

    /** Returns a duplicate observation for a safe callback replay. */
    public List<Long> evidenceByHash(String rail, byte[] hash) {
        return jdbc.query("SELECT EVIDENCE_ID FROM M06_RAIL_STATUS_EVIDENCE WHERE RAIL_CODE=? AND EVIDENCE_HASH=?",
                (rs, row) -> rs.getLong(1), rail, hash);
    }

    /** Reads history without exposing internal write APIs. */
    public List<java.util.Map<String, Object>> history(long paymentId) {
        return jdbc.queryForList("SELECT PAYMENT_STATUS_HISTORY_ID,FROM_STATUS,TO_STATUS,ACTOR_TYPE,ACTOR_ID,REASON_CODE,EVIDENCE_ID,LEDGER_JOURNAL_ID,TREASURY_ENTRY_ID,CHANGED_AT FROM M06_PAYMENT_STATUS_HISTORY WHERE PAYMENT_ID=? ORDER BY PAYMENT_STATUS_HISTORY_ID", paymentId);
    }

    /** Reads evidence IDs and statuses used by the operations UI. */
    public List<java.util.Map<String, Object>> evidenceList(long paymentId) {
        return jdbc.queryForList("SELECT EVIDENCE_ID,RAIL_MESSAGE_ID,EXTERNAL_REFERENCE,EVIDENCE_TYPE,RAIL_STATUS,REASON_CODE,EVIDENCE_REF,OCCURRED_AT,RECEIVED_AT FROM M06_RAIL_STATUS_EVIDENCE WHERE PAYMENT_ID=? ORDER BY EVIDENCE_ID", paymentId);
    }

    /** A scoped request lookup result. */
    public record ExistingRequest(long id, byte[] hash) { }
    /** Transition obligations as configured by the installer. */
    public record TransitionRule(boolean rail, boolean ledger) { }
}

