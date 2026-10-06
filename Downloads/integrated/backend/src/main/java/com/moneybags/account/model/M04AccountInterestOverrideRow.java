package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04AccountInterestOverrideRow(
    @DbColumn(name="INTEREST_OVERRIDE_ID", generated=true, identity=true, nullable=false) BigDecimal interestOverrideId,
    @DbColumn(name="ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal accountId,
    @DbColumn(name="CHANGE_REQUEST_ID", generated=false, identity=false, nullable=false) String changeRequestId,
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="OVERRIDE_POLICY_ID", generated=false, identity=false, nullable=false) BigDecimal overridePolicyId,
    @DbColumn(name="RATE_PCT", generated=false, identity=false, nullable=false) BigDecimal ratePct,
    @DbColumn(name="REASON", generated=false, identity=false, nullable=false) String reason,
    @DbColumn(name="EFFECTIVE_FROM_AT", generated=false, identity=false, nullable=false) OffsetDateTime effectiveFromAt,
    @DbColumn(name="EFFECTIVE_FROM_UTC", generated=true, identity=false, nullable=true) LocalDateTime effectiveFromUtc,
    @DbColumn(name="EFFECTIVE_TO_AT", generated=false, identity=false, nullable=true) OffsetDateTime effectiveToAt,
    @DbColumn(name="APPROVED_BY_USER_ID", generated=false, identity=false, nullable=false) String approvedByUserId,
    @DbColumn(name="CREATED_BY_USER_ID", generated=false, identity=false, nullable=false) String createdByUserId,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt
) {}
