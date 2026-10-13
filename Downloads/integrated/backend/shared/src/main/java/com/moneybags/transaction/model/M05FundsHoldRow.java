package com.moneybags.transaction.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M05FundsHoldRow(
    @DbColumn(name="HOLD_ID", generated=true, identity=true, nullable=false) BigDecimal holdId,
    @DbColumn(name="BANK_ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal bankAccountId,
    @DbColumn(name="CURRENCY_CODE", generated=false, identity=false, nullable=false) String currencyCode,
    @DbColumn(name="AMOUNT", generated=false, identity=false, nullable=false) BigDecimal amount,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="TXN_ID", generated=false, identity=false, nullable=true) BigDecimal txnId,
    @DbColumn(name="PAYMENT_ID", generated=false, identity=false, nullable=true) BigDecimal paymentId,
    @DbColumn(name="HOLD_KEY", generated=false, identity=false, nullable=false) String holdKey,
    @DbColumn(name="EXPIRES_AT", generated=false, identity=false, nullable=true) OffsetDateTime expiresAt,
    @DbColumn(name="CONSUMED_BY_JOURNAL_ID", generated=false, identity=false, nullable=true) BigDecimal consumedByJournalId,
    @DbColumn(name="RELEASE_REASON", generated=false, identity=false, nullable=true) String releaseReason,
    @DbColumn(name="CLOSED_AT", generated=false, identity=false, nullable=true) OffsetDateTime closedAt,
    @DbColumn(name="VERSION_NO", generated=false, identity=false, nullable=false) Long versionNo,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt,
    @DbColumn(name="UPDATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime updatedAt
) {}
