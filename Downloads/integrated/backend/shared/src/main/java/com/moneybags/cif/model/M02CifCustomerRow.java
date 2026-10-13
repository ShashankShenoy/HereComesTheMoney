package com.moneybags.cif.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M02CifCustomerRow(
    @DbColumn(name="CIF_ID", generated=false, identity=false, nullable=false) String cifId,
    @DbColumn(name="CIF_NUMBER", generated=false, identity=false, nullable=false) String cifNumber,
    @DbColumn(name="PARTY_ID", generated=false, identity=false, nullable=false) String partyId,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="HOME_BRANCH_REF", generated=false, identity=false, nullable=false) String homeBranchRef,
    @DbColumn(name="SEGMENT_CODE", generated=false, identity=false, nullable=true) String segmentCode,
    @DbColumn(name="KYC_STATUS", generated=false, identity=false, nullable=false) String kycStatus,
    @DbColumn(name="RISK_LEVEL", generated=false, identity=false, nullable=true) String riskLevel,
    @DbColumn(name="NEXT_REVIEW_DUE_AT", generated=false, identity=false, nullable=true) OffsetDateTime nextReviewDueAt,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt,
    @DbColumn(name="UPDATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime updatedAt,
    @DbColumn(name="ROW_VERSION", generated=false, identity=false, nullable=false) Long rowVersion
) {}
