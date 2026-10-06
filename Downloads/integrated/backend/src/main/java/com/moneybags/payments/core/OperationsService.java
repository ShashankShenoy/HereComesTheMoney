package com.moneybags.payments.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.payments.api.ApiException;
import com.moneybags.payments.api.Contracts;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns clearing aggregates, evidence reconciliation, exception queues and maker-checker approval. */
@Service
public class OperationsService {
    private final JdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final IntegrationChecks checks;
    private final EventEmitter events;
    private final ObjectMapper mapper;
    private final String suspenseGlCode;

    /** Receives persistence, cross-module evidence checks and the configured suspense GL code. */
    public OperationsService(JdbcTemplate jdbc, PaymentRepository payments, IntegrationChecks checks,
                             EventEmitter events, ObjectMapper mapper,
                             @Value("${moneybags.reconciliation.suspense-gl-code:}") String suspenseGlCode) {
        this.jdbc = jdbc; this.payments = payments; this.checks = checks; this.events = events;
        this.mapper = mapper; this.suspenseGlCode = suspenseGlCode;
    }

    /** Creates an OPEN batch; the rail and external reference are unique. */
    @Transactional
    public Map<String, Object> createBatch(Contracts.CreateBatch request) {
        jdbc.update("INSERT INTO M06_CLEARING_BATCH (RAIL_CODE,BATCH_REFERENCE,BATCH_STATUS,BATCH_DIRECTION) VALUES (?,?,'OPEN',?)",
                request.railCode(), request.batchReference(), request.direction());
        return jdbc.queryForMap("SELECT * FROM M06_CLEARING_BATCH WHERE RAIL_CODE=? AND BATCH_REFERENCE=?", request.railCode(), request.batchReference());
    }

    /** Adds one accepted payment, updating gross and net totals under a batch lock. */
    @Transactional
    public Map<String, Object> addBatchItem(long batchId, Contracts.AddBatchItem request) {
        Map<String, Object> batch = jdbc.queryForMap("SELECT * FROM M06_CLEARING_BATCH WHERE CLEARING_BATCH_ID=? FOR UPDATE", batchId);
        Contracts.Payment payment = payments.lock(request.paymentId());
        if (!"OPEN".equals(batch.get("BATCH_STATUS")) || !payment.railCode().equals(batch.get("RAIL_CODE"))
                || !List.of("ACCEPTED", "SETTLEMENT_PENDING").contains(payment.status())
                || payment.clearingBatchId() != null)
            throw conflict("BATCH_ITEM_NOT_ELIGIBLE", "Batch and payment rail or state do not match");
        String direction = (String) batch.get("BATCH_DIRECTION");
        if (!direction.equals("NET") && !direction.equals(payment.direction()))
            throw conflict("BATCH_DIRECTION_MISMATCH", "Payment direction differs from batch direction");
        String side = payment.direction().equals("OUTBOUND") ? "OUT" : "IN";
        jdbc.update("INSERT INTO M06_CLEARING_BATCH_ITEM (CLEARING_BATCH_ID,PAYMENT_ID,MOVEMENT_SIDE,ITEM_STATUS,AMOUNT,EXTERNAL_ITEM_REF) VALUES (?,?,?,'ACCEPTED',?,?)",
                batchId, payment.paymentId(), side, payment.amount(), request.externalItemRef());
        jdbc.update("UPDATE M06_PAYMENT_INSTRUCTION SET CLEARING_BATCH_ID=? WHERE PAYMENT_ID=?", batchId, payment.paymentId());
        jdbc.update("UPDATE M06_CLEARING_BATCH SET ITEM_COUNT=ITEM_COUNT+1,TOTAL_AMOUNT=TOTAL_AMOUNT+?,GROSS_OUT_AMOUNT=GROSS_OUT_AMOUNT+?,GROSS_IN_AMOUNT=GROSS_IN_AMOUNT+? WHERE CLEARING_BATCH_ID=?",
                payment.amount(), side.equals("OUT") ? payment.amount() : BigDecimal.ZERO,
                side.equals("IN") ? payment.amount() : BigDecimal.ZERO, batchId);
        refreshNet(batchId);
        events.emit("M06.ClearingItemAdded", payment.paymentId(), null, Map.of("paymentId", payment.paymentId(), "batchId", batchId));
        return batch(batchId);
    }

