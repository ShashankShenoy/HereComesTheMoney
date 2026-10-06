package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04AccountRestrictionRow(
    @DbColumn(name="RESTRICTION_ID", generated=true, identity=true, nullable=false) BigDecimal restrictionId,
    @DbColumn(name="ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal accountId,
    @DbColumn(name="REQUEST_ID", generated=false, identity=false, nullable=false) String requestId,
    @DbColumn(name="RESTRICTION_TYPE", generated=false, identity=false, nullable=false) String restrictionType,
    @DbColumn(name="RESTRICTION_AMOUNT", generated=false, identity=false, nullable=true) BigDecimal restrictionAmount,
    @DbColumn(name="SOURCE_SYSTEM", generated=false, identity=false, nullable=true) String sourceSystem,
    @DbColumn(name="SOURCE_REFERENCE", generated=false, identity=false, nullable=true) String sourceReference,
    @DbColumn(name="REASON_CODE", generated=false, identity=false, nullable=false) String reasonCode,
    @DbColumn(name="REASON_REMARKS", generated=false, identity=false, nullable=true) String reasonRemarks,
    @DbColumn(name="RESTRICTION_STATUS", generated=false, identity=false, nullable=false) String restrictionStatus,
    @DbColumn(name="STARTED_AT", generated=false, identity=false, nullable=false) OffsetDateTime startedAt,
    @DbColumn(name="EFFECTIVE_AT", generated=false, identity=false, nullable=true) OffsetDateTime effectiveAt,
    @DbColumn(name="ENDS_AT", generated=false, identity=false, nullable=true) OffsetDateTime endsAt,
    @DbColumn(name="CREATED_BY_USER_ID", generated=false, identity=false, nullable=false) String createdByUserId,
    @DbColumn(name="REMOVED_BY_USER_ID", generated=false, identity=false, nullable=true) String removedByUserId,
    @DbColumn(name="REMOVED_AT", generated=false, identity=false, nullable=true) OffsetDateTime removedAt,
    @DbColumn(name="FAILURE_CODE", generated=false, identity=false, nullable=true) String failureCode
) {}
