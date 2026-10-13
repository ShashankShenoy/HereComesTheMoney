package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06PaymentStatusHistoryRow(
    @DbColumn(name="PAYMENT_STATUS_HISTORY_ID", generated=true, identity=true, nullable=false) BigDecimal paymentStatusHistoryId,
    @DbColumn(name="PAYMENT_ID", generated=false, identity=false, nullable=false) BigDecimal paymentId,
    @DbColumn(name="FROM_STATUS", generated=false, identity=false, nullable=true) String fromStatus,
    @DbColumn(name="TO_STATUS", generated=false, identity=false, nullable=false) String toStatus,
    @DbColumn(name="ACTOR_TYPE", generated=false, identity=false, nullable=false) String actorType,
    @DbColumn(name="ACTOR_ID", generated=false, identity=false, nullable=true) String actorId,
    @DbColumn(name="REASON_CODE", generated=false, identity=false, nullable=true) String reasonCode,
    @DbColumn(name="EVIDENCE_ID", generated=false, identity=false, nullable=true) BigDecimal evidenceId,
    @DbColumn(name="CHANGED_AT", generated=false, identity=false, nullable=false) OffsetDateTime changedAt,
    @DbColumn(name="PAYMENT_DIRECTION", generated=false, identity=false, nullable=false) String paymentDirection,
    @DbColumn(name="LEDGER_JOURNAL_ID", generated=false, identity=false, nullable=true) BigDecimal ledgerJournalId,
    @DbColumn(name="TREASURY_ENTRY_ID", generated=false, identity=false, nullable=true) BigDecimal treasuryEntryId
) {}
