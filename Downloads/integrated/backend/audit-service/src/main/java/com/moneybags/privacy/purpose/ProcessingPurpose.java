package com.moneybags.privacy.purpose;

import java.time.OffsetDateTime;

/** One immutable content version of an approved data-processing purpose. */
public record ProcessingPurpose(
        String id, String code, int version, String name, String ownerService,
        String legalBasisCode, String noticeUri, String noticeSha256, PurposeStatus status,
        OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo, OffsetDateTime reviewDueAt,
        OffsetDateTime approvedAt, String approvedBy, long versionNo) {

    public enum PurposeStatus { DRAFT, PENDING_APPROVAL, ACTIVE, SUSPENDED, RETIRED }
}
