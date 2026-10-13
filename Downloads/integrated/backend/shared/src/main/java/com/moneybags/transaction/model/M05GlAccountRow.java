package com.moneybags.transaction.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M05GlAccountRow(
    @DbColumn(name="GL_ACCOUNT_ID", generated=true, identity=true, nullable=false) BigDecimal glAccountId,
    @DbColumn(name="GL_CODE", generated=false, identity=false, nullable=false) String glCode,
    @DbColumn(name="GL_NAME", generated=false, identity=false, nullable=false) String glName,
    @DbColumn(name="ACCOUNT_CLASS", generated=false, identity=false, nullable=false) String accountClass,
    @DbColumn(name="NORMAL_SIDE", generated=false, identity=false, nullable=false) String normalSide,
    @DbColumn(name="CURRENCY_CODE", generated=false, identity=false, nullable=false) String currencyCode,
    @DbColumn(name="ACTIVE_FLAG", generated=false, identity=false, nullable=false) String activeFlag,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt
) {}
