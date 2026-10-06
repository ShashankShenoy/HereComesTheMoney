package com.moneybags.transaction.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M05AccountPositionRow(
    @DbColumn(name="BANK_ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal bankAccountId,
    @DbColumn(name="CURRENCY_CODE", generated=false, identity=false, nullable=false) String currencyCode,
    @DbColumn(name="POSTED_BALANCE", generated=false, identity=false, nullable=false) BigDecimal postedBalance,
    @DbColumn(name="ACTIVE_HOLD_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal activeHoldAmount,
    @DbColumn(name="ACTIVE_LIEN_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal activeLienAmount,
    @DbColumn(name="ACTIVE_BLOCK_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal activeBlockAmount,
    @DbColumn(name="OVERDRAFT_LIMIT", generated=false, identity=false, nullable=false) BigDecimal overdraftLimit,
    @DbColumn(name="SPENDABLE_BALANCE", generated=true, identity=false, nullable=true) BigDecimal spendableBalance,
    @DbColumn(name="LAST_JOURNAL_ID", generated=false, identity=false, nullable=true) BigDecimal lastJournalId,
    @DbColumn(name="POSITION_VERSION", generated=false, identity=false, nullable=false) BigDecimal positionVersion,
    @DbColumn(name="APPLIED_CONTROL_VERSION", generated=false, identity=false, nullable=false) BigDecimal appliedControlVersion,
    @DbColumn(name="AS_OF", generated=false, identity=false, nullable=false) OffsetDateTime asOf
) {}
