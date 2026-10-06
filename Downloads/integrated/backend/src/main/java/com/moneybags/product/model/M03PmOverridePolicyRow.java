package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmOverridePolicyRow(
    @DbColumn(name="OVERRIDE_POLICY_ID", generated=true, identity=true, nullable=false) BigDecimal overridePolicyId,
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="RULE_FAMILY", generated=false, identity=false, nullable=false) String ruleFamily,
    @DbColumn(name="RULE_CODE", generated=false, identity=false, nullable=false) String ruleCode,
    @DbColumn(name="FIELD_CODE", generated=false, identity=false, nullable=false) String fieldCode,
    @DbColumn(name="MIN_VALUE", generated=false, identity=false, nullable=true) BigDecimal minValue,
    @DbColumn(name="MAX_VALUE", generated=false, identity=false, nullable=true) BigDecimal maxValue,
    @DbColumn(name="AUTHORITY_CODE", generated=false, identity=false, nullable=false) String authorityCode,
    @DbColumn(name="VALID_FROM", generated=false, identity=false, nullable=false) OffsetDateTime validFrom,
    @DbColumn(name="VALID_TO", generated=false, identity=false, nullable=true) OffsetDateTime validTo
) {}
