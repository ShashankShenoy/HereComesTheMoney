package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmInterestTierRow(
    @DbColumn(name="INTEREST_TIER_ID", generated=true, identity=true, nullable=false) BigDecimal interestTierId,
    @DbColumn(name="INTEREST_RULE_ID", generated=false, identity=false, nullable=false) BigDecimal interestRuleId,
    @DbColumn(name="TIER_NO", generated=false, identity=false, nullable=false) Long tierNo,
    @DbColumn(name="AMOUNT_FROM", generated=false, identity=false, nullable=false) BigDecimal amountFrom,
    @DbColumn(name="AMOUNT_TO", generated=false, identity=false, nullable=true) BigDecimal amountTo,
    @DbColumn(name="RATE_PCT", generated=false, identity=false, nullable=false) BigDecimal ratePct
) {}
