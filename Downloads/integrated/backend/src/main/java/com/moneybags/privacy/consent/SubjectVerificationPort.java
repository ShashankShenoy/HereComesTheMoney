package com.moneybags.privacy.consent;

/** Port implemented by the CIF/IAM adapter before a person can change consent. */
public interface SubjectVerificationPort {
    /** Confirms that the authenticated actor may act for the subject in this channel. */
    VerificationResult verify(String subjectService, String subjectExternalId,
                              String actorId, String channelCode, String correlationId);

    record VerificationResult(boolean verified, String authenticationStrength, String evidenceReference) {}
}
