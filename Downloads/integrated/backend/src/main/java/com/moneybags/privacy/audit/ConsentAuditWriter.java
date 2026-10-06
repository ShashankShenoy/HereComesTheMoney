package com.moneybags.privacy.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.privacy.common.RequestContext;
import com.moneybags.privacy.consent.ConsentDecision;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Persists decision evidence in the same transaction as consent and outbox rows. */
@Component
public class ConsentAuditWriter {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditRetentionPolicy retention;

    public ConsentAuditWriter(JdbcClient jdbc, ObjectMapper json, AuditRetentionPolicy retention) {
        this.jdbc = jdbc;
        this.json = json;
        this.retention = retention;
    }

    /** Appends a minimized audit row; a missing approved schedule rolls the decision back. */
    public void write(ConsentDecision decision, RequestContext context) {
        try {
            var metadata = json.writeValueAsString(Map.of("operation", "CONSENT_DECISION"));
            var fingerprint = json.writeValueAsString(decision);
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(fingerprint.getBytes(StandardCharsets.UTF_8)));
            jdbc.sql("""
                    INSERT INTO M09_PRIVACY_AUDIT_EVENT
                    (AUDIT_EVENT_ID,SOURCE_SERVICE,SOURCE_STREAM,SOURCE_EVENT_ID,EVENT_TYPE,
                     OCCURRED_AT,OUTCOME,ACTOR_EXTERNAL_ID,SESSION_EXTERNAL_ID,PURPOSE_CODE,
                     SUBJECT_EXTERNAL_ID,RESOURCE_TYPE,RESOURCE_EXTERNAL_ID,CONSENT_DECISION_ID,
                     CORRELATION_ID,EVENT_METADATA,EVENT_SHA256,RETENTION_UNTIL,CREATED_AT,CREATED_BY)
                    VALUES (:id,'M09_PRIVACY','CONSENT_HISTORY',:decisionId,:type,:occurred,
                            'SUCCESS',:actor,:session,:purpose,:subject,'CONSENT_DECISION',:decisionId,
                            :decisionId,:correlation,:metadata,:hash,:retention,SYSTIMESTAMP,:actor)
                    """).param("id", UUID.randomUUID().toString()).param("decisionId", decision.id())
                    .param("type", "Consent" + decision.decision().name())
                    .param("occurred", decision.decidedAt()).param("actor", context.actorId())
                    .param("session", context.sessionId()).param("purpose", decision.purposeCode())
                    .param("subject", decision.subjectExternalId()).param("correlation", context.correlationId())
                    .param("metadata", metadata).param("hash", hash)
                    .param("retention", retention.until(decision.decidedAt())).update();
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Could not build consent audit evidence", failure);
        }
    }
}
