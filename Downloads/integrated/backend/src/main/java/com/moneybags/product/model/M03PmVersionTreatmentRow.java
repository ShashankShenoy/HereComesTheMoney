package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmVersionTreatmentRow(
    @DbColumn(name="VERSION_TREATMENT_ID", generated=true, identity=true, nullable=false) BigDecimal versionTreatmentId,
    @DbColumn(name="PRODUCT_ID", generated=false, identity=false, nullable=false) BigDecimal productId,
    @DbColumn(name="SOURCE_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal sourceVersionId,
    @DbColumn(name="TARGET_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal targetVersionId,
    @DbColumn(name="TREATMENT_CODE", generated=false, identity=false, nullable=false) String treatmentCode,
    @DbColumn(name="MIGRATION_FROM_AT", generated=false, identity=false, nullable=true) OffsetDateTime migrationFromAt,
    @DbColumn(name="CONSENT_REQUIRED", generated=false, identity=false, nullable=false) String consentRequired,
    @DbColumn(name="APPROVAL_ID", generated=false, identity=false, nullable=false) BigDecimal approvalId
) {}
