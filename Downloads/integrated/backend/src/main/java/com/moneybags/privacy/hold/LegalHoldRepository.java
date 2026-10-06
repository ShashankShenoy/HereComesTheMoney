package com.moneybags.privacy.hold;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Oracle adapter for scoped holds and hold checks. */
@Repository
public class LegalHoldRepository {
    private final JdbcClient jdbc;

    public LegalHoldRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Opens an approval-pending hold with a stable idempotency key. */
    public void insert(LegalHold hold, String correlationId, String idempotencyKey) {
        jdbc.sql("""
                INSERT INTO M09_PRIVACY_LEGAL_HOLD
                  (LEGAL_HOLD_ID,HOLD_REFERENCE,HOLD_STATUS,AUTHORITY_REFERENCE,REASON,
                   SUBJECT_SERVICE,SUBJECT_EXTERNAL_ID,TARGET_SERVICE,TARGET_RESOURCE_TYPE,
                   TARGET_EXTERNAL_ID,RECORD_CATEGORY,RECORD_FROM_AT,RECORD_TO_AT,
                   REVIEW_DUE_AT,CREATED_AT,CREATED_BY,CORRELATION_ID,IDEMPOTENCY_KEY)
                VALUES (:id,:reference,:status,:authority,:reason,:subjectService,:subjectId,
                        :targetService,:resourceType,:resourceId,:category,:recordFrom,:recordTo,
                        :reviewDue,SYSTIMESTAMP,:actor,:correlation,:idempotency)
                """).param("id", hold.id()).param("reference", hold.reference())
                .param("status", hold.status().name()).param("authority", hold.authorityReference())
                .param("reason", hold.reason()).param("subjectService", hold.subjectService())
                .param("subjectId", hold.subjectExternalId()).param("targetService", hold.targetService())
                .param("resourceType", hold.targetResourceType()).param("resourceId", hold.targetExternalId())
                .param("category", hold.recordCategory()).param("recordFrom", hold.recordFromAt())
                .param("recordTo", hold.recordToAt()).param("reviewDue", hold.reviewDueAt())
                .param("actor", hold.createdBy()).param("correlation", correlationId)
                .param("idempotency", idempotencyKey).update();
    }

    /** Finds one hold for review and audit. */
    public Optional<LegalHold> findById(String id) {
        return jdbc.sql("SELECT * FROM M09_PRIVACY_LEGAL_HOLD WHERE LEGAL_HOLD_ID=:id")
                .param("id", id).query(this::map).optional();
    }

