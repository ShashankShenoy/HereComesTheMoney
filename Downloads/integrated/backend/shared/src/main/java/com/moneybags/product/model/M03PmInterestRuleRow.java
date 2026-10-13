package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmInterestRuleRow(
    @DbColumn(name="INTEREST_RULE_ID", generated=true, identity=true, nullable=false) BigDecimal interestRuleId,
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="RULE_CODE", generated=false, identity=false, nullable=false) String ruleCode,
    @DbColumn(name="INTEREST_TYPE", generated=false, identity=false, nullable=false) String interestType,
    @DbColumn(name="INTEREST_METHOD", generated=false, identity=false, nullable=false) String interestMethod,
    @DbColumn(name="FIXED_RATE_PCT", generated=false, identity=false, nullable=true) BigDecimal fixedRatePct,
    @DbColumn(name="RATE_INDEX_CODE", generated=false, identity=false, nullable=true) String rateIndexCode,
    @DbColumn(name="SPREAD_PCT", generated=false, identity=false, nullable=true) BigDecimal spreadPct,
    @DbColumn(name="DAY_COUNT_BASIS", generated=false, identity=false, nullable=false) String dayCountBasis,
    @DbColumn(name="COMPOUND_FREQUENCY", generated=false, identity=false, nullable=false) String compoundFrequency,
    @DbColumn(name="PAYOUT_FREQUENCY", generated=false, identity=false, nullable=false) String payoutFrequency,
    @DbColumn(name="ROUNDING_MODE", generated=false, identity=false, nullable=false) String roundingMode
) {}
