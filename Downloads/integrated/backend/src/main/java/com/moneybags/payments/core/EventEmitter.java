package com.moneybags.payments.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Writes durable integration events and security audit records in the caller's Oracle transaction. */
@Component
public class EventEmitter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Receives persistence and canonical JSON serialization dependencies. */
    public EventEmitter(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    /** Appends a versioned event for CDC or an outbox publisher to deliver at least once. */
    public void emit(String eventType, long paymentId, String correlationId, Map<String, ?> payload) {
        try {
            UUID uuid = UUID.randomUUID();
            byte[] id = ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
            jdbc.update("INSERT INTO M06_OUTBOX_EVENT (EVENT_ID,EVENT_TYPE,SCHEMA_VERSION,AGGREGATE_TYPE,AGGREGATE_ID,CORRELATION_ID,PARTITION_KEY,PAYLOAD_JSON,OCCURRED_AT) VALUES (?,?,1,'PAYMENT',?,?,?,?,SYSTIMESTAMP)",
                    id, eventType, Long.toString(paymentId), correlationId, Long.toString(paymentId), mapper.writeValueAsString(payload));
        } catch (JsonProcessingException error) { throw new IllegalStateException("Cannot serialize payment event", error); }
    }

    /** Records an IAM actor's command outcome alongside the aggregate mutation. */
    public void audit(String action, long paymentId, String result, String reason) {
        jdbc.update("INSERT INTO M06_SECURITY_AUDIT_EVENT (ACTOR_ID,ACTION_CODE,RESOURCE_TYPE,RESOURCE_ID,RESULT_CODE,REASON_CODE) VALUES (?,?,'PAYMENT',?,?,?)",
                Actor.id(), action, Long.toString(paymentId), result, reason);
    }
}

