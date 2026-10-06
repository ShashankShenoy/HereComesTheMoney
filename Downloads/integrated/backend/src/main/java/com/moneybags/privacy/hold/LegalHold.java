package com.moneybags.privacy.hold;

import java.time.OffsetDateTime;

/** Scoped preservation instruction that overrides automated disposition. */
public record LegalHold(
        String id, String reference, HoldStatus status, String authorityReference, String reason,
        String subjectService, String subjectExternalId, String targetService,
        String targetResourceType, String targetExternalId, String recordCategory,
        OffsetDateTime recordFromAt, OffsetDateTime recordToAt, OffsetDateTime startedAt,
        OffsetDateTime reviewDueAt, OffsetDateTime releasedAt, String createdBy,
        String approvedBy, String releasedBy, String releaseApprovedBy,
        String releaseAuthorityReference) {

    public enum HoldStatus { PENDING, ACTIVE, REJECTED, RELEASED }
}
