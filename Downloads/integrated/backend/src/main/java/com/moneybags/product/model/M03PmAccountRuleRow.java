package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmAccountRuleRow(
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="MIN_OPENING_BALANCE", generated=false, identity=false, nullable=false) BigDecimal minOpeningBalance,
    @DbColumn(name="MINOR_ALLOWED", generated=false, identity=false, nullable=false) String minorAllowed,
    @DbColumn(name="MINOR_DAILY_DEBIT_LIMIT", generated=false, identity=false, nullable=true) BigDecimal minorDailyDebitLimit,
    @DbColumn(name="JOINT_ALLOWED", generated=false, identity=false, nullable=false) String jointAllowed,
    @DbColumn(name="MAX_HOLDERS", generated=false, identity=false, nullable=false) Long maxHolders,
    @DbColumn(name="NOMINEE_REQUIRED", generated=false, identity=false, nullable=false) String nomineeRequired,
    @DbColumn(name="DORMANCY_AFTER_DAYS", generated=false, identity=false, nullable=true) Long dormancyAfterDays
) {}
