package com.moneybags.treasury.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Immutable domain projections returned by the Oracle repository. */
public final class TreasuryModels {
    private TreasuryModels() {}

    public record ReserveAccount(long id, String code, String accountType, String externalAccountRef,
                                 long glAccountId, String currency, String status,
                                 BigDecimal safetyBuffer, BigDecimal warningThreshold,
                                 OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo) {}

    public record ReservePosition(long reserveAccountId, String currency, BigDecimal confirmedBalance,
                                  BigDecimal activeHoldAmount, BigDecimal availableBalance,
                                  Long lastTreasuryEntryId, long version, OffsetDateTime asOf) {}

    public record LiquidityHold(long id, String holdKey, long reserveAccountId, String railCode,
                                Long paymentId, Long settlementCycleId, BigDecimal amount,
                                String currency, HoldStatus status, byte[] requestHash, String responseReference,
                                OffsetDateTime expiresAt, OffsetDateTime committedAt,
                                Long consumedByEntryId, String releaseReason,
                                OffsetDateTime createdAt, OffsetDateTime closedAt, int version) {}

    public record SettlementCycle(long id, String railCode, long reserveAccountId, String cycleReference,
                                  Long clearingBatchId, String direction, String currency,
                                  BigDecimal grossOut, BigDecimal grossIn, BigDecimal netAmount,
                                  String netMovementSide, CycleStatus status,
                                  OffsetDateTime openedAt, OffsetDateTime closedAt, OffsetDateTime settledAt) {}

    public record SettlementCycleItem(long id, long cycleId, long paymentId, String direction,
                                      BigDecimal amount, String status, OffsetDateTime includedAt,
                                      OffsetDateTime reconciledAt) {}

    public record SettlementEvidence(long id, long reserveAccountId, Long paymentId, Long cycleId,
                                     String railCode, String type, String status, String movementSide,
                                     BigDecimal amount, String externalSettlementRef,
                                     String externalStatementRef, OffsetDateTime occurredAt,
                                     OffsetDateTime receivedAt) {}

    public record TreasuryEntry(long id, long reserveAccountId, Long paymentId, Long cycleId,
                                long evidenceId, String railCode, String movementSide,
                                BigDecimal amount, long glJournalId, OffsetDateTime settledAt,
                                OffsetDateTime recordedAt) {}

    public record ReconciliationException(long id, String mismatchKey, long reserveAccountId,
                                          Long paymentId, Long cycleId, Long treasuryEntryId,
                                          String type, ExceptionStatus status, String severity,
                                          String ownerId, String evidenceJson, String resolutionCode,
                                          String resolutionText, OffsetDateTime openedAt,
                                          OffsetDateTime resolvedAt) {}

    public record WorkItem(long id, String workType, String subjectType, long reserveAccountId,
                           Long paymentId, Long cycleId, String sourceReference,
                           BigDecimal expectedAmount, BigDecimal observedAmount, String detailText,
                           String ownerId, WorkStatus status, String makerUserId,
                           String checkerUserId, Long glJournalId, OffsetDateTime openedAt,
                           OffsetDateTime resolvedAt, int version) {}

    public enum HoldStatus { REQUESTED, RESERVED, COMMITTED, CONSUMED, RELEASED, EXPIRED, EXCEPTION }
    public enum CycleStatus { OPEN, CLOSED, SUBMITTED, SETTLEMENT_PENDING, SETTLED, EXCEPTION }
    public enum ExceptionStatus { OPEN, ASSIGNED, RESOLVED, WAIVED }
    public enum WorkStatus { OPEN, ASSIGNED, PENDING_APPROVAL, RESOLVED, WAIVED, REJECTED }
}
