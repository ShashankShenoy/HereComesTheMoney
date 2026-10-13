package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06PaymentReconciliationRow(
    @DbColumn(name="RECONCILIATION_ID", generated=true, identity=true, nullable=false) BigDecimal reconciliationId,
    @DbColumn(name="PAYMENT_ID", generated=false, identity=false, nullable=false) BigDecimal paymentId,
    @DbColumn(name="RECONCILIATION_STATUS", generated=false, identity=false, nullable=false) String reconciliationStatus,
    @DbColumn(name="CUSTOMER_JOURNAL_ID", generated=false, identity=false, nullable=true) BigDecimal customerJournalId,
    @DbColumn(name="REFUND_JOURNAL_ID", generated=false, identity=false, nullable=true) BigDecimal refundJournalId,
    @DbColumn(name="SETTLEMENT_JOURNAL_ID", generated=false, identity=false, nullable=true) BigDecimal settlementJournalId,
    @DbColumn(name="RAIL_EVIDENCE_ID", generated=false, identity=false, nullable=true) BigDecimal railEvidenceId,
    @DbColumn(name="TREASURY_ENTRY_ID", generated=false, identity=false, nullable=true) BigDecimal treasuryEntryId,
    @DbColumn(name="SETTLEMENT_CYCLE_ID", generated=false, identity=false, nullable=true) BigDecimal settlementCycleId,
    @DbColumn(name="EXTERNAL_SETTLEMENT_REF", generated=false, identity=false, nullable=true) String externalSettlementRef,
    @DbColumn(name="EXPECTED_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal expectedAmount,
    @DbColumn(name="RECONCILED_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal reconciledAmount,
    @DbColumn(name="CHECK_VERSION", generated=false, identity=false, nullable=false) BigDecimal checkVersion,
    @DbColumn(name="LAST_CHECKED_AT", generated=false, identity=false, nullable=false) OffsetDateTime lastCheckedAt,
    @DbColumn(name="RECONCILED_AT", generated=false, identity=false, nullable=true) OffsetDateTime reconciledAt
) {}
