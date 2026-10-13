package com.moneybags.transaction.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M05PostingFenceRow(
    @DbColumn(name="BANK_ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal bankAccountId,
    @DbColumn(name="DEBIT_STATUS", generated=false, identity=false, nullable=false) String debitStatus,
    @DbColumn(name="CREDIT_STATUS", generated=false, identity=false, nullable=false) String creditStatus,
    @DbColumn(name="CONTROL_VERSION", generated=false, identity=false, nullable=false) BigDecimal controlVersion,
    @DbColumn(name="SOURCE_EVENT_ID", generated=false, identity=false, nullable=true) byte[] sourceEventId,
    @DbColumn(name="REASON_CODE", generated=false, identity=false, nullable=true) String reasonCode,
    @DbColumn(name="CHANGED_BY", generated=false, identity=false, nullable=false) String changedBy,
    @DbColumn(name="CHANGED_AT", generated=false, identity=false, nullable=false) OffsetDateTime changedAt
) {}
