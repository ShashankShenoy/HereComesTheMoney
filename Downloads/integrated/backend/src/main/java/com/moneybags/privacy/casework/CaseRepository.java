package com.moneybags.privacy.casework;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Oracle adapter for compliance cases and their per-service tasks. */
@Repository
public class CaseRepository {
    private final JdbcClient jdbc;

    public CaseRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Inserts a new case before its tasks are created in the same transaction. */
    public void insert(ComplianceCase value, String idempotencyKey, String correlationId, String actorId) {
        jdbc.sql("""
                INSERT INTO M09_PRIVACY_COMPLIANCE_CASE
                  (COMPLIANCE_CASE_ID,CASE_REFERENCE,CASE_TYPE,CASE_STATUS,PRIORITY,
                   REQUESTER_SERVICE,REQUESTER_EXTERNAL_ID,REQUESTER_AUTHORITY_REF,
                   SUBJECT_SERVICE,SUBJECT_EXTERNAL_ID,PROCESSING_PURPOSE_ID,TARGET_SERVICE,
                   TARGET_RESOURCE_TYPE,TARGET_EXTERNAL_ID,REASON,DUE_AT,CORRELATION_ID,
                   IDEMPOTENCY_KEY,CREATED_AT,CREATED_BY,UPDATED_AT,UPDATED_BY,VERSION_NO)
                VALUES (:id,:reference,:type,:status,:priority,:requesterService,:requesterId,:authority,
                        :subjectService,:subjectId,:purpose,:targetService,:resourceType,:resourceId,
                        :reason,:dueAt,:correlation,:idempotency,SYSTIMESTAMP,:actor,SYSTIMESTAMP,:actor,1)
                """).param("id", value.id()).param("reference", value.reference())
                .param("type", value.type().name()).param("status", value.status().name())
                .param("priority", value.priority().name()).param("requesterService", value.requesterService())
                .param("requesterId", value.requesterExternalId()).param("authority", value.requesterAuthorityRef())
                .param("subjectService", value.subjectService()).param("subjectId", value.subjectExternalId())
                .param("purpose", value.purposeId()).param("targetService", value.targetService())
                .param("resourceType", value.targetResourceType()).param("resourceId", value.targetExternalId())
                .param("reason", value.reason()).param("dueAt", value.dueAt())
                .param("correlation", correlationId).param("idempotency", idempotencyKey)
                .param("actor", actorId).update();
    }

    /** Adds one independently retryable task for an authoritative service. */
    public void insertTask(ComplianceCase.CaseTask task, String idempotencyKey, String actorId) {
        jdbc.sql("""
                INSERT INTO M09_PRIVACY_COMPLIANCE_CASE_TASK
                  (CASE_TASK_ID,COMPLIANCE_CASE_ID,TARGET_SERVICE,ACTION_CODE,TASK_STATUS,
                   TARGET_RESOURCE_TYPE,TARGET_EXTERNAL_ID,IDEMPOTENCY_KEY,
                   CREATED_AT,CREATED_BY,UPDATED_AT,UPDATED_BY,VERSION_NO)
                VALUES (:id,:caseId,:service,:action,:status,:resourceType,:resourceId,:idempotency,
                        SYSTIMESTAMP,:actor,SYSTIMESTAMP,:actor,1)
                """).param("id", task.id()).param("caseId", task.caseId()).param("service", task.targetService())
                .param("action", task.actionCode()).param("status", task.status().name())
                .param("resourceType", task.targetResourceType()).param("resourceId", task.targetExternalId())
                .param("idempotency", idempotencyKey).param("actor", actorId).update();
    }

    /** Finds a case and then loads its tasks without creating cross-service joins. */
    public Optional<ComplianceCase> findById(String id) {
        var value = jdbc.sql("SELECT * FROM M09_PRIVACY_COMPLIANCE_CASE WHERE COMPLIANCE_CASE_ID=:id")
                .param("id", id).query((rs, n) -> mapCase(rs, List.of())).optional();
        return value.map(c -> withTasks(c, findTasks(id)));
    }

