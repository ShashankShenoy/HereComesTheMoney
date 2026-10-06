package com.moneybags.treasury.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** API command contracts kept separate from persistence and integration models. */
public final class TreasuryRequests {
    private TreasuryRequests() {}

    public record CreateReserveAccount(
        @NotBlank @Size(max=40) String code,
        @NotBlank @Pattern(regexp="RBI_CURRENT|RTGS_SETTLEMENT") String accountType,
        @NotBlank @Size(max=120) String externalAccountRef,
        @Positive long glAccountId,
        @NotNull @DecimalMin("0.00") BigDecimal safetyBuffer,
        @NotNull @DecimalMin("0.00") BigDecimal warningThreshold) {}

    public record CreateLiquidityHold(
        @NotBlank @Size(max=120) String holdKey,
        @Positive long reserveAccountId,
        @NotBlank @Pattern(regexp="UPI|IMPS|NEFT|RTGS") String railCode,
        Long paymentId, Long settlementCycleId,
        @NotNull @DecimalMin(value="0.00", inclusive=false) BigDecimal amount,
        OffsetDateTime expiresAt) {}

    public record HoldDecision(@NotBlank @Pattern(regexp="COMMIT|RELEASE") String decision,
                               @Size(max=50) String reason, int expectedVersion) {}

    public record CreateCycle(
        @NotBlank @Pattern(regexp="UPI|IMPS|NEFT|RTGS") String railCode,
        @Positive long reserveAccountId,
        @NotBlank @Size(max=100) String cycleReference,
        Long clearingBatchId,
        @NotBlank @Pattern(regexp="OUTBOUND|INBOUND|NET") String direction) {}

    public record CycleItem(@Positive long paymentId,
                            @NotBlank @Pattern(regexp="IN|OUT") String direction,
                            @NotNull @DecimalMin(value="0.00", inclusive=false) BigDecimal amount) {}

    public record AddCycleItems(@NotNull @Size(min=1, max=1000) List<CycleItem> items) {}

    public record SettlementEvidenceCommand(
        @Positive long reserveAccountId, Long paymentId, Long settlementCycleId,
        @NotBlank @Pattern(regexp="UPI|IMPS|NEFT|RTGS") String railCode,
        @NotBlank @Pattern(regexp="RAIL_CONFIRMATION|CLEARING_CONFIRMATION|RESERVE_STATEMENT|MANUAL_VERIFICATION") String evidenceType,
        @NotBlank @Pattern(regexp="RECEIVED|VERIFIED|REJECTED|CONFLICT") String evidenceStatus,
        @NotBlank @Pattern(regexp="IN|OUT") String movementSide,
        @NotNull @DecimalMin(value="0.00", inclusive=false) BigDecimal amount,
        @NotBlank @Size(max=120) String externalSettlementRef,
        @Size(max=120) String externalStatementRef,
        @NotBlank String evidencePayload,
        @Size(max=250) String evidenceRef,
        @NotNull OffsetDateTime occurredAt) {}

    public record ConfirmMovement(@Positive long evidenceId, @Positive long glJournalId) {}

    public record OpenException(
        @NotBlank @Size(max=150) String mismatchKey, @Positive long reserveAccountId,
        Long paymentId, Long settlementCycleId, Long treasuryEntryId,
        @NotBlank @Size(max=40) String exceptionType,
        @NotBlank @Pattern(regexp="LOW|MEDIUM|HIGH|CRITICAL") String severity,
        String evidenceJson) {}

    public record ResolveException(@NotBlank @Pattern(regexp="RESOLVED|WAIVED") String decision,
                                   @NotBlank @Size(max=50) String resolutionCode,
                                   @NotBlank @Size(max=1000) String resolutionText) {}

    public record CreateWorkItem(
        @NotBlank @Pattern(regexp="RECONCILIATION|FUNDING") String workType,
        @NotBlank @Pattern(regexp="PAYMENT|CYCLE|RESERVE_ACCOUNT") String subjectType,
        @Positive long reserveAccountId, Long paymentId, Long settlementCycleId,
        String sourceReference, BigDecimal expectedAmount, BigDecimal observedAmount,
        @NotBlank @Size(max=2000) String detailText, @NotBlank @Size(max=64) String ownerId) {}

    public record WorkDecision(@NotBlank @Pattern(regexp="RESOLVED|WAIVED|REJECTED") String decision,
                               @io.swagger.v3.oas.annotations.media.Schema(accessMode=io.swagger.v3.oas.annotations.media.Schema.AccessMode.READ_ONLY) String checkerUserId, Long glJournalId, int expectedVersion) {}

    public record InboundEvent(@NotBlank String consumerName, @NotBlank String eventId,
                               @NotBlank String eventType, @NotBlank String payload) {}
}
