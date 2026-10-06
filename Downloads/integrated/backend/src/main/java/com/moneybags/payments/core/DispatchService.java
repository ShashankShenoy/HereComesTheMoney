package com.moneybags.payments.core;

import com.moneybags.payments.api.ApiException;
import com.moneybags.payments.api.Contracts;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists rail work and attempts; an external adapter owns actual network transmission. */
@Service
public class DispatchService {
    private final JdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final EventEmitter events;

    /** Receives the local work queue and event writer. */
    public DispatchService(JdbcTemplate jdbc, PaymentRepository payments, EventEmitter events) {
        this.jdbc = jdbc; this.payments = payments; this.events = events;
    }

    /** Creates one stable rail message after a posted outbound debit. */
    @Transactional
    public Map<String, Object> prepare(long paymentId, Contracts.PrepareDispatch request) {
        Contracts.Payment payment = payments.lock(paymentId);
        if (!payment.direction().equals("OUTBOUND") || !payment.status().equals("DEBIT_POSTED"))
            throw conflict("DISPATCH_NOT_READY", "Only a posted outbound payment can be dispatched");
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT DISPATCH_ID,PAYMENT_ID,RAIL_CODE,RAIL_MESSAGE_ID,PAYLOAD_REF,DISPATCH_STATUS,ATTEMPT_COUNT FROM M06_RAIL_DISPATCH WHERE PAYMENT_ID=?", paymentId);
        if (!existing.isEmpty()) {
            if (!request.railMessageId().equals(existing.get(0).get("RAIL_MESSAGE_ID")) || !request.payloadRef().equals(existing.get(0).get("PAYLOAD_REF")))
                throw conflict("DISPATCH_CONFLICT", "Dispatch is already prepared with different details");
            return existing.get(0);
        }
        if (payment.railMessageId() != null && !payment.railMessageId().equals(request.railMessageId()))
            throw conflict("RAIL_MESSAGE_MISMATCH", "Payment already has a different rail message");
        jdbc.update("INSERT INTO M06_RAIL_DISPATCH (PAYMENT_ID,RAIL_CODE,RAIL_MESSAGE_ID,PAYLOAD_REF,DISPATCH_STATUS) VALUES (?,?,?,?,'READY')",
                paymentId, payment.railCode(), request.railMessageId(), request.payloadRef());
        jdbc.update("UPDATE M06_PAYMENT_INSTRUCTION SET RAIL_MESSAGE_ID=? WHERE PAYMENT_ID=?", request.railMessageId(), paymentId);
        events.emit("M06.RailDispatchReady", paymentId, null, Map.of("paymentId", paymentId, "railMessageId", request.railMessageId(), "payloadRef", request.payloadRef()));
        events.audit("PREPARE_DISPATCH", paymentId, "SUCCESS", null);
        return jdbc.queryForMap("SELECT DISPATCH_ID,PAYMENT_ID,RAIL_CODE,RAIL_MESSAGE_ID,PAYLOAD_REF,DISPATCH_STATUS,ATTEMPT_COUNT FROM M06_RAIL_DISPATCH WHERE PAYMENT_ID=?", paymentId);
    }

