package com.moneybags.privacy.casework;

import java.time.OffsetDateTime;
import java.util.List;

/** A rights, correction, retention or regulatory workflow routed to data owners. */
public record ComplianceCase(
        String id, String reference, CaseType type, CaseStatus status, Priority priority,
        String requesterService, String requesterExternalId, String requesterAuthorityRef,
        String subjectService, String subjectExternalId, String purposeId,
        String targetService, String targetResourceType, String targetExternalId,
        String reason, OffsetDateTime identityVerifiedAt, OffsetDateTime dueAt,
        OffsetDateTime responseDeliveredAt, OffsetDateTime closedAt, long versionNo,
        List<CaseTask> tasks) {

    public enum CaseType { ACCESS_REQUEST, CORRECTION_REQUEST, ERASURE_REQUEST, RESTRICTION_REQUEST,
        RETENTION_REVIEW, PRIVILEGED_ACCESS_REVIEW, REGULATORY_INQUIRY }
    public enum CaseStatus { OPEN, IDENTITY_PENDING, IN_REVIEW, WAITING_ON_SERVICE, RESPONSE_READY, CLOSED, REJECTED }
    public enum Priority { LOW, NORMAL, HIGH, CRITICAL }
    public enum TaskStatus { PENDING, DISPATCHED, IN_PROGRESS, COMPLETED, FAILED, EXEMPTED }

    /** Work assigned to one authoritative Moneybags service. */
    public record CaseTask(String id, String caseId, String targetService, String actionCode,
                           TaskStatus status, String targetResourceType, String targetExternalId,
                           String responseReference, String failureReason, OffsetDateTime completedAt,
                           long versionNo) {}
}
