package com.moneybags.privacy.consent;

import java.time.OffsetDateTime;

/** Append-only decision captured against one exact purpose and notice version. */
public record ConsentDecision(
        String id, String purposeId, String purposeCode, String subjectService,
        String subjectExternalId, String sourceConsentId, long decisionNo, Decision decision, String channelCode,
        OffsetDateTime decidedAt, OffsetDateTime effectiveAt, OffsetDateTime expiresAt,
        String actorExternalId, String authenticationStrength, String noticeSha256,
        String evidenceUri, String evidenceSha256, String correlationId) {

    public enum Decision { GRANTED, DENIED, WITHDRAWN, EXPIRED, SUPERSEDED }
}
