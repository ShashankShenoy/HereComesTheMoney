package com.moneybags.account.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.account.api.Models.ProjectionEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Transactional audit, inbox, outbox, and Module 5 balance-projection persistence. */
@Repository
public class EventStore {
    private final JdbcClient db;
    private final ObjectMapper json;
    public EventStore(JdbcClient db, ObjectMapper json) { this.db = db; this.json = json; }

    /** Writes an account event in the same local transaction as the account change. */
    public String outbox(long accountId, String type, String correlation, Object payload) {
        String eventId = UUID.randomUUID().toString();
        db.sql("INSERT INTO M04_ACCT_OUTBOX_EVENT (EVENT_ID,EVENT_TYPE,SCHEMA_VERSION,AGGREGATE_TYPE,AGGREGATE_ID,CORRELATION_ID,PAYLOAD) VALUES (:id,:t,1,'BANK_ACCOUNT',:a,:c,:p)")
                .param("id", eventId).param("t", type).param("a", Long.toString(accountId))
                .param("c", correlation).param("p", toJson(payload)).update();
        return eventId;
    }
    /** Writes a control event whose UUID is also stored in RAW(16) control sync. */
    public void controlOutbox(String eventId, long accountId, String correlation, Object payload) {
        db.sql("INSERT INTO M04_ACCT_OUTBOX_EVENT (EVENT_ID,EVENT_TYPE,SCHEMA_VERSION,AGGREGATE_TYPE,AGGREGATE_ID,CORRELATION_ID,PAYLOAD) VALUES (:id,'ACCOUNT_CONTROL_REQUESTED',1,'BANK_ACCOUNT',:a,:c,:p)")
                .param("id", eventId).param("a", Long.toString(accountId))
                .param("c", correlation).param("p", toJson(payload)).update();
    }
    /** Appends an account audit entry; no nominee PII is written to details. */
    public void audit(long accountId, String action, String actor, String result,
                      String correlation, Object details) {
        db.sql("INSERT INTO M04_ACCOUNT_AUDIT_EVENT (AUDIT_ID,ACCOUNT_ID,ACTION_TYPE,ACTOR_USER_ID,RESULT,DETAILS_JSON,CORRELATION_ID) VALUES (:id,:a,:t,:u,:r,:d,:c)")
                .param("id", UUID.randomUUID().toString()).param("a", accountId).param("t", action)
                .param("u", actor).param("r", result).param("d", toJson(details))
                .param("c", correlation).update();
    }
    /** Returns true after a previously processed event, making event delivery idempotent. */
    public boolean processed(String eventId) {
        return db.sql("SELECT COUNT(*) FROM M04_ACCT_INBOX_EVENT WHERE EVENT_ID=:e AND PROCESSED_AT IS NOT NULL")
                .param("e", eventId).query(Long.class).single() > 0;
    }
    /** Detects a previously committed command that has no dedicated request-ID column. */
    public boolean commandRecorded(long accountId, String eventType, String requestId) {
        return db.sql("SELECT COUNT(*) FROM M04_ACCT_OUTBOX_EVENT WHERE AGGREGATE_ID=:a AND EVENT_TYPE=:t AND CORRELATION_ID=:r")
                .param("a", Long.toString(accountId)).param("t", eventType)
                .param("r", requestId).query(Long.class).single() > 0;
    }
    /** Marks a validated incoming event complete in the same transaction as its effect. */
    public void inbox(String eventId, String type, String producer, long accountId) {
        db.sql("INSERT INTO M04_ACCT_INBOX_EVENT (EVENT_ID,EVENT_TYPE,PRODUCER,AGGREGATE_ID,PROCESSED_AT) VALUES (:e,:t,:p,:a,SYSTIMESTAMP)")
                .param("e", eventId).param("t", type).param("p", producer)
                .param("a", Long.toString(accountId)).update();
    }
    /** Stores a Module 5 source event before advancing the read-only account projection. */
    public void projection(ProjectionEvent e) {
        db.sql("INSERT INTO M04_ACCOUNT_FINANCIAL_PROJECTION_EVENT (ACCOUNT_ID,SOURCE_EVENT_ID,EVENT_TYPE,SOURCE_JOURNAL_ID,SOURCE_POSTING_ID,SOURCE_TRANSACTION_ID,SOURCE_HOLD_ID,PROJECTION_VERSION,LEDGER_DELTA,BLOCKED_DELTA,LIEN_DELTA,OVERDRAFT_DELTA,LEDGER_BALANCE_AFTER,BLOCKED_BALANCE_AFTER,LIEN_BALANCE_AFTER,OVERDRAFT_LIMIT_AFTER,OCCURRED_AT,CORRELATION_ID) VALUES (:a,:id,:t,:j,:p,:tx,:h,:v,:ld,:bd,:lid,:od,:la,:ba,:lia,:oa,:at,:c)")
                .param("a", e.accountId()).param("id", WorkflowStore.rawUuid(e.eventId()))
                .param("t", e.eventType()).param("j", e.journalId()).param("p", e.postingId())
                .param("tx", e.transactionId()).param("h", e.holdId()).param("v", e.version())
                .param("ld", e.ledgerDelta()).param("bd", e.blockedDelta())
                .param("lid", e.lienDelta()).param("od", e.overdraftDelta())
                .param("la", e.ledgerAfter()).param("ba", e.blockedAfter())
                .param("lia", e.lienAfter()).param("oa", e.overdraftAfter())
                .param("at", e.occurredAt()).param("c", e.correlationId()).update();
        db.sql("UPDATE M04_BANK_ACCOUNT SET LEDGER_BALANCE=:la,BLOCKED_BALANCE=:ba,LIEN_BALANCE=:lia,OVERDRAFT_LIMIT=:oa,BALANCE_AS_OF=:at,BALANCE_SOURCE_JOURNAL_ID=COALESCE(:j,BALANCE_SOURCE_JOURNAL_ID),BALANCE_SOURCE_EVENT_ID=:id,BALANCE_SOURCE_VERSION=:v,LAST_TRANSACTION_AT=CASE WHEN :t='LEDGER_POSTED' THEN :at ELSE LAST_TRANSACTION_AT END,UPDATED_AT=SYSTIMESTAMP,ROW_VERSION=ROW_VERSION+1 WHERE ACCOUNT_ID=:a")
                .param("la", e.ledgerAfter()).param("ba", e.blockedAfter()).param("lia", e.lienAfter())
                .param("oa", e.overdraftAfter()).param("at", e.occurredAt())
                .param("j", e.journalId()).param("id", WorkflowStore.rawUuid(e.eventId()))
                .param("v", e.version()).param("t", e.eventType()).param("a", e.accountId()).update();
    }
    public record OutgoingEvent(String id, String type, String producer, int schemaVersion,
                                String aggregateType, String aggregateId, String correlationId,
                                String payload) { }
    /** Returns pending outbox rows, materializing Oracle CLOB payloads as JSON strings. */
    public List<OutgoingEvent> pendingOutbox() {
        return db.sql("SELECT * FROM M04_ACCT_OUTBOX_EVENT WHERE PUBLISHED_AT IS NULL ORDER BY ATTEMPT_COUNT,OCCURRED_AT FETCH FIRST 50 ROWS ONLY")
                .query((rs, n) -> new OutgoingEvent(rs.getString("EVENT_ID"), rs.getString("EVENT_TYPE"),
                        rs.getString("PRODUCER"), rs.getInt("SCHEMA_VERSION"),
                        rs.getString("AGGREGATE_TYPE"), rs.getString("AGGREGATE_ID"),
                        rs.getString("CORRELATION_ID"), rs.getString("PAYLOAD"))).list();
    }
    /** Records a successful publish; downstream consumers still deduplicate by EVENT_ID. */
    public void published(String eventId) {
        db.sql("UPDATE M04_ACCT_OUTBOX_EVENT SET PUBLISHED_AT=SYSTIMESTAMP,ATTEMPT_COUNT=ATTEMPT_COUNT+1 WHERE EVENT_ID=:e AND PUBLISHED_AT IS NULL")
                .param("e", eventId).update();
    }
    /** Records a retryable relay failure without discarding the event. */
    public void publishFailed(String eventId) {
        db.sql("UPDATE M04_ACCT_OUTBOX_EVENT SET ATTEMPT_COUNT=ATTEMPT_COUNT+1 WHERE EVENT_ID=:e AND PUBLISHED_AT IS NULL")
                .param("e", eventId).update();
    }
    /** Serializes versioned payloads using the application's configured JSON mapper. */
    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("Event payload cannot be serialized", ex); }
    }
}
