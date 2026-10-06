package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmApprovalRow(
    @DbColumn(name="APPROVAL_ID", generated=true, identity=true, nullable=false) BigDecimal approvalId,
    @DbColumn(name="PRODUCT_ID", generated=false, identity=false, nullable=false) BigDecimal productId,
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=true) BigDecimal productVersionId,
    @DbColumn(name="ACTION_CODE", generated=false, identity=false, nullable=false) String actionCode,
    @DbColumn(name="REQUEST_STATUS", generated=false, identity=false, nullable=false) String requestStatus,
    @DbColumn(name="MAKER_USER_ID", generated=false, identity=false, nullable=false) String makerUserId,
    @DbColumn(name="MAKER_SESSION_ID", generated=false, identity=false, nullable=false) String makerSessionId,
    @DbColumn(name="CHECKER_USER_ID", generated=false, identity=false, nullable=true) String checkerUserId,
    @DbColumn(name="CHECKER_SESSION_ID", generated=false, identity=false, nullable=true) String checkerSessionId,
    @DbColumn(name="CONTENT_HASH", generated=false, identity=false, nullable=false) String contentHash,
    @DbColumn(name="REASON", generated=false, identity=false, nullable=false) String reason,
    @DbColumn(name="IMPACT_SUMMARY", generated=false, identity=false, nullable=true) String impactSummary,
    @DbColumn(name="CORRELATION_ID", generated=false, identity=false, nullable=false) String correlationId,
    @DbColumn(name="SUBMITTED_AT", generated=false, identity=false, nullable=false) OffsetDateTime submittedAt,
    @DbColumn(name="DECIDED_AT", generated=false, identity=false, nullable=true) OffsetDateTime decidedAt,
    @DbColumn(name="APPROVED_EFFECTIVE_FROM_AT", generated=false, identity=false, nullable=true) OffsetDateTime approvedEffectiveFromAt,
    @DbColumn(name="APPROVED_EFFECTIVE_TO_AT", generated=false, identity=false, nullable=true) OffsetDateTime approvedEffectiveToAt,
    @DbColumn(name="CHECKER_COMMENT", generated=false, identity=false, nullable=true) String checkerComment,
    @DbColumn(name="ROW_VERSION", generated=false, identity=false, nullable=false) Long rowVersion
) {}
