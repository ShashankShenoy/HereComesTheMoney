package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04AccountFinancialProjectionEventRow(
    @DbColumn(name="PROJECTION_EVENT_ID", generated=true, identity=true, nullable=false) BigDecimal projectionEventId,
    @DbColumn(name="ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal accountId,
    @DbColumn(name="SOURCE_EVENT_ID", generated=false, identity=false, nullable=false) byte[] sourceEventId,
    @DbColumn(name="EVENT_TYPE", generated=false, identity=false, nullable=false) String eventType,
    @DbColumn(name="SOURCE_JOURNAL_ID", generated=false, identity=false, nullable=true) BigDecimal sourceJournalId,
    @DbColumn(name="SOURCE_POSTING_ID", generated=false, identity=false, nullable=true) BigDecimal sourcePostingId,
    @DbColumn(name="SOURCE_TRANSACTION_ID", generated=false, identity=false, nullable=true) BigDecimal sourceTransactionId,
    @DbColumn(name="SOURCE_HOLD_ID", generated=false, identity=false, nullable=true) BigDecimal sourceHoldId,
    @DbColumn(name="PROJECTION_VERSION", generated=false, identity=false, nullable=false) BigDecimal projectionVersion,
    @DbColumn(name="LEDGER_DELTA", generated=false, identity=false, nullable=false) BigDecimal ledgerDelta,
    @DbColumn(name="BLOCKED_DELTA", generated=false, identity=false, nullable=false) BigDecimal blockedDelta,
    @DbColumn(name="LIEN_DELTA", generated=false, identity=false, nullable=false) BigDecimal lienDelta,
    @DbColumn(name="OVERDRAFT_DELTA", generated=false, identity=false, nullable=false) BigDecimal overdraftDelta,
    @DbColumn(name="LEDGER_BALANCE_AFTER", generated=false, identity=false, nullable=false) BigDecimal ledgerBalanceAfter,
    @DbColumn(name="BLOCKED_BALANCE_AFTER", generated=false, identity=false, nullable=false) BigDecimal blockedBalanceAfter,
    @DbColumn(name="LIEN_BALANCE_AFTER", generated=false, identity=false, nullable=false) BigDecimal lienBalanceAfter,
    @DbColumn(name="OVERDRAFT_LIMIT_AFTER", generated=false, identity=false, nullable=false) BigDecimal overdraftLimitAfter,
    @DbColumn(name="OCCURRED_AT", generated=false, identity=false, nullable=false) OffsetDateTime occurredAt,
    @DbColumn(name="APPLIED_AT", generated=false, identity=false, nullable=false) OffsetDateTime appliedAt,
    @DbColumn(name="CORRELATION_ID", generated=false, identity=false, nullable=true) String correlationId
) {}
