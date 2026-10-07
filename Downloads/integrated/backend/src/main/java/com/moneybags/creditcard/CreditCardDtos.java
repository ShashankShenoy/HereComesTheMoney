package com.moneybags.creditcard;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Monetary inputs are decimal INR amounts; card references are synthetic identifiers. */
public final class CreditCardDtos {
    private CreditCardDtos() {}

    public record ProductInput(
        @NotBlank @Size(max=100) String requestKey,
        @NotBlank @Pattern(regexp="[A-Z][A-Z0-9_-]{0,29}") String productCode,
        @NotBlank @Size(max=120) String productName,
        @Min(1) int versionNumber,
        @NotNull @DecimalMin("1000") @DecimalMax("10000000") @Digits(integer=8,fraction=2) BigDecimal minimumLimit,
        @NotNull @DecimalMin("1000") @DecimalMax("10000000") @Digits(integer=8,fraction=2) BigDecimal maximumLimit,
        @NotNull @DecimalMin("0") @DecimalMax("48") @Digits(integer=2,fraction=2) BigDecimal annualRatePct,
        @NotNull @DecimalMin("1") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal minimumPaymentPct,
        @NotNull @DecimalMin("0") @DecimalMax("100000") @Digits(integer=6,fraction=2) BigDecimal minimumPaymentFloor,
        @Min(1) @Max(28) int billingDay,
        @Min(7) @Max(25) int paymentDueDays,
        @NotBlank @Size(max=1000) String description) {}

    public record Decision(
        @NotBlank @Size(max=100) String requestKey,
        @Min(1) long rowVersion,
        @NotBlank @Pattern(regexp="APPROVED|REJECTED") String decision,
        @NotBlank @Size(max=500) String reason,
        @DecimalMin("1000") @DecimalMax("10000000") @Digits(integer=8,fraction=2) BigDecimal approvedLimit) {}

    public record ApplicationInput(
        @NotBlank @Size(max=100) String requestKey,
        @NotBlank @Size(max=36) String productId,
        @NotBlank @Size(max=36) String cifId,
        @Min(1) long repaymentAccountId,
        @NotNull @DecimalMin("1000") @DecimalMax("10000000") @Digits(integer=8,fraction=2) BigDecimal requestedLimit,
        @AssertTrue(message="Accept the credit card terms before applying") boolean termsAccepted) {}

    public record Command(
        @NotBlank @Size(max=100) String requestKey,
        @Min(1) long rowVersion,
        @NotBlank @Size(max=500) String reason) {}

    public record Control(
        @NotBlank @Size(max=100) String requestKey,
        @Min(1) long rowVersion,
        @NotBlank @Pattern(regexp="ACTIVATE|FREEZE|UNFREEZE|BLOCK|CLOSE") String action,
        @NotBlank @Size(max=500) String reason) {}

    public record Purchase(
        @NotBlank @Size(max=100) String requestKey,
        @NotNull @DecimalMin("0.01") @DecimalMax("10000000") @Digits(integer=8,fraction=2) BigDecimal amount,
        @NotBlank @Size(max=120) String merchantName) {}

    public record Repayment(
        @NotBlank @Size(max=100) String requestKey,
        @NotNull @DecimalMin("0.01") @DecimalMax("20000000") @Digits(integer=8,fraction=2) BigDecimal amount) {}

    public record Refund(
        @NotBlank @Size(max=100) String requestKey,
        @NotBlank @Size(max=500) String reason) {}

    public record Billing(
        @NotBlank @Size(max=100) String requestKey,
        @NotNull LocalDate statementDate) {}
}
