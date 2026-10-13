package com.moneybags.cif.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M02CifConsentRow(
    @DbColumn(name="CONSENT_ID", generated=false, identity=false, nullable=false) String consentId,
    @DbColumn(name="CIF_ID", generated=false, identity=false, nullable=false) String cifId,
    @DbColumn(name="PURPOSE_CODE", generated=false, identity=false, nullable=false) String purposeCode,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="CAPTURE_CHANNEL", generated=false, identity=false, nullable=false) String captureChannel,
    @DbColumn(name="CAPTURED_AT", generated=false, identity=false, nullable=false) OffsetDateTime capturedAt,
    @DbColumn(name="WITHDRAWN_AT", generated=false, identity=false, nullable=true) OffsetDateTime withdrawnAt,
    @DbColumn(name="EVIDENCE_REF", generated=false, identity=false, nullable=true) String evidenceRef,
    @DbColumn(name="PRIVACY_PURPOSE_ID", generated=false, identity=false, nullable=true) String privacyPurposeId,
    @DbColumn(name="PRIVACY_DECISION_ID", generated=false, identity=false, nullable=true) String privacyDecisionId,
    @DbColumn(name="PRIVACY_DECISION_NO", generated=false, identity=false, nullable=true) BigDecimal privacyDecisionNo
) {}