    /** Finds a prior request so a client retry does not create a second hold. */
    public Optional<LegalHold> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT * FROM M09_PRIVACY_LEGAL_HOLD WHERE IDEMPOTENCY_KEY=:key")
                .param("key", key).query(this::map).optional();
    }

    /** Lists holds without exposing protected reason text in broad search responses. */
    public List<LegalHold> findAll(String status, int offset, int size) {
        return jdbc.sql("""
                SELECT * FROM M09_PRIVACY_LEGAL_HOLD
                 WHERE (:status IS NULL OR HOLD_STATUS=:status)
                 ORDER BY CREATED_AT DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY
                """).param("status", status).param("offset", offset).param("size", size)
                .query(this::map).list();
    }

    /** Counts the filtered hold queue. */
    public long count(String status) {
        return jdbc.sql("SELECT COUNT(*) FROM M09_PRIVACY_LEGAL_HOLD WHERE (:status IS NULL OR HOLD_STATUS=:status)")
                .param("status", status).query(Long.class).single();
    }

    /** Activates only a pending hold approved by someone other than the maker. */
    public int activate(String id, String checker) {
        return jdbc.sql("""
                UPDATE M09_PRIVACY_LEGAL_HOLD
                   SET HOLD_STATUS='ACTIVE', STARTED_AT=SYSTIMESTAMP, APPROVED_BY=:checker
                 WHERE LEGAL_HOLD_ID=:id AND HOLD_STATUS='PENDING' AND CREATED_BY<>:checker
                """).param("checker", checker).param("id", id).update();
    }

    /** Records a release request while the hold remains active and blocks disposition. */
    public int requestRelease(String id, String releaser, String authorityReference) {
        return jdbc.sql("""
                UPDATE M09_PRIVACY_LEGAL_HOLD
                   SET RELEASED_BY=:releaser, RELEASE_AUTHORITY_REF=:authority
                 WHERE LEGAL_HOLD_ID=:id AND HOLD_STATUS='ACTIVE' AND RELEASED_BY IS NULL
                """).param("releaser", releaser)
                .param("authority", authorityReference).param("id", id).update();
    }

    /** Authenticated checker completes the release; the maker cannot approve it. */
    public int approveRelease(String id, String checker) {
        return jdbc.sql("""
                UPDATE M09_PRIVACY_LEGAL_HOLD
                   SET HOLD_STATUS='RELEASED', RELEASED_AT=SYSTIMESTAMP,
                       RELEASE_APPROVED_BY=:checker
                 WHERE LEGAL_HOLD_ID=:id AND HOLD_STATUS='ACTIVE'
                   AND RELEASED_BY IS NOT NULL AND RELEASED_BY<>:checker
                """).param("checker", checker).param("id", id).update();
    }

    /** Checks the full intersection of subject, resource, category and record time. */
    public boolean hasActiveMatch(HoldTarget target) {
        var found = jdbc.sql("""
                SELECT COUNT(*) FROM M09_PRIVACY_LEGAL_HOLD
                 WHERE HOLD_STATUS='ACTIVE'
                   AND (SUBJECT_EXTERNAL_ID IS NULL OR
                        (SUBJECT_SERVICE=:subjectService AND SUBJECT_EXTERNAL_ID=:subjectId))
                   AND (TARGET_EXTERNAL_ID IS NULL OR
                        (TARGET_SERVICE=:targetService AND TARGET_RESOURCE_TYPE=:resourceType
                         AND TARGET_EXTERNAL_ID=:resourceId))
                   AND (RECORD_CATEGORY IS NULL OR RECORD_CATEGORY=:category)
                   AND (:recordAt IS NULL OR RECORD_FROM_AT IS NULL OR RECORD_FROM_AT<=:recordAt)
                   AND (:recordAt IS NULL OR RECORD_TO_AT IS NULL OR RECORD_TO_AT>=:recordAt)
                """).param("subjectService", target.subjectService()).param("subjectId", target.subjectExternalId())
                .param("targetService", target.targetService()).param("resourceType", target.resourceType())
                .param("resourceId", target.resourceExternalId()).param("category", target.recordCategory())
                .param("recordAt", target.recordAt()).query(Long.class).single();
        return found > 0;
    }

    private LegalHold map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new LegalHold(rs.getString("LEGAL_HOLD_ID"), rs.getString("HOLD_REFERENCE"),
                LegalHold.HoldStatus.valueOf(rs.getString("HOLD_STATUS")),
                rs.getString("AUTHORITY_REFERENCE"), rs.getString("REASON"),
                rs.getString("SUBJECT_SERVICE"), rs.getString("SUBJECT_EXTERNAL_ID"),
                rs.getString("TARGET_SERVICE"), rs.getString("TARGET_RESOURCE_TYPE"),
                rs.getString("TARGET_EXTERNAL_ID"), rs.getString("RECORD_CATEGORY"),
                rs.getObject("RECORD_FROM_AT", OffsetDateTime.class),
                rs.getObject("RECORD_TO_AT", OffsetDateTime.class),
                rs.getObject("STARTED_AT", OffsetDateTime.class),
                rs.getObject("REVIEW_DUE_AT", OffsetDateTime.class),
                rs.getObject("RELEASED_AT", OffsetDateTime.class),
                rs.getString("CREATED_BY"), rs.getString("APPROVED_BY"),
                rs.getString("RELEASED_BY"), rs.getString("RELEASE_APPROVED_BY"),
                rs.getString("RELEASE_AUTHORITY_REF"));
    }

    /** Candidate supplied by the record owner before any purge or de-identification. */
    public record HoldTarget(String subjectService, String subjectExternalId,
                             String targetService, String resourceType, String resourceExternalId,
                             String recordCategory, OffsetDateTime recordAt) {}
}
