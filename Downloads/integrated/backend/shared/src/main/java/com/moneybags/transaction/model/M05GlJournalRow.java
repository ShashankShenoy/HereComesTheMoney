package com.moneybags.transaction.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M05GlJournalRow(
    @DbColumn(name="JOURNAL_ID", generated=true, identity=true, nullable=false) BigDecimal journalId,
    @DbColumn(name="POSTING_KEY", generated=false, identity=false, nullable=false) String postingKey,
    @DbColumn(name="TXN_ID", generated=false, identity=false, nullable=true) BigDecimal txnId,
    @DbColumn(name="PAYMENT_ID", generated=false, identity=false, nullable=true) BigDecimal paymentId,
    @DbColumn(name="SETTLEMENT_CYCLE_ID", generated=false, identity=false, nullable=true) BigDecimal settlementCycleId,
    @DbColumn(name="LOAN_FACILITY_ID", generated=false, identity=false, nullable=true) BigDecimal loanFacilityId,
    @DbColumn(name="JOURNAL_TYPE", generated=false, identity=false, nullable=false) String journalType,
    @DbColumn(name="CURRENCY_CODE", generated=false, identity=false, nullable=false) String currencyCode,
    @DbColumn(name="BOOKED_AT", generated=false, identity=false, nullable=false) OffsetDateTime bookedAt,
    @DbColumn(name="VALUE_DATE", generated=false, identity=false, nullable=false) LocalDate valueDate,
    @DbColumn(name="REVERSAL_OF_JOURNAL_ID", generated=false, identity=false, nullable=true) BigDecimal reversalOfJournalId,
    @DbColumn(name="DESCRIPTION", generated=false, identity=false, nullable=true) String description,
    @DbColumn(name="CREATED_BY", generated=false, identity=false, nullable=false) String createdBy,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt
) {}