    /** Resolves a retry to the existing case without creating duplicate tasks. */
    public Optional<ComplianceCase> findByIdempotencyKey(String key) {
        var id = jdbc.sql("SELECT COMPLIANCE_CASE_ID FROM M09_PRIVACY_COMPLIANCE_CASE WHERE IDEMPOTENCY_KEY=:key")
                .param("key", key).query(String.class).optional();
        return id.flatMap(this::findById);
    }

    /** Lists cases for the operational queue. */
    public List<ComplianceCase> findAll(String status, int offset, int size) {
        return jdbc.sql("""
                SELECT * FROM M09_PRIVACY_COMPLIANCE_CASE
                 WHERE (:status IS NULL OR CASE_STATUS=:status)
                 ORDER BY CREATED_AT DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY
                """).param("status", status).param("offset", offset).param("size", size)
                .query((rs, n) -> mapCase(rs, List.of())).list();
    }

    /** Counts queue items with the same status filter. */
    public long count(String status) {
        return jdbc.sql("SELECT COUNT(*) FROM M09_PRIVACY_COMPLIANCE_CASE WHERE (:status IS NULL OR CASE_STATUS=:status)")
                .param("status", status).query(Long.class).single();
    }

    /** Marks identity verification and makes tasks eligible for dispatch. */
    public int verifyIdentity(String id, long expectedVersion, String reviewer) {
        return jdbc.sql("""
                UPDATE M09_PRIVACY_COMPLIANCE_CASE
                   SET IDENTITY_VERIFIED_AT=SYSTIMESTAMP, REVIEWED_AT=SYSTIMESTAMP, REVIEWED_BY=:reviewer,
                       CASE_STATUS='IN_REVIEW', UPDATED_AT=SYSTIMESTAMP, UPDATED_BY=:reviewer,
                       VERSION_NO=VERSION_NO+1
                 WHERE COMPLIANCE_CASE_ID=:id AND CASE_STATUS='IDENTITY_PENDING' AND VERSION_NO=:version
                """).param("reviewer", reviewer).param("id", id).param("version", expectedVersion).update();
    }

    /** Completes or fails a task using optimistic locking. */
    public int completeTask(String taskId, long expectedVersion, ComplianceCase.TaskStatus status,
                            String responseReference, String failureReason, String actorId) {
        return jdbc.sql("""
                UPDATE M09_PRIVACY_COMPLIANCE_CASE_TASK
                   SET TASK_STATUS=:status, RESPONSE_REFERENCE=:response, FAILURE_REASON=:failure,
                       COMPLETED_AT=SYSTIMESTAMP, UPDATED_AT=SYSTIMESTAMP, UPDATED_BY=:actor,
                       VERSION_NO=VERSION_NO+1
                 WHERE CASE_TASK_ID=:id AND VERSION_NO=:version
                   AND TASK_STATUS IN ('PENDING','DISPATCHED','IN_PROGRESS')
                   AND EXISTS (SELECT 1 FROM M09_PRIVACY_COMPLIANCE_CASE C
                                WHERE C.COMPLIANCE_CASE_ID=M09_PRIVACY_COMPLIANCE_CASE_TASK.COMPLIANCE_CASE_ID
                                  AND C.IDENTITY_VERIFIED_AT IS NOT NULL)
                """).param("status", status.name()).param("response", responseReference)
                .param("failure", failureReason).param("actor", actorId).param("id", taskId)
                .param("version", expectedVersion).update();
    }

