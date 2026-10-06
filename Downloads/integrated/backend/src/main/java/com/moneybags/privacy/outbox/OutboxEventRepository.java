package com.moneybags.privacy.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/** Persists integration events in the same Oracle transaction as each aggregate change. */
@Repository
public class OutboxEventRepository {
    private final JdbcClient jdbc;

    public OutboxEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Appends an event whose payload is already serialized and classified for external use. */
    public void append(OutboxEvent event) {
        jdbc.sql("""
                INSERT INTO M09_PRIVACY_OUTBOX_EVENT
                  (OUTBOX_EVENT_ID, AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, EVENT_VERSION,
                   EVENT_PAYLOAD, IDEMPOTENCY_KEY, CORRELATION_ID, ATTEMPT_COUNT,
                   CREATED_AT, CREATED_BY, UPDATED_AT, VERSION_NO)
                VALUES (:id, :aggregateType, :aggregateId, :eventType, :eventVersion,
                        :payload, :idempotencyKey, :correlationId, 0,
                        :createdAt, :createdBy, :createdAt, 1)
                """)
                .param("id", event.id())
                .param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId())
                .param("eventType", event.eventType())
                .param("eventVersion", event.eventVersion())
                .param("payload", event.payload())
                .param("idempotencyKey", event.idempotencyKey())
                .param("correlationId", event.correlationId())
                .param("createdAt", event.createdAt())
                .param("createdBy", event.createdBy())
                .update();
    }

    /** Locks a small batch so concurrent publisher instances do not publish the same row. */
    public List<OutboxEvent> lockPending(int batchSize) {
        return jdbc.sql("""
                SELECT OUTBOX_EVENT_ID, AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, EVENT_VERSION,
                       EVENT_PAYLOAD, IDEMPOTENCY_KEY, CORRELATION_ID, CREATED_AT, CREATED_BY
                  FROM M09_PRIVACY_OUTBOX_EVENT
                 WHERE PUBLISHED_AT IS NULL AND ATTEMPT_COUNT < 20 AND ROWNUM <= :batchSize
                 FOR UPDATE SKIP LOCKED
                """)
                .param("batchSize", batchSize)
                .query((rs, rowNum) -> new OutboxEvent(
                        rs.getString("OUTBOX_EVENT_ID"), rs.getString("AGGREGATE_TYPE"),
                        rs.getString("AGGREGATE_ID"), rs.getString("EVENT_TYPE"),
                        rs.getInt("EVENT_VERSION"), rs.getString("EVENT_PAYLOAD"),
                        rs.getString("IDEMPOTENCY_KEY"), rs.getString("CORRELATION_ID"),
                        rs.getObject("CREATED_AT", OffsetDateTime.class), rs.getString("CREATED_BY")))
                .list();
    }

    /** Marks a published event only after Kafka acknowledges it. */
    public void markPublished(String eventId) {
        jdbc.sql("UPDATE M09_PRIVACY_OUTBOX_EVENT SET PUBLISHED_AT=SYSTIMESTAMP, UPDATED_AT=SYSTIMESTAMP WHERE OUTBOX_EVENT_ID=:id")
                .param("id", eventId).update();
    }

    /** Records a bounded error message and increments the retry counter. */
    public void markFailed(String eventId, String error) {
        var safeError = error == null ? "Unknown publish failure" : error.substring(0, Math.min(1000, error.length()));
        jdbc.sql("""
                UPDATE M09_PRIVACY_OUTBOX_EVENT
                   SET ATTEMPT_COUNT=ATTEMPT_COUNT+1, LAST_ERROR=:error, UPDATED_AT=SYSTIMESTAMP
                 WHERE OUTBOX_EVENT_ID=:id
                """).param("error", safeError).param("id", eventId).update();
    }

    /** Durable event row used by the publisher adapter. */
    public record OutboxEvent(String id, String aggregateType, String aggregateId, String eventType,
                              int eventVersion, String payload, String idempotencyKey,
                              String correlationId, OffsetDateTime createdAt, String createdBy) {}
}
