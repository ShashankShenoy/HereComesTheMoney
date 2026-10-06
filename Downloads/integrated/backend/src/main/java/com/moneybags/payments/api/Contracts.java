package com.moneybags.payments.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Request and response shapes shared by the REST boundary and integration tests. */
public final class Contracts {
    private Contracts() { }

    /** Creates an outbound or inbound payment; the originator and key form one idempotency scope. */
    public record CreatePayment(
            @NotBlank String originatorId, @NotBlank String channelCode, @NotBlank String requestKey,
            @NotBlank String correlationId, @NotBlank String endToEndId,
            @NotNull @Pattern(regexp = "OUTBOUND|INBOUND") String direction,
            @NotNull @Pattern(regexp = "PAYMENT|RETURN") String kind,
            Long originalPaymentId, Long sourceAccountId, String sourceCifId,
            Long destinationAccountId, String destinationCifId,
            String beneficiaryTokenRef, String beneficiaryBankCode,
            @NotNull @Pattern(regexp = "UPI|IMPS|NEFT|RTGS") String railCode,
            @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount,
            @NotNull LocalDate requestedExecutionDate) { }

    /** Requests a legal transition and names the external evidence that authorizes it. */
    public record Transition(@NotBlank String toStatus, @NotNull @Min(0) Integer expectedVersion,
                             Long evidenceId, Long ledgerJournalId, Long treasuryEntryId,
                             Long holdId, Long liquidityHoldId, String reasonCode) { }

    /** Records an immutable observation received from a rail adapter. */
    public record RailEvidence(@NotBlank String railMessageId, String externalReference,
                               @NotNull @Pattern(regexp = "ACKNOWLEDGEMENT|CALLBACK|INQUIRY|CLEARING|SETTLEMENT|RETURN") String evidenceType,
                               @NotNull @Pattern(regexp = "ACCEPTED|REJECTED|PENDING|SETTLED|RETURNED|UNKNOWN") String railStatus,
                               String reasonCode, @NotBlank String evidenceRef,
                               @NotBlank String evidenceHashHex, OffsetDateTime occurredAt) { }

    /** Prepares one dispatch without sending an external message inside a database transaction. */
    public record PrepareDispatch(@NotBlank String railMessageId, @NotBlank String payloadRef) { }

    /** Records an adapter attempt; TIMEOUT requires an inquiry before another SEND. */
    public record DispatchAttempt(@NotNull @Pattern(regexp = "SEND|INQUIRY") String attemptType,
                                  @NotNull @Pattern(regexp = "STARTED|SENT|ACKNOWLEDGED|TIMEOUT|REJECTED|ERROR") String outcome,
                                  String transportReference, String errorCode) { }

    /** Creates a rail clearing batch with a unique external reference. */
    public record CreateBatch(@NotNull @Pattern(regexp = "UPI|IMPS|NEFT|RTGS") String railCode,
                              @NotBlank String batchReference,
                              @NotNull @Pattern(regexp = "OUTBOUND|INBOUND|NET") String direction) { }

    /** Adds an accepted payment to a clearing batch. */
    public record AddBatchItem(@NotNull Long paymentId, @NotBlank String externalItemRef) { }

    /** Associates an already verified Module 7 settlement cycle with a closed batch. */
    public record BatchSettlement(@NotNull Long settlementCycleId) { }

    /** Runs one evidence comparison using cross-service journal and treasury references. */
    public record Reconcile(Long customerJournalId, Long refundJournalId, Long settlementJournalId,
                            Long railEvidenceId, Long treasuryEntryId, Long settlementCycleId,
                            String externalSettlementRef, @NotNull @DecimalMin("0.00") BigDecimal reconciledAmount) { }

    /** Assigns or closes a reconciliation exception. */
    public record ExceptionAction(@NotBlank String action, String ownerId, String resolutionCode,
                                  String resolutionText) { }

    /** Requests a dual-control payment exception action. */
    public record ApprovalRequest(@NotBlank String approvalKey, @NotNull Long paymentId, Long exceptionId,
                                  @NotNull @Pattern(regexp = "MANUAL_RETURN|EXCEPTION_CORRECTION|MANUAL_STATUS_RESOLUTION") String actionCode,
                                  @NotBlank String reasonText) { }

    /** Records an independent checker decision. */
    public record ApprovalDecision(@NotNull @Pattern(regexp = "APPROVED|REJECTED") String decision) { }

    /** Small public payment view; detailed evidence is available through separate resources. */
    public record Payment(long paymentId, String endToEndId, String direction, String kind,
                          String railCode, BigDecimal amount, String status, int version,
                          Long holdId, Long customerTxnId, Long clearingBatchId,
                          Long settlementCycleId, Long liquidityHoldId, String railMessageId,
                          String railReference, String reasonCode) { }
}