    /** Closes only a case whose service tasks have reached terminal states. */
    public int close(String id, long expectedVersion, String actorId) {
        return jdbc.sql("""
                UPDATE M09_PRIVACY_COMPLIANCE_CASE C
                   SET CASE_STATUS='CLOSED', CLOSED_AT=SYSTIMESTAMP, UPDATED_AT=SYSTIMESTAMP,
                       UPDATED_BY=:actor, VERSION_NO=VERSION_NO+1
                 WHERE C.COMPLIANCE_CASE_ID=:id AND C.VERSION_NO=:version
                   AND C.IDENTITY_VERIFIED_AT IS NOT NULL AND C.CASE_STATUS<>'CLOSED'
                   AND NOT EXISTS (SELECT 1 FROM M09_PRIVACY_COMPLIANCE_CASE_TASK T
                                    WHERE T.COMPLIANCE_CASE_ID=C.COMPLIANCE_CASE_ID
                                      AND T.TASK_STATUS NOT IN ('COMPLETED','EXEMPTED'))
                """).param("actor", actorId).param("id", id).param("version", expectedVersion).update();
    }

    private List<ComplianceCase.CaseTask> findTasks(String caseId) {
        return jdbc.sql("SELECT * FROM M09_PRIVACY_COMPLIANCE_CASE_TASK WHERE COMPLIANCE_CASE_ID=:id ORDER BY CREATED_AT")
                .param("id", caseId).query((rs, n) -> new ComplianceCase.CaseTask(
                        rs.getString("CASE_TASK_ID"), rs.getString("COMPLIANCE_CASE_ID"),
                        rs.getString("TARGET_SERVICE"), rs.getString("ACTION_CODE"),
                        ComplianceCase.TaskStatus.valueOf(rs.getString("TASK_STATUS")),
                        rs.getString("TARGET_RESOURCE_TYPE"), rs.getString("TARGET_EXTERNAL_ID"),
                        rs.getString("RESPONSE_REFERENCE"), rs.getString("FAILURE_REASON"),
                        rs.getObject("COMPLETED_AT", OffsetDateTime.class), rs.getLong("VERSION_NO"))).list();
    }

    private ComplianceCase withTasks(ComplianceCase c, List<ComplianceCase.CaseTask> tasks) {
        return new ComplianceCase(c.id(), c.reference(), c.type(), c.status(), c.priority(),
                c.requesterService(), c.requesterExternalId(), c.requesterAuthorityRef(),
                c.subjectService(), c.subjectExternalId(), c.purposeId(), c.targetService(),
                c.targetResourceType(), c.targetExternalId(), c.reason(), c.identityVerifiedAt(),
                c.dueAt(), c.responseDeliveredAt(), c.closedAt(), c.versionNo(), tasks);
    }

    private ComplianceCase mapCase(java.sql.ResultSet rs, List<ComplianceCase.CaseTask> tasks)
            throws java.sql.SQLException {
        return new ComplianceCase(rs.getString("COMPLIANCE_CASE_ID"), rs.getString("CASE_REFERENCE"),
                ComplianceCase.CaseType.valueOf(rs.getString("CASE_TYPE")),
                ComplianceCase.CaseStatus.valueOf(rs.getString("CASE_STATUS")),
                ComplianceCase.Priority.valueOf(rs.getString("PRIORITY")),
                rs.getString("REQUESTER_SERVICE"), rs.getString("REQUESTER_EXTERNAL_ID"),
                rs.getString("REQUESTER_AUTHORITY_REF"), rs.getString("SUBJECT_SERVICE"),
                rs.getString("SUBJECT_EXTERNAL_ID"), rs.getString("PROCESSING_PURPOSE_ID"),
                rs.getString("TARGET_SERVICE"), rs.getString("TARGET_RESOURCE_TYPE"),
                rs.getString("TARGET_EXTERNAL_ID"), rs.getString("REASON"),
                rs.getObject("IDENTITY_VERIFIED_AT", OffsetDateTime.class),
                rs.getObject("DUE_AT", OffsetDateTime.class),
                rs.getObject("RESPONSE_DELIVERED_AT", OffsetDateTime.class),
                rs.getObject("CLOSED_AT", OffsetDateTime.class), rs.getLong("VERSION_NO"), tasks);
    }
}
