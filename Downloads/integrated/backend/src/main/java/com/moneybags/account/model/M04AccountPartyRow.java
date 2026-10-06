package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04AccountPartyRow(
    @DbColumn(name="ACCOUNT_PARTY_ID", generated=true, identity=true, nullable=false) BigDecimal accountPartyId,
    @DbColumn(name="ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal accountId,
    @DbColumn(name="CIF_ID", generated=false, identity=false, nullable=false) String cifId,
    @DbColumn(name="PARTY_ROLE", generated=false, identity=false, nullable=false) String partyRole,
    @DbColumn(name="OPERATING_INSTRUCTION", generated=false, identity=false, nullable=false) String operatingInstruction,
    @DbColumn(name="IS_ACTIVE", generated=false, identity=false, nullable=false) String isActive,
    @DbColumn(name="EFFECTIVE_FROM", generated=false, identity=false, nullable=false) LocalDate effectiveFrom,
    @DbColumn(name="EFFECTIVE_TO", generated=false, identity=false, nullable=true) LocalDate effectiveTo,
    @DbColumn(name="CREATED_BY_USER_ID", generated=false, identity=false, nullable=false) String createdByUserId,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt
) {}
