package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04AccountProductVersionHistoryRow(
    @DbColumn(name="ADOPTION_ID", generated=true, identity=true, nullable=false) BigDecimal adoptionId,
    @DbColumn(name="ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal accountId,
    @DbColumn(name="FROM_PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal fromProductVersionId,
    @DbColumn(name="TO_PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal toProductVersionId,
    @DbColumn(name="VERSION_TREATMENT_ID", generated=false, identity=false, nullable=false) BigDecimal versionTreatmentId,
    @DbColumn(name="TREATMENT_EVENT_ID", generated=false, identity=false, nullable=false) String treatmentEventId,
    @DbColumn(name="CONSENT_REFERENCE", generated=false, identity=false, nullable=true) String consentReference,
    @DbColumn(name="REASON_CODE", generated=false, identity=false, nullable=false) String reasonCode,
    @DbColumn(name="ADOPTED_BY_USER_ID", generated=false, identity=false, nullable=false) String adoptedByUserId,
    @DbColumn(name="ADOPTED_AT", generated=false, identity=false, nullable=false) OffsetDateTime adoptedAt,
    @DbColumn(name="CORRELATION_ID", generated=false, identity=false, nullable=false) String correlationId
) {}
