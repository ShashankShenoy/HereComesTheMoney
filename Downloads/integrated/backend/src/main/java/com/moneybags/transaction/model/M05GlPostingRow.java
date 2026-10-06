package com.moneybags.transaction.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M05GlPostingRow(
    @DbColumn(name="POSTING_ID", generated=true, identity=true, nullable=false) BigDecimal postingId,
    @DbColumn(name="JOURNAL_ID", generated=false, identity=false, nullable=false) BigDecimal journalId,
    @DbColumn(name="LINE_NO", generated=false, identity=false, nullable=false) Long lineNo,
    @DbColumn(name="GL_ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal glAccountId,
    @DbColumn(name="BANK_ACCOUNT_ID", generated=false, identity=false, nullable=true) BigDecimal bankAccountId,
    @DbColumn(name="LOAN_FACILITY_ID", generated=false, identity=false, nullable=true) BigDecimal loanFacilityId,
    @DbColumn(name="LOAN_COMPONENT_CODE", generated=false, identity=false, nullable=true) String loanComponentCode,
    @DbColumn(name="ENTRY_SIDE", generated=false, identity=false, nullable=false) String entrySide,
    @DbColumn(name="AMOUNT", generated=false, identity=false, nullable=false) BigDecimal amount,
    @DbColumn(name="CURRENCY_CODE", generated=false, identity=false, nullable=false) String currencyCode,
    @DbColumn(name="NARRATIVE", generated=false, identity=false, nullable=true) String narrative,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt
) {}
