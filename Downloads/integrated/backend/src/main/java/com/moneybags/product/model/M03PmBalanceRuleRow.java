package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmBalanceRuleRow(
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="MIN_BALANCE", generated=false, identity=false, nullable=false) BigDecimal minBalance,
    @DbColumn(name="MEASUREMENT_BASIS", generated=false, identity=false, nullable=false) String measurementBasis,
    @DbColumn(name="MEASUREMENT_PERIOD", generated=false, identity=false, nullable=false) String measurementPeriod,
    @DbColumn(name="GRACE_DAYS", generated=false, identity=false, nullable=false) Long graceDays,
    @DbColumn(name="CONSEQUENCE_CODE", generated=false, identity=false, nullable=false) String consequenceCode
) {}