    /** Closes an OPEN batch after the caller has reviewed its totals. */
    @Transactional
    public Map<String, Object> closeBatch(long batchId) {
        int changed = jdbc.update("UPDATE M06_CLEARING_BATCH SET BATCH_STATUS='CLOSED',CLOSED_AT=SYSTIMESTAMP WHERE CLEARING_BATCH_ID=? AND BATCH_STATUS='OPEN' AND ITEM_COUNT>0", batchId);
        if (changed != 1) throw conflict("BATCH_NOT_CLOSABLE", "Batch is empty or no longer open");
        return batch(batchId);
    }

    /** Links a Module 7 cycle only when rail, batch and gross amounts match. */
    @Transactional
    public Map<String, Object> linkSettlement(long batchId, Contracts.BatchSettlement request) {
        Map<String, Object> batch = jdbc.queryForMap("SELECT * FROM M06_CLEARING_BATCH WHERE CLEARING_BATCH_ID=? FOR UPDATE", batchId);
        if (!List.of("CLOSED", "SUBMITTED", "SETTLEMENT_PENDING").contains(batch.get("BATCH_STATUS")))
            throw conflict("BATCH_NOT_CLOSED", "Batch must be closed before settlement linking");
        List<Map<String, Object>> cycles = jdbc.queryForList("SELECT SETTLEMENT_CYCLE_ID,STATUS FROM M07_SETTLEMENT_CYCLE WHERE SETTLEMENT_CYCLE_ID=? AND CLEARING_BATCH_ID=? AND RAIL_CODE=? AND GROSS_OUT_AMOUNT=? AND GROSS_IN_AMOUNT=?",
                request.settlementCycleId(), batchId, batch.get("RAIL_CODE"), batch.get("GROSS_OUT_AMOUNT"), batch.get("GROSS_IN_AMOUNT"));
        if (cycles.size() != 1) throw conflict("SETTLEMENT_CYCLE_MISMATCH", "Module 7 cycle does not match this clearing batch");
        String status = "SETTLED".equals(cycles.get(0).get("STATUS")) ? "SETTLED" : "SETTLEMENT_PENDING";
        jdbc.update("UPDATE M06_CLEARING_BATCH SET SETTLEMENT_CYCLE_ID=?,BATCH_STATUS=?,SETTLED_AT=CASE WHEN ?='SETTLED' THEN SYSTIMESTAMP ELSE NULL END WHERE CLEARING_BATCH_ID=?",
                request.settlementCycleId(), status, status, batchId);
        jdbc.update("UPDATE M06_PAYMENT_INSTRUCTION SET SETTLEMENT_CYCLE_ID=? WHERE CLEARING_BATCH_ID=?",
                request.settlementCycleId(), batchId);
        jdbc.update("UPDATE M06_CLEARING_BATCH_ITEM SET ITEM_STATUS=? WHERE CLEARING_BATCH_ID=? AND ITEM_STATUS IN ('ACCEPTED','SETTLEMENT_PENDING')",
                status, batchId);
        return batch(batchId);
    }

