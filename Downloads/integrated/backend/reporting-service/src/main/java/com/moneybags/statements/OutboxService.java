package com.moneybags.statements;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Persists Module 10 events in the same transaction as their source state. */
@Service
public class OutboxService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public OutboxService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    /** Inserts an idempotent event; the deployment's publisher delivers pending rows. */
    public void emit(String aggregate, String id, String type, String key, String correlation, Map<String,?> payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            jdbc.update("""
                INSERT INTO M10_STATEMENT_OUTBOX_EVENT
                 (EVENT_ID,AGGREGATE_TYPE,AGGREGATE_ID,EVENT_TYPE,SCHEMA_VERSION,
                  IDEMPOTENCY_KEY,CORRELATION_ID,PAYLOAD_JSON,PAYLOAD_HASH_SHA256)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(),aggregate,id,type,1,key,correlation,json,Hashing.sha256(json));
        } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
