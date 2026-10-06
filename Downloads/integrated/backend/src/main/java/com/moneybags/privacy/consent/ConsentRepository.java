package com.moneybags.privacy.consent;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Oracle adapter for append-only consent decisions and current-state evaluation. */
@Repository
public class ConsentRepository {
    private final JdbcClient jdbc;

    public ConsentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Allocates the next subject/purpose sequence while the caller holds the transaction. */
    public long nextDecisionNo(String subjectService, String subjectId, String purposeId) {
        // Oracle cannot lock an aggregate result. The purpose row is a stable
        // lock target; it serializes decisions for that purpose version.
        jdbc.sql("SELECT PROCESSING_PURPOSE_ID FROM M09_PRIVACY_PROCESSING_PURPOSE WHERE PROCESSING_PURPOSE_ID=:id FOR UPDATE")
                .param("id", purposeId).query(String.class).single();
        return jdbc.sql("""
                SELECT COALESCE(MAX(DECISION_NO),0)+1
                  FROM M09_PRIVACY_CONSENT_HISTORY
                 WHERE SUBJECT_SERVICE=:service AND SUBJECT_EXTERNAL_ID=:subject AND PROCESSING_PURPOSE_ID=:purpose
                """).param("service", subjectService).param("subject", subjectId).param("purpose", purposeId)
                .query(Long.class).single();
    }

    /** Appends one decision; duplicate idempotency keys are handled by the service. */
    public void insert(ConsentDecision decision, String idempotencyKey, String createdBy) throws DuplicateKeyException {
        jdbc.sql("""
                INSERT INTO M09_PRIVACY_CONSENT_HISTORY
                  (CONSENT_DECISION_ID,PROCESSING_PURPOSE_ID,SUBJECT_SERVICE,SUBJECT_EXTERNAL_ID,
                   SOURCE_CONSENT_ID,DECISION_NO,DECISION,CHANNEL_CODE,DECIDED_AT,EFFECTIVE_AT,EXPIRES_AT,
                   ACTOR_EXTERNAL_ID,AUTH_STRENGTH_CODE,NOTICE_SHA256,EVIDENCE_URI,EVIDENCE_SHA256,
                   CORRELATION_ID,IDEMPOTENCY_KEY,CREATED_AT,CREATED_BY)
                VALUES (:id,:purpose,:subjectService,:subjectId,:sourceConsentId,:decisionNo,:decision,:channel,
                        :decidedAt,:effectiveAt,:expiresAt,:actor,:strength,:noticeHash,:evidenceUri,
                        :evidenceHash,:correlation,:idempotency,SYSTIMESTAMP,:createdBy)
                """)
                .param("id", decision.id()).param("purpose", decision.purposeId())
                .param("subjectService", decision.subjectService()).param("subjectId", decision.subjectExternalId())
                .param("sourceConsentId", decision.sourceConsentId())
                .param("decisionNo", decision.decisionNo()).param("decision", decision.decision().name())
                .param("channel", decision.channelCode()).param("decidedAt", decision.decidedAt())
                .param("effectiveAt", decision.effectiveAt()).param("expiresAt", decision.expiresAt())
                .param("actor", decision.actorExternalId()).param("strength", decision.authenticationStrength())
                .param("noticeHash", decision.noticeSha256()).param("evidenceUri", decision.evidenceUri())
                .param("evidenceHash", decision.evidenceSha256()).param("correlation", decision.correlationId())
                .param("idempotency", idempotencyKey).param("createdBy", createdBy).update();
    }

    /** Returns the decision produced by a previously completed idempotent request. */
    public Optional<ConsentDecision> findByIdempotencyKey(String key) {
        return jdbc.sql(selectSql() + " WHERE C.IDEMPOTENCY_KEY=:key")
                .param("key", key).query(this::map).optional();
    }

    /** Returns complete immutable history for a subject and purpose code. */
    public List<ConsentDecision> history(String subjectService, String subjectId, String purposeCode) {
        return jdbc.sql(selectSql() + """
                 WHERE C.SUBJECT_SERVICE=:service AND C.SUBJECT_EXTERNAL_ID=:subject
                   AND P.PURPOSE_CODE=:purposeCode
                 ORDER BY C.EFFECTIVE_AT DESC, C.DECISION_NO DESC
                """).param("service", subjectService).param("subject", subjectId)
                .param("purposeCode", purposeCode).query(this::map).list();
    }

    /** Retrieves the latest decision for the currently active purpose version. */
    public Optional<ConsentDecision> current(String subjectService, String subjectId,
                                             String purposeCode, OffsetDateTime at) {
        return jdbc.sql(selectSql() + """
                 WHERE C.SUBJECT_SERVICE=:service AND C.SUBJECT_EXTERNAL_ID=:subject
                   AND P.PURPOSE_CODE=:purposeCode AND P.STATUS='ACTIVE'
                   AND P.EFFECTIVE_FROM<=:at AND (P.EFFECTIVE_TO IS NULL OR P.EFFECTIVE_TO>:at)
                   AND P.PURPOSE_VERSION=(
                       SELECT MAX(P2.PURPOSE_VERSION) FROM M09_PRIVACY_PROCESSING_PURPOSE P2
                        WHERE P2.PURPOSE_CODE=:purposeCode AND P2.STATUS='ACTIVE'
                          AND P2.EFFECTIVE_FROM<=:at AND (P2.EFFECTIVE_TO IS NULL OR P2.EFFECTIVE_TO>:at))
                   AND C.EFFECTIVE_AT<=:at
                 ORDER BY C.DECISION_NO DESC
                 FETCH FIRST 1 ROW ONLY
                """).param("service", subjectService).param("subject", subjectId)
                .param("purposeCode", purposeCode).param("at", at).query(this::map).optional();
    }

    private String selectSql() {
        return """
                SELECT C.*, P.PURPOSE_CODE
                  FROM M09_PRIVACY_CONSENT_HISTORY C
                  JOIN M09_PRIVACY_PROCESSING_PURPOSE P
                    ON P.PROCESSING_PURPOSE_ID=C.PROCESSING_PURPOSE_ID
                """;
    }

    private ConsentDecision map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ConsentDecision(rs.getString("CONSENT_DECISION_ID"), rs.getString("PROCESSING_PURPOSE_ID"),
                rs.getString("PURPOSE_CODE"), rs.getString("SUBJECT_SERVICE"),
                rs.getString("SUBJECT_EXTERNAL_ID"), rs.getString("SOURCE_CONSENT_ID"), rs.getLong("DECISION_NO"),
                ConsentDecision.Decision.valueOf(rs.getString("DECISION")), rs.getString("CHANNEL_CODE"),
                rs.getObject("DECIDED_AT", OffsetDateTime.class), rs.getObject("EFFECTIVE_AT", OffsetDateTime.class),
                rs.getObject("EXPIRES_AT", OffsetDateTime.class), rs.getString("ACTOR_EXTERNAL_ID"),
                rs.getString("AUTH_STRENGTH_CODE"), rs.getString("NOTICE_SHA256"),
                rs.getString("EVIDENCE_URI"), rs.getString("EVIDENCE_SHA256"), rs.getString("CORRELATION_ID"));
    }
}
