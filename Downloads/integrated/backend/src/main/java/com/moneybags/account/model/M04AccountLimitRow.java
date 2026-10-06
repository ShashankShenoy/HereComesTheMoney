package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04AccountLimitRow(
    @DbColumn(name="LIMIT_ID", generated=true, identity=true, nullable=false) BigDecimal limitId,
    @DbColumn(name="ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal accountId,
    @DbColumn(name="CHANGE_REQUEST_ID", generated=false, identity=false, nullable=false) String changeRequestId,
    @DbColumn(name="LIMIT_TYPE", generated=false, identity=false, nullable=false) String limitType,
    @DbColumn(name="OPERATION_CODE", generated=false, identity=false, nullable=false) String operationCode,
    @DbColumn(name="CHANNEL_CODE", generated=false, identity=false, nullable=true) String channelCode,
    @DbColumn(name="PERIOD_CODE", generated=false, identity=false, nullable=false) String periodCode,
    @DbColumn(name="RESET_RULE_CODE", generated=false, identity=false, nullable=false) String resetRuleCode,
    @DbColumn(name="TIME_ZONE_ID", generated=false, identity=false, nullable=false) String timeZoneId,
    @DbColumn(name="LIMIT_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal limitAmount,
    @DbColumn(name="PRODUCT_LIMIT_RULE_ID", generated=false, identity=false, nullable=true) BigDecimal productLimitRuleId,
    @DbColumn(name="OVERRIDE_POLICY_ID", generated=false, identity=false, nullable=true) BigDecimal overridePolicyId,
    @DbColumn(name="EFFECTIVE_FROM", generated=false, identity=false, nullable=false) LocalDate effectiveFrom,
    @DbColumn(name="EFFECTIVE_TO", generated=false, identity=false, nullable=true) LocalDate effectiveTo,
    @DbColumn(name="IS_ACTIVE", generated=false, identity=false, nullable=false) String isActive,
    @DbColumn(name="APPROVED_BY_USER_ID", generated=false, identity=false, nullable=true) String approvedByUserId,
    @DbColumn(name="CREATED_BY_USER_ID", generated=false, identity=false, nullable=false) String createdByUserId,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt
) {}
