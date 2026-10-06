package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmLimitRuleRow(
    @DbColumn(name="LIMIT_RULE_ID", generated=true, identity=true, nullable=false) BigDecimal limitRuleId,
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="RULE_CODE", generated=false, identity=false, nullable=false) String ruleCode,
    @DbColumn(name="OPERATION_CODE", generated=false, identity=false, nullable=false) String operationCode,
    @DbColumn(name="PERIOD_CODE", generated=false, identity=false, nullable=false) String periodCode,
    @DbColumn(name="MIN_AMOUNT", generated=false, identity=false, nullable=true) BigDecimal minAmount,
    @DbColumn(name="MAX_AMOUNT", generated=false, identity=false, nullable=true) BigDecimal maxAmount,
    @DbColumn(name="MAX_COUNT", generated=false, identity=false, nullable=true) Long maxCount,
    @DbColumn(name="CURRENCY_CODE", generated=false, identity=false, nullable=true) String currencyCode,
    @DbColumn(name="RESET_RULE_CODE", generated=false, identity=false, nullable=true) String resetRuleCode
) {}
