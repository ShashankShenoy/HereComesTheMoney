package com.moneybags.txn.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Requests for controlled chart-of-accounts and period administration. */
public final class OperationsContracts {
    private OperationsContracts() { }

    public record GlAccountRequest(@NotBlank String code, @NotBlank String name,
            @NotBlank String accountClass, @NotBlank String normalSide) { }
    public record MappingRequest(@NotNull Long productVersionId, @NotBlank String postingType,
            @NotBlank String roleCode, @NotNull Long glAccountId, @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo) { }
    public record CloseRequest(@NotBlank String requestKey, @NotNull LocalDate closedThroughDate,
            @NotBlank String reasonText) { }
    public record CloseDecision(@NotBlank String decision) { }
    public record FeeRequest(@NotBlank String feeKey, Long bankAccountId, Long loanFacilityId,
            @NotNull Long productFeeRuleId, @NotNull @DecimalMin("0.01") BigDecimal assessedAmount,
            @NotNull LocalDate dueDate) { }
}
