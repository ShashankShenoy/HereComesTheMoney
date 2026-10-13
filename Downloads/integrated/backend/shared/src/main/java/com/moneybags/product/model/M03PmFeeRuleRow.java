package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmFeeRuleRow(
    @DbColumn(name="FEE_RULE_ID", generated=true, identity=true, nullable=false) BigDecimal feeRuleId,
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="RULE_CODE", generated=false, identity=false, nullable=false) String ruleCode,
    @DbColumn(name="TRIGGER_CODE", generated=false, identity=false, nullable=false) String triggerCode,
    @DbColumn(name="CHARGE_BASIS", generated=false, identity=false, nullable=false) String chargeBasis,
    @DbColumn(name="FIXED_AMOUNT", generated=false, identity=false, nullable=true) BigDecimal fixedAmount,
    @DbColumn(name="RATE_PCT", generated=false, identity=false, nullable=true) BigDecimal ratePct,
    @DbColumn(name="CHARGE_FREQUENCY", generated=false, identity=false, nullable=false) String chargeFrequency,
    @DbColumn(name="WAIVER_RULE_CODE", generated=false, identity=false, nullable=true) String waiverRuleCode,
    @DbColumn(name="TAX_CODE", generated=false, identity=false, nullable=true) String taxCode,
    @DbColumn(name="EFFECTIVE_FROM_AT", generated=false, identity=false, nullable=true) OffsetDateTime effectiveFromAt,
    @DbColumn(name="EFFECTIVE_TO_AT", generated=false, identity=false, nullable=true) OffsetDateTime effectiveToAt
) {}
