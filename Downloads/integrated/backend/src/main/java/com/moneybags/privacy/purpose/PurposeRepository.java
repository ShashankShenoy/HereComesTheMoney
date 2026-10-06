package com.moneybags.privacy.purpose;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Oracle adapter for processing-purpose queries and lifecycle changes. */
@Repository
public class PurposeRepository {
    private final JdbcClient jdbc;

    public PurposeRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Returns purposes in stable code/version order for API paging. */
    public List<ProcessingPurpose> findAll(String status, int offset, int size) {
        var sql = """
                SELECT * FROM M09_PRIVACY_PROCESSING_PURPOSE
                 WHERE (:status IS NULL OR STATUS=:status)
                 ORDER BY PURPOSE_CODE, PURPOSE_VERSION DESC
                 OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY
                """;
        return jdbc.sql(sql).param("status", status).param("offset", offset).param("size", size)
                .query(this::map).list();
    }

    /** Counts rows using the same list filter. */
    public long count(String status) {
        return jdbc.sql("SELECT COUNT(*) FROM M09_PRIVACY_PROCESSING_PURPOSE WHERE (:status IS NULL OR STATUS=:status)")
                .param("status", status).query(Long.class).single();
    }

    /** Finds an exact immutable purpose version. */
    public Optional<ProcessingPurpose> findById(String id) {
        return jdbc.sql("SELECT * FROM M09_PRIVACY_PROCESSING_PURPOSE WHERE PROCESSING_PURPOSE_ID=:id")
                .param("id", id).query(this::map).optional();
    }

    /** Finds the active version used for a new consent decision. */
    public Optional<ProcessingPurpose> findCurrentActive(String code, OffsetDateTime at) {
        return jdbc.sql("""
                SELECT * FROM M09_PRIVACY_PROCESSING_PURPOSE
                 WHERE PURPOSE_CODE=:code AND STATUS='ACTIVE' AND EFFECTIVE_FROM<=:at
                   AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>:at)
                 ORDER BY PURPOSE_VERSION DESC FETCH FIRST 1 ROW ONLY
                """).param("code", code).param("at", at).query(this::map).optional();
    }

    /** Inserts a draft version; approved content is never updated in place. */
    public void insert(ProcessingPurpose purpose, String actorId) {
        jdbc.sql("""
                INSERT INTO M09_PRIVACY_PROCESSING_PURPOSE
                  (PROCESSING_PURPOSE_ID,PURPOSE_CODE,PURPOSE_VERSION,PURPOSE_NAME,OWNER_SERVICE,
                   LEGAL_BASIS_CODE,NOTICE_URI,NOTICE_SHA256,STATUS,EFFECTIVE_FROM,EFFECTIVE_TO,
                   REVIEW_DUE_AT,CREATED_AT,CREATED_BY,UPDATED_AT,UPDATED_BY,VERSION_NO)
                VALUES (:id,:code,:version,:name,:owner,:basis,:noticeUri,:noticeHash,:status,
                        :effectiveFrom,:effectiveTo,:reviewDue,SYSTIMESTAMP,:actor,SYSTIMESTAMP,:actor,1)
                """)
                .param("id", purpose.id()).param("code", purpose.code()).param("version", purpose.version())
                .param("name", purpose.name()).param("owner", purpose.ownerService())
                .param("basis", purpose.legalBasisCode()).param("noticeUri", purpose.noticeUri())
                .param("noticeHash", purpose.noticeSha256()).param("status", purpose.status().name())
                .param("effectiveFrom", purpose.effectiveFrom()).param("effectiveTo", purpose.effectiveTo())
                .param("reviewDue", purpose.reviewDueAt()).param("actor", actorId).update();
    }

    /** Activates a submitted version with optimistic locking and maker-checker evidence. */
    public int approve(String id, long expectedVersion, String checkerId) {
        return jdbc.sql("""
                UPDATE M09_PRIVACY_PROCESSING_PURPOSE
                   SET STATUS='ACTIVE', APPROVED_AT=SYSTIMESTAMP, APPROVED_BY=:checker,
                       UPDATED_AT=SYSTIMESTAMP, UPDATED_BY=:checker, VERSION_NO=VERSION_NO+1
                 WHERE PROCESSING_PURPOSE_ID=:id AND STATUS IN ('DRAFT','PENDING_APPROVAL')
                   AND CREATED_BY<>:checker AND VERSION_NO=:expectedVersion
                """).param("checker", checkerId).param("id", id)
                .param("expectedVersion", expectedVersion).update();
    }

    private ProcessingPurpose map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProcessingPurpose(rs.getString("PROCESSING_PURPOSE_ID"), rs.getString("PURPOSE_CODE"),
                rs.getInt("PURPOSE_VERSION"), rs.getString("PURPOSE_NAME"), rs.getString("OWNER_SERVICE"),
                rs.getString("LEGAL_BASIS_CODE"), rs.getString("NOTICE_URI"), rs.getString("NOTICE_SHA256"),
                ProcessingPurpose.PurposeStatus.valueOf(rs.getString("STATUS")),
                rs.getObject("EFFECTIVE_FROM", OffsetDateTime.class), rs.getObject("EFFECTIVE_TO", OffsetDateTime.class),
                rs.getObject("REVIEW_DUE_AT", OffsetDateTime.class), rs.getObject("APPROVED_AT", OffsetDateTime.class),
                rs.getString("APPROVED_BY"), rs.getLong("VERSION_NO"));
    }
}
