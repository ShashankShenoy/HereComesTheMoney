package com.moneybags.product.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Product contracts carry decimal values without binary floating-point conversion. */
public final class ProductDtos {

  private ProductDtos() {}

  public record ProductInput(
    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,39}") String productCode,
    @NotBlank @Size(max = 200) String productName,
    @NotBlank @Pattern(
      regexp = "SAVINGS|CURRENT|DEPOSIT|LOAN"
    ) String productType,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currencyCode,
    @NotBlank @Size(max = 80) String businessOwnerRef,
    @Size(max = 80) String productManagerRef,
    @Size(max = 80) String supportRef,
    @Size(max = 2000) String description,
    @NotNull OffsetDateTime salesStartAt,
    OffsetDateTime salesEndAt
  ) {}

  public record DraftInput(
    BigDecimal sourceVersionId,
    @NotNull OffsetDateTime effectiveFromAt,
    OffsetDateTime effectiveToAt,
    @NotBlank @Pattern(regexp = "ALLOW|BLOCK") String defaultTxnAction,
    @NotBlank @Size(max = 1000) String changeReason
  ) {}

  public record DraftEdit(
    @Min(1) long rowVersion,
    @NotNull OffsetDateTime effectiveFromAt,
    OffsetDateTime effectiveToAt,
    @NotBlank @Pattern(regexp = "ALLOW|BLOCK") String defaultTxnAction,
    @NotBlank @Size(max = 1000) String changeReason
  ) {}

  public record RuleInput(
    @Min(1) long rowVersion,
    @NotNull Map<String, Object> values
  ) {}

  public record Revision(
    @Min(1) long rowVersion,
    @NotBlank @Size(max = 1000) String reason,
    @Size(max = 2000) String impactSummary
  ) {}

  public record Decision(
    @Min(1) long rowVersion,
    @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
    @NotBlank @Size(max = 1000) String comment
  ) {}

  public record Lifecycle(
    @Min(1) long rowVersion,
    @NotBlank @Pattern(regexp = "SUSPEND|RETIRE|REACTIVATE") String action,
    @NotBlank @Size(max = 1000) String reason
  ) {}

  public record Treatment(
    @NotNull BigDecimal sourceVersionId,
    @NotNull BigDecimal targetVersionId,
    @NotBlank @Pattern(
      regexp = "GRANDFATHER|SCHEDULED|RENEWAL|MANDATORY|OPT_IN"
    ) String treatmentCode,
    OffsetDateTime migrationFromAt,
    boolean consentRequired,
    @NotBlank @Size(max = 1000) String reason
  ) {}

  public record Eligibility(
    @NotBlank @Size(max = 36) String cifId,
    @NotBlank @Size(max = 40) String channel,
    OffsetDateTime at,
    @DecimalMin("0") BigDecimal openingAmount,
    @DecimalMin("0") BigDecimal loanAmount,
    @Min(1) Integer tenureMonths
  ) {}
}
