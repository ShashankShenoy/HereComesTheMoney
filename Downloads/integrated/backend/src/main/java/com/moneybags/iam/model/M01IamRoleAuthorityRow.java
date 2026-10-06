package com.moneybags.iam.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M01IamRoleAuthorityRow(
    @DbColumn(name="AUTHORITY_ID", generated=false, identity=false, nullable=false) String authorityId,
    @DbColumn(name="ROLE_ID", generated=false, identity=false, nullable=false) String roleId,
    @DbColumn(name="AUTHORITY_CODE", generated=false, identity=false, nullable=false) String authorityCode,
    @DbColumn(name="CURRENCY_CODE", generated=false, identity=false, nullable=false) String currencyCode,
    @DbColumn(name="MAX_AMOUNT", generated=false, identity=false, nullable=true) BigDecimal maxAmount,
    @DbColumn(name="MAX_RATE_PCT", generated=false, identity=false, nullable=true) BigDecimal maxRatePct,
    @DbColumn(name="VALID_FROM", generated=false, identity=false, nullable=false) OffsetDateTime validFrom,
    @DbColumn(name="VALID_FROM_UTC", generated=true, identity=false, nullable=true) LocalDateTime validFromUtc,
    @DbColumn(name="VALID_TO", generated=false, identity=false, nullable=true) OffsetDateTime validTo
) {}
