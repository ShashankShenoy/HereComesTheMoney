package com.moneybags.account.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** HTTP payloads; these records deliberately contain no persistence entities. */
public final class Models {
    private Models() { }

    public record OpenAccount(@NotBlank @Size(max=60) String requestId, @NotBlank @Size(max=36) String primaryCifId,
            @NotNull Long productId, @NotNull Long productVersionId, @NotBlank String branchCode,
            @NotBlank String operationMode, @Valid List<PartyInput> additionalParties) { }
    public record SelfOpenAccount(@NotBlank @Size(max=60) String requestId, @NotBlank String cifId,
            @NotNull Long productId, @NotNull Long productVersionId) { }
    public record RequestId(@NotBlank @Size(max=60) String requestId) { }
    public record PartyInput(@NotBlank String cifId, @NotBlank String role,
            @NotBlank String operatingInstruction) { }
    public record NomineeInput(String nomineeCifId, @NotBlank String name,
            @NotBlank String relationship, String dateOfBirth, String mobileNumber,
            String addressLine1, String addressLine2, String city, String state,
            String postalCode, @NotNull @DecimalMin("0.01") @DecimalMax("100.00") BigDecimal sharePercentage,
            String guardianName, String guardianRelationship) { }
    public record ReplaceNominees(@NotBlank @Size(max=60) String requestId, @NotEmpty @Valid List<NomineeInput> nominees) { }
    public record RestrictionCommand(@NotBlank @Size(max=60) String requestId, @NotBlank String type,
            @Digits(integer=16,fraction=2) BigDecimal amount, @NotBlank String reasonCode, String remarks,
            String sourceSystem, String sourceReference, OffsetDateTime endsAt) { }
    public record ReasonCommand(@NotBlank @Size(max=60) String requestId, @NotBlank String reasonCode,
            String remarks) { }
    public record LimitCommand(@NotBlank @Size(max=60) String requestId, @NotBlank String limitType,
            @NotBlank String operationCode, String channelCode, @NotBlank String periodCode,
            @NotBlank String resetRuleCode, @NotBlank String timeZoneId,
            @NotNull @DecimalMin("0.00") @Digits(integer=16,fraction=2) BigDecimal amount, Long productLimitRuleId,
            Long overridePolicyId, String effectiveFrom, String effectiveTo, String approvedByUserId) { }
    public record SelfLimitRequest(@NotBlank @Size(max=60) String requestId,
            @NotBlank String operationCode, @NotBlank String periodCode,
            @NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal requestedAmount,
            @NotBlank @Size(max=500) String reason) { }
    public record LimitRequestDecision(@NotBlank String decision, @Size(max=500) String reason,
            Long productLimitRuleId, Long overridePolicyId, String approvedByUserId) { }
    public record SelfIssueRequest(@NotBlank @Size(max=60) String requestId,
            @NotBlank @Size(max=500) String details) { }
    public record SelfSafetyBlock(@NotBlank @Size(max=60) String requestId,
            @NotBlank String type, @NotBlank @Size(max=500) String details) { }
    public record InterestCommand(@NotBlank @Size(max=60) String requestId, @NotNull Long overridePolicyId,
            @NotNull @DecimalMin("0.00") BigDecimal ratePct, @NotBlank String reason,
            @NotNull OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
            @NotBlank String approvedByUserId) { }
    public record VersionCommand(@NotBlank @Size(max=60) String requestId, @NotNull Long targetVersionId,
            @NotNull Long treatmentId, @NotBlank String treatmentEventId,
            String consentReference, @NotBlank String reasonCode) { }
    public record ClosureDecision(@NotBlank String requestId, @NotBlank String decision,
            String reason) { }
    public record MajorityDecision(@NotBlank String requestId, @NotBlank String decision,
            String reason, String newOperationMode, String consentReference) { }
    public record ControlAck(@NotBlank String eventId, @NotNull Long accountId,
            @NotNull Long controlVersion, @NotBlank String sourceEventId,
            @NotBlank String outcome, Long fenceVersion, String failureCode) { }
    public record ProjectionEvent(@NotBlank String eventId, @NotNull Long accountId,
            @NotBlank String eventType, @NotNull Long version, Long journalId,
            Long postingId, Long transactionId, Long holdId,
            @NotNull BigDecimal ledgerDelta, @NotNull BigDecimal blockedDelta,
            @NotNull BigDecimal lienDelta, @NotNull BigDecimal overdraftDelta,
            @NotNull BigDecimal ledgerAfter, @NotNull BigDecimal blockedAfter,
            @NotNull BigDecimal lienAfter, @NotNull BigDecimal overdraftAfter,
            @NotNull OffsetDateTime occurredAt, String correlationId) { }
    public record AccountView(@JsonFormat(shape=JsonFormat.Shape.STRING) long id, String number,
            String primaryCifId, @JsonFormat(shape=JsonFormat.Shape.STRING) long productId,
            @JsonFormat(shape=JsonFormat.Shape.STRING) long productVersionId, String branchCode, String currencyCode,
            String lifecycleStatus, String accountStatus,
            String operationMode, String majorityReviewStatus, BigDecimal ledgerBalance,
            BigDecimal blockedBalance, BigDecimal lienBalance, BigDecimal overdraftLimit,
            BigDecimal availableBalance, BigDecimal interestAccrued,
            OffsetDateTime lastInterestCalculationAt, OffsetDateTime lastInterestPostingAt,
            @JsonFormat(shape=JsonFormat.Shape.STRING) long balanceSourceVersion,
            @JsonFormat(shape=JsonFormat.Shape.STRING) long financialControlVersion,
            @JsonFormat(shape=JsonFormat.Shape.STRING) long rowVersion, OffsetDateTime openedAt, OffsetDateTime activatedAt,
            OffsetDateTime closedAt) { }
    public record CommandResult(@JsonFormat(shape=JsonFormat.Shape.STRING) long accountId,
            String status, String requestId, @JsonFormat(shape=JsonFormat.Shape.STRING) Long controlVersion) { }
    public record Page<T>(List<T> items, int limit, int offset) { }
}