    /** Records an adapter outcome and forces inquiry after an uncertain send. */
    @Transactional
    public Map<String, Object> attempt(long dispatchId, Contracts.DispatchAttempt request) {
        Map<String, Object> dispatch = jdbc.queryForMap("SELECT DISPATCH_ID,PAYMENT_ID,DISPATCH_STATUS,ATTEMPT_COUNT FROM M06_RAIL_DISPATCH WHERE DISPATCH_ID=? FOR UPDATE", dispatchId);
        String current = (String) dispatch.get("DISPATCH_STATUS");
        if (current.equals("ACKNOWLEDGED") || current.equals("FAILED")) throw conflict("DISPATCH_CLOSED", "Dispatch has a final outcome");
        if (current.equals("INQUIRY_REQUIRED") && !request.attemptType().equals("INQUIRY"))
            throw conflict("INQUIRY_REQUIRED", "A timed-out send must be investigated before retry");
        if (request.attemptType().equals("SEND") && !current.equals("READY") && !current.equals("SENDING"))
            throw conflict("SEND_NOT_ALLOWED", "Send attempt is not allowed from this dispatch state");
        int attemptNo = ((Number) dispatch.get("ATTEMPT_COUNT")).intValue() + 1;
        String next = switch (request.outcome()) {
            case "STARTED" -> "SENDING";
            case "SENT" -> "SENT";
            case "ACKNOWLEDGED" -> "ACKNOWLEDGED";
            case "TIMEOUT", "ERROR" -> "INQUIRY_REQUIRED";
            case "REJECTED" -> "FAILED";
            default -> throw conflict("INVALID_OUTCOME", "Unsupported attempt outcome");
        };
        jdbc.update("INSERT INTO M06_RAIL_DISPATCH_ATTEMPT (DISPATCH_ID,ATTEMPT_NO,ATTEMPT_TYPE,OUTCOME_CODE,TRANSPORT_REFERENCE,ERROR_CODE,STARTED_AT,COMPLETED_AT) VALUES (?,?,?,?,?,?,SYSTIMESTAMP,CASE WHEN ?='STARTED' THEN NULL ELSE SYSTIMESTAMP END)",
                dispatchId, attemptNo, request.attemptType(), request.outcome(), request.transportReference(), request.errorCode(), request.outcome());
        jdbc.update("UPDATE M06_RAIL_DISPATCH SET DISPATCH_STATUS=?,ATTEMPT_COUNT=?,LAST_ATTEMPT_AT=SYSTIMESTAMP,LAST_ERROR_CODE=?,FIRST_DISPATCHED_AT=CASE WHEN ?='SENT' THEN COALESCE(FIRST_DISPATCHED_AT,SYSTIMESTAMP) ELSE FIRST_DISPATCHED_AT END WHERE DISPATCH_ID=?",
                next, attemptNo, request.errorCode(), request.outcome(), dispatchId);
        long paymentId = ((Number) dispatch.get("PAYMENT_ID")).longValue();
        events.emit("M06.RailDispatchAttemptRecorded", paymentId, null, Map.of("paymentId", paymentId, "dispatchId", dispatchId, "attemptNo", attemptNo, "outcome", request.outcome()));
        events.audit("RECORD_DISPATCH_ATTEMPT", paymentId, "SUCCESS", request.errorCode());
        return jdbc.queryForMap("SELECT DISPATCH_ID,PAYMENT_ID,DISPATCH_STATUS,ATTEMPT_COUNT,LAST_ATTEMPT_AT,LAST_ERROR_CODE FROM M06_RAIL_DISPATCH WHERE DISPATCH_ID=?", dispatchId);
    }

    /** Lists bounded adapter work; the queue itself is durable in Oracle. */
    public List<Map<String, Object>> queue(String status) {
        return jdbc.queryForList("SELECT DISPATCH_ID,PAYMENT_ID,RAIL_CODE,RAIL_MESSAGE_ID,PAYLOAD_REF,DISPATCH_STATUS,ATTEMPT_COUNT FROM M06_RAIL_DISPATCH WHERE DISPATCH_STATUS=? ORDER BY CREATED_AT FETCH FIRST 100 ROWS ONLY", status);
    }

    /** Lists attempts for incident investigation. */
    public List<Map<String, Object>> attempts(long dispatchId) {
        return jdbc.queryForList("SELECT DISPATCH_ATTEMPT_ID,ATTEMPT_NO,ATTEMPT_TYPE,OUTCOME_CODE,TRANSPORT_REFERENCE,ERROR_CODE,STARTED_AT,COMPLETED_AT FROM M06_RAIL_DISPATCH_ATTEMPT WHERE DISPATCH_ID=? ORDER BY ATTEMPT_NO", dispatchId);
    }

    /** Creates a stable conflict response. */
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}

