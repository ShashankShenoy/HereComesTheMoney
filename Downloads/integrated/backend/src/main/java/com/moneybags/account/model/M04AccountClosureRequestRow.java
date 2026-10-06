package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04AccountClosureRequestRow(
    @DbColumn(name="CLOSURE_REQUEST_ID", generated=true, identity=true, nullable=false) BigDecimal closureRequestId,
    @DbColumn(name="ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal accountId,
    @DbColumn(name="REQUEST_ID", generated=false, identity=false, nullable=false) String requestId,
    @DbColumn(name="CLOSURE_REASON_CODE", generated=false, identity=false, nullable=false) String closureReasonCode,
    @DbColumn(name="CLOSURE_REMARKS", generated=false, identity=false, nullable=true) String closureRemarks,
    @DbColumn(name="PRE_CLOSURE_LIFECYCLE_STATUS", generated=false, identity=false, nullable=false) String preClosureLifecycleStatus,
    @DbColumn(name="REQUEST_STATUS", generated=false, identity=false, nullable=false) String requestStatus,
    @DbColumn(name="REQUESTED_BY_USER_ID", generated=false, identity=false, nullable=false) String requestedByUserId,
    @DbColumn(name="REQUESTED_AT", generated=false, identity=false, nullable=false) OffsetDateTime requestedAt,
    @DbColumn(name="DECIDED_BY_USER_ID", generated=false, identity=false, nullable=true) String decidedByUserId,
    @DbColumn(name="DECIDED_AT", generated=false, identity=false, nullable=true) OffsetDateTime decidedAt,
    @DbColumn(name="SETTLEMENT_CLEARANCE_REF", generated=false, identity=false, nullable=true) String settlementClearanceRef,
    @DbColumn(name="SETTLEMENT_CHECKED_AT", generated=false, identity=false, nullable=true) OffsetDateTime settlementCheckedAt,
    @DbColumn(name="CLEARED_ACCOUNT_ROW_VERSION", generated=false, identity=false, nullable=true) Long clearedAccountRowVersion,
    @DbColumn(name="REJECTION_REASON", generated=false, identity=false, nullable=true) String rejectionReason,
    @DbColumn(name="CORRELATION_ID", generated=false, identity=false, nullable=false) String correlationId
) {}
