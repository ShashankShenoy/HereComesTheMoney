package com.moneybags.txn.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Immutable request and response shapes shared by the HTTP layer and service. */
public final class Contracts {
    private Contracts() { }

    public record TransferRequest(@NotNull Long sourceAccountId, @NotNull Long targetAccountId,
            @NotNull @DecimalMin("0.01") BigDecimal amount, @NotBlank String channelCode,
            @NotBlank String requestKey, @NotBlank String correlationId, @NotNull LocalDate valueDate) { }
    public record TransactionView(long transactionId, String status, Long journalId,
            String type, BigDecimal amount, String currency, String correlationId) { }
    public record PositionView(long accountId, BigDecimal posted, BigDecimal holds, BigDecimal liens,
            BigDecimal blocks, BigDecimal overdraft, BigDecimal spendable, long version, long controlVersion) { }
    public record ControlRequest(@NotNull UUID eventId, @NotNull Long controlVersion,
            @NotBlank String debitStatus, @NotBlank String creditStatus,
            @NotNull @DecimalMin("0.00") BigDecimal lienAmount,
            @NotNull @DecimalMin("0.00") BigDecimal blockAmount,
            @NotNull @DecimalMin("0.00") BigDecimal overdraftLimit, String reasonCode) { }
    public record HoldRequest(@NotNull Long bankAccountId, @NotNull Long paymentId,
            @NotNull @DecimalMin("0.01") BigDecimal amount, @NotBlank String holdKey,
            @NotNull OffsetDateTime expiresAt) { }
    public record HoldView(long holdId, String status, long bankAccountId, long paymentId, BigDecimal amount) { }
    public record ReleaseRequest(@NotBlank String reasonCode) { }
    public record JournalLine(@NotNull Long glAccountId, Long bankAccountId, Long loanFacilityId,
            String loanComponentCode, @NotBlank String side,
            @NotNull @DecimalMin("0.01") BigDecimal amount, String narrative) { }
    public record JournalRequest(@NotBlank String postingKey, @NotBlank String journalType,
            Long transactionId, Long paymentId, Long settlementCycleId, Long loanFacilityId,
            Long holdId, Long reversalOfJournalId, @NotNull LocalDate valueDate, String description,
            @NotEmpty @Size(min = 2) List<@Valid JournalLine> lines) { }
    public record JournalView(long journalId, String postingKey, BigDecimal debitTotal,
            BigDecimal creditTotal, String currency) { }
    public record ApprovalDecision(@NotBlank String decision, String reasonText) { }
    public record ReversalRequest(@NotBlank String requestKey, @NotBlank String reasonText,
            @NotBlank String correlationId, @NotNull LocalDate valueDate) { }
}