    /** Reads one clearing batch and its aggregate totals. */
    public Map<String, Object> batch(long batchId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM M06_CLEARING_BATCH WHERE CLEARING_BATCH_ID=?", batchId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND", "Batch does not exist");
        return rows.get(0);
    }

    /** Lists the most recent 100 clearing batches. */
    public List<Map<String, Object>> batches() {
        return jdbc.queryForList("SELECT * FROM M06_CLEARING_BATCH ORDER BY OPENED_AT DESC FETCH FIRST 100 ROWS ONLY");
    }

    /** Lists items in a single clearing batch. */
    public List<Map<String, Object>> batchItems(long batchId) {
        batch(batchId);
        return jdbc.queryForList("SELECT * FROM M06_CLEARING_BATCH_ITEM WHERE CLEARING_BATCH_ID=? ORDER BY CLEARING_BATCH_ITEM_ID", batchId);
    }

    /** Compares all four evidence classes, appends a run and updates the current summary. */
    @Transactional
    public Map<String, Object> reconcile(long paymentId, Contracts.Reconcile request) {
        Contracts.Payment payment = payments.lock(paymentId);
        long version = jdbc.query("SELECT CHECK_VERSION FROM M06_PAYMENT_RECONCILIATION WHERE PAYMENT_ID=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), paymentId).stream().findFirst().orElse(0L) + 1;
        Long customerJournal = payment.direction().equals("OUTBOUND") ? request.customerJournalId() : request.customerJournalId();
        boolean customer = journalMatches(paymentId, customerJournal, payment.amount(), "PAYMENT_PROVISIONAL");
        boolean suspense = !suspenseGlCode.isBlank() && customerJournal != null && count(
                "SELECT COUNT(*) FROM M05_GL_POSTING p JOIN M05_GL_ACCOUNT a ON a.GL_ACCOUNT_ID=p.GL_ACCOUNT_ID WHERE p.JOURNAL_ID=? AND a.GL_CODE=? AND p.AMOUNT=?",
                customerJournal, suspenseGlCode, payment.amount()) == 1;
        boolean railScoped = request.railEvidenceId() != null && count(
                "SELECT COUNT(*) FROM M06_RAIL_STATUS_EVIDENCE WHERE EVIDENCE_ID=? AND PAYMENT_ID=?",
                request.railEvidenceId(), paymentId) == 1;
        boolean rail = railScoped && count(
                "SELECT COUNT(*) FROM M06_RAIL_STATUS_EVIDENCE WHERE EVIDENCE_ID=? AND PAYMENT_ID=? AND RAIL_STATUS='SETTLED'",
                request.railEvidenceId(), paymentId) == 1;
        boolean reserve = request.treasuryEntryId() != null && count(
                "SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER t JOIN M07_SETTLEMENT_EVIDENCE e ON e.EVIDENCE_ID=t.EVIDENCE_ID AND e.RESERVE_ACCOUNT_ID=t.RESERVE_ACCOUNT_ID JOIN M06_PAYMENT_INSTRUCTION i ON i.PAYMENT_ID=t.PAYMENT_ID WHERE t.TREASURY_ENTRY_ID=? AND t.PAYMENT_ID=? AND t.AMOUNT=? AND t.RAIL_CODE=i.RAIL_CODE AND t.MOVEMENT_SIDE=CASE WHEN i.PAYMENT_DIRECTION='OUTBOUND' THEN 'OUT' ELSE 'IN' END AND e.EVIDENCE_STATUS='VERIFIED' AND t.EXTERNAL_SETTLEMENT_REF=?",
                request.treasuryEntryId(), paymentId, payment.amount(), request.externalSettlementRef()) == 1;
        boolean amount = payment.amount().compareTo(request.reconciledAmount()) == 0;
        String result = suspenseGlCode.isBlank() ? "EXCEPTION" : customer && suspense && rail && reserve && amount ? "MATCHED" : "MISMATCH";
        String detail = json(Map.of("customerPostingMatch", customer, "suspenseMatch", suspense,
                "railEvidenceMatch", rail, "reserveMatch", reserve, "amountMatch", amount,
                "suspenseConfigurationPresent", !suspenseGlCode.isBlank()));
        jdbc.update("INSERT INTO M06_PAYMENT_RECONCILIATION_RUN (PAYMENT_ID,CHECK_VERSION,RESULT_STATUS,CUSTOMER_POSTING_MATCH,SUSPENSE_MATCH,RAIL_EVIDENCE_MATCH,RESERVE_MATCH,DETAIL_JSON) VALUES (?,?,?,?,?,?,?,?)",
                paymentId, version, result, flag(customer), flag(suspense), flag(rail), flag(reserve), detail);
        int updated = jdbc.update("UPDATE M06_PAYMENT_RECONCILIATION SET RECONCILIATION_STATUS=?,CUSTOMER_JOURNAL_ID=?,REFUND_JOURNAL_ID=?,SETTLEMENT_JOURNAL_ID=?,RAIL_EVIDENCE_ID=?,TREASURY_ENTRY_ID=?,SETTLEMENT_CYCLE_ID=?,EXTERNAL_SETTLEMENT_REF=?,RECONCILED_AMOUNT=?,CHECK_VERSION=?,LAST_CHECKED_AT=SYSTIMESTAMP,RECONCILED_AT=CASE WHEN ?='MATCHED' THEN SYSTIMESTAMP ELSE NULL END WHERE PAYMENT_ID=?",
                result, request.customerJournalId(), request.refundJournalId(), request.settlementJournalId(),
                railScoped ? request.railEvidenceId() : null, request.treasuryEntryId(), request.settlementCycleId(),
                request.externalSettlementRef(), request.reconciledAmount(), version, result, paymentId);
        if (updated == 0)
            jdbc.update("INSERT INTO M06_PAYMENT_RECONCILIATION (PAYMENT_ID,RECONCILIATION_STATUS,CUSTOMER_JOURNAL_ID,REFUND_JOURNAL_ID,SETTLEMENT_JOURNAL_ID,RAIL_EVIDENCE_ID,TREASURY_ENTRY_ID,SETTLEMENT_CYCLE_ID,EXTERNAL_SETTLEMENT_REF,EXPECTED_AMOUNT,RECONCILED_AMOUNT,CHECK_VERSION,RECONCILED_AT) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CASE WHEN ?='MATCHED' THEN SYSTIMESTAMP ELSE NULL END)",
                    paymentId, result, request.customerJournalId(), request.refundJournalId(), request.settlementJournalId(),
                    railScoped ? request.railEvidenceId() : null, request.treasuryEntryId(), request.settlementCycleId(), request.externalSettlementRef(),
                    payment.amount(), request.reconciledAmount(), version, result);
        if (!result.equals("MATCHED")) openMismatch(paymentId, result, detail);
        events.emit("M06.PaymentReconciled", paymentId, null, Map.of("paymentId", paymentId, "checkVersion", version, "result", result));
        return reconciliation(paymentId);
    }

    /** Reads the latest summary for one payment. */
    public Map<String, Object> reconciliation(long paymentId) {
        payments.get(paymentId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM M06_PAYMENT_RECONCILIATION WHERE PAYMENT_ID=?", paymentId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "RECONCILIATION_NOT_FOUND", "Payment has not been reconciled");
        return rows.get(0);
    }

    /** Reads immutable reconciliation attempts in version order. */
    public List<Map<String, Object>> reconciliationRuns(long paymentId) {
        payments.get(paymentId);
        return jdbc.queryForList("SELECT * FROM M06_PAYMENT_RECONCILIATION_RUN WHERE PAYMENT_ID=? ORDER BY CHECK_VERSION", paymentId);
    }

    /** Lists operational exceptions with optional state filtering. */
    public List<Map<String, Object>> exceptions(String status) {
        return status == null || status.isBlank()
                ? jdbc.queryForList("SELECT * FROM M06_RECONCILIATION_EXCEPTION ORDER BY OPENED_AT DESC FETCH FIRST 100 ROWS ONLY")
                : jdbc.queryForList("SELECT * FROM M06_RECONCILIATION_EXCEPTION WHERE STATUS=? ORDER BY OPENED_AT DESC FETCH FIRST 100 ROWS ONLY", status);
    }

    /** Assigns, resolves or waives an exception with an append-only history row. */
    @Transactional
    public Map<String, Object> exceptionAction(long exceptionId, Contracts.ExceptionAction action) {
        Map<String, Object> prior = jdbc.queryForMap("SELECT * FROM M06_RECONCILIATION_EXCEPTION WHERE EXCEPTION_ID=? FOR UPDATE", exceptionId);
        String old = (String) prior.get("STATUS");
        String next = switch (action.action()) {
            case "ASSIGN" -> "ASSIGNED";
            case "RESOLVE" -> "RESOLVED";
            case "WAIVE" -> "WAIVED";
            default -> throw conflict("INVALID_EXCEPTION_ACTION", "Action must be ASSIGN, RESOLVE or WAIVE");
        };
        if (List.of("RESOLVED", "WAIVED").contains(old)) throw conflict("EXCEPTION_CLOSED", "Exception is already closed");
        if (next.equals("ASSIGNED") && (action.ownerId() == null || action.ownerId().isBlank()))
            throw conflict("OWNER_REQUIRED", "Assignment requires an owner");
        if (!next.equals("ASSIGNED") && (action.resolutionCode() == null || action.resolutionText() == null))
            throw conflict("RESOLUTION_REQUIRED", "Closing an exception requires a code and explanation");
        jdbc.update("UPDATE M06_RECONCILIATION_EXCEPTION SET STATUS=?,OWNER_ID=COALESCE(?,OWNER_ID),RESOLUTION_CODE=?,RESOLUTION_TEXT=?,RESOLVED_AT=CASE WHEN ?='ASSIGNED' THEN NULL ELSE SYSTIMESTAMP END WHERE EXCEPTION_ID=?",
                next, action.ownerId(), action.resolutionCode(), action.resolutionText(), next, exceptionId);
        jdbc.update("INSERT INTO M06_RECONCILIATION_EXCEPTION_HISTORY (EXCEPTION_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,ACTION_CODE,REASON_TEXT) VALUES (?,?,?,?,?,?)",
                exceptionId, old, next, Actor.id(), action.action(), action.resolutionText());
        return jdbc.queryForMap("SELECT * FROM M06_RECONCILIATION_EXCEPTION WHERE EXCEPTION_ID=?", exceptionId);
    }

    /** Creates an approval task for a high-risk manual action; it does not execute that action. */
    @Transactional
    public Map<String, Object> requestApproval(Contracts.ApprovalRequest request) {
        payments.get(request.paymentId());
        if (request.exceptionId() != null && count("SELECT COUNT(*) FROM M06_RECONCILIATION_EXCEPTION WHERE EXCEPTION_ID=? AND PAYMENT_ID=?", request.exceptionId(), request.paymentId()) != 1)
            throw conflict("EXCEPTION_SCOPE_MISMATCH", "Exception does not belong to this payment");
        jdbc.update("INSERT INTO M06_PAYMENT_APPROVAL (APPROVAL_KEY,PAYMENT_ID,EXCEPTION_ID,ACTION_CODE,MAKER_ID,REASON_TEXT) VALUES (?,?,?,?,?,?)",
                request.approvalKey(), request.paymentId(), request.exceptionId(), request.actionCode(), Actor.id(), request.reasonText());
        return jdbc.queryForMap("SELECT * FROM M06_PAYMENT_APPROVAL WHERE APPROVAL_KEY=?", request.approvalKey());
    }

    /** Records an independent checker decision with database-enforced separation of duties. */
    @Transactional
    public Map<String, Object> decideApproval(long approvalId, Contracts.ApprovalDecision request) {
        Map<String, Object> prior = jdbc.queryForMap("SELECT * FROM M06_PAYMENT_APPROVAL WHERE APPROVAL_ID=? FOR UPDATE", approvalId);
        if (!"PENDING".equals(prior.get("STATUS"))) throw conflict("APPROVAL_DECIDED", "Approval is no longer pending");
        if (Actor.id().equals(prior.get("MAKER_ID"))) throw conflict("MAKER_CHECKER_CONFLICT", "Maker cannot check their own request");
        jdbc.update("UPDATE M06_PAYMENT_APPROVAL SET STATUS=?,CHECKER_ID=?,DECIDED_AT=SYSTIMESTAMP WHERE APPROVAL_ID=?",
                request.decision(), Actor.id(), approvalId);
        return jdbc.queryForMap("SELECT * FROM M06_PAYMENT_APPROVAL WHERE APPROVAL_ID=?", approvalId);
    }

    /** Lists approval tasks for operations staff. */
    public List<Map<String, Object>> approvals(String status) {
        return jdbc.queryForList("SELECT * FROM M06_PAYMENT_APPROVAL WHERE STATUS=? ORDER BY REQUESTED_AT DESC FETCH FIRST 100 ROWS ONLY", status);
    }

    /** Recomputes absolute net amount and movement side from gross totals. */
    private void refreshNet(long batchId) {
        Map<String, Object> totals = jdbc.queryForMap("SELECT GROSS_OUT_AMOUNT,GROSS_IN_AMOUNT FROM M06_CLEARING_BATCH WHERE CLEARING_BATCH_ID=?", batchId);
        BigDecimal out = (BigDecimal) totals.get("GROSS_OUT_AMOUNT");
        BigDecimal in = (BigDecimal) totals.get("GROSS_IN_AMOUNT");
        String side = out.compareTo(in) > 0 ? "OUT" : in.compareTo(out) > 0 ? "IN" : null;
        jdbc.update("UPDATE M06_CLEARING_BATCH SET NET_AMOUNT=?,NET_MOVEMENT_SIDE=? WHERE CLEARING_BATCH_ID=?", out.subtract(in).abs(), side, batchId);
    }

    /** Checks a Module 5 journal without turning a mismatch into a 500 response. */
    private boolean journalMatches(long paymentId, Long journalId, BigDecimal amount, String type) {
        return journalId != null && count("SELECT COUNT(*) FROM M05_GL_JOURNAL j JOIN M06_PAYMENT_INSTRUCTION i ON i.PAYMENT_ID=j.PAYMENT_ID WHERE j.JOURNAL_ID=? AND j.PAYMENT_ID=? AND j.JOURNAL_TYPE=? AND EXISTS (SELECT 1 FROM M05_GL_POSTING p WHERE p.JOURNAL_ID=j.JOURNAL_ID AND p.AMOUNT=? AND p.BANK_ACCOUNT_ID=CASE WHEN i.PAYMENT_DIRECTION='OUTBOUND' THEN i.SOURCE_ACCOUNT_ID ELSE i.DESTINATION_ACCOUNT_ID END AND p.ENTRY_SIDE=CASE WHEN i.PAYMENT_DIRECTION='OUTBOUND' THEN 'DR' ELSE 'CR' END)",
                journalId, paymentId, type, amount) == 1;
    }

    /** Counts matching rows through a parameterized query. */
    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    /** Opens one stable mismatch queue item without duplicating it on later runs. */
    private void openMismatch(long paymentId, String result, String detail) {
        String mismatchKey = "PAYMENT:" + paymentId + ":RECON";
        List<Map<String, Object>> prior = jdbc.queryForList("SELECT EXCEPTION_ID,STATUS FROM M06_RECONCILIATION_EXCEPTION WHERE MISMATCH_KEY=? FOR UPDATE", mismatchKey);
        if (prior.isEmpty()) {
            jdbc.update("INSERT INTO M06_RECONCILIATION_EXCEPTION (MISMATCH_KEY,PAYMENT_ID,EXCEPTION_TYPE,SEVERITY,EVIDENCE_JSON) VALUES (?,?,'PAYMENT_RECONCILIATION','HIGH',?)",
                    mismatchKey, paymentId, detail);
            long exceptionId = jdbc.queryForObject("SELECT EXCEPTION_ID FROM M06_RECONCILIATION_EXCEPTION WHERE MISMATCH_KEY=?", Long.class, mismatchKey);
            jdbc.update("INSERT INTO M06_RECONCILIATION_EXCEPTION_HISTORY (EXCEPTION_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,ACTION_CODE) VALUES (?,NULL,'OPEN',?,'RECONCILE')",
                    exceptionId, Actor.id());
        } else {
            var row = prior.get(0);
            String status = (String) row.get("STATUS");
            long exceptionId = ((Number) row.get("EXCEPTION_ID")).longValue();
            if (List.of("RESOLVED", "WAIVED").contains(status)) {
                jdbc.update("UPDATE M06_RECONCILIATION_EXCEPTION SET STATUS='OPEN',EVIDENCE_JSON=?,RESOLUTION_CODE=NULL,RESOLUTION_TEXT=NULL,RESOLVED_AT=NULL WHERE EXCEPTION_ID=?",
                        detail, exceptionId);
                jdbc.update("INSERT INTO M06_RECONCILIATION_EXCEPTION_HISTORY (EXCEPTION_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,ACTION_CODE,REASON_TEXT) VALUES (?,?,'OPEN',?,'REOPEN',?)",
                        exceptionId, status, Actor.id(), "New " + result + " reconciliation run");
            } else {
                jdbc.update("UPDATE M06_RECONCILIATION_EXCEPTION SET EVIDENCE_JSON=? WHERE EXCEPTION_ID=?", detail, exceptionId);
            }
        }
    }

    /** Serializes reconciliation flags into valid Oracle JSON. */
    private String json(Map<String, ?> data) {
        try { return mapper.writeValueAsString(data); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    /** Converts a boolean into the schema's Y/N flag. */
    private String flag(boolean yes) { return yes ? "Y" : "N"; }

    /** Creates a stable conflict response. */
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}

