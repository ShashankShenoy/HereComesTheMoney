package com.moneybags.transaction.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M05PostingFenceHistoryRow(
    @DbColumn(name="FENCE_HISTORY_ID", generated=true, identity=true, nullable=false) BigDecimal fenceHistoryId,
    @DbColumn(name="BANK_ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal bankAccountId,
    @DbColumn(name="CONTROL_VERSION", generated=false, identity=false, nullable=false) BigDecimal controlVersion,
    @DbColumn(name="SOURCE_EVENT_ID", generated=false, identity=false, nullable=false) byte[] sourceEventId,
    @DbColumn(name="OLD_DEBIT_STATUS", generated=false, identity=false, nullable=true) String oldDebitStatus,
    @DbColumn(name="NEW_DEBIT_STATUS", generated=false, identity=false, nullable=false) String newDebitStatus,
    @DbColumn(name="OLD_CREDIT_STATUS", generated=false, identity=false, nullable=true) String oldCreditStatus,
    @DbColumn(name="NEW_CREDIT_STATUS", generated=false, identity=false, nullable=false) String newCreditStatus,
    @DbColumn(name="LIEN_AMOUNT_AFTER", generated=false, identity=false, nullable=false) BigDecimal lienAmountAfter,
    @DbColumn(name="BLOCK_AMOUNT_AFTER", generated=false, identity=false, nullable=false) BigDecimal blockAmountAfter,
    @DbColumn(name="REASON_CODE", generated=false, identity=false, nullable=true) String reasonCode,
    @DbColumn(name="APPLIED_AT", generated=false, identity=false, nullable=false) OffsetDateTime appliedAt
) {}
