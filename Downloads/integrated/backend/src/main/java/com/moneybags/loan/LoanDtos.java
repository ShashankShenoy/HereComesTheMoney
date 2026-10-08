package com.moneybags.loan;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Public loan API shapes. Oracle rows and sensitive evidence are never returned wholesale. */
public final class LoanDtos {
    private LoanDtos() {}
    public record CreateApplication(@NotBlank @Size(max=36) String cifId,
        @NotNull @Positive Long productId,@NotNull @Positive Long productVersionId,
        @NotBlank @Size(max=80) String branchCode,@NotBlank @Size(max=20) String channelCode,
        @NotNull @DecimalMin("0.01") BigDecimal amount,@NotNull @Min(1) @Max(9999) Integer tenureMonths,
        @NotBlank @Size(max=40) String purposeCode) {}
    /** Customer input excludes branch and channel; the service derives both from the linked CIF. */
    public record SelfApplication(@NotBlank @Size(max=36) String cifId,
        @NotNull @Positive Long productId,@NotNull @Positive Long productVersionId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,@NotNull @Min(1) @Max(9999) Integer tenureMonths,
        @NotBlank @Size(max=40) String purposeCode) {}
    public record Application(long id,String number,String cifId,long productId,long productVersionId,
        BigDecimal amount,int tenureMonths,String purposeCode,String branchCode,String status,
        Long revisionId,String assignedTo,OffsetDateTime createdAt) {}
    public record Assessment(@NotNull @DecimalMin("0") BigDecimal monthlyIncome,
        @NotNull @DecimalMin("0") BigDecimal monthlyObligations,
        @NotNull @Pattern(regexp="APPROVE|REJECT|REFER") String recommendation,
        @NotBlank @Size(max=1000) String reasonCodes,@Min(0) @Max(999) Integer creditScore,
        @Size(max=200) String creditEvidenceRef) {}
    public record Decision(@NotNull @Pattern(regexp="APPROVE|REJECT|REFER") String code,
        @NotBlank @Size(max=1000) String reasonCodes,
        @DecimalMin("0.01") BigDecimal sanctionedAmount,@Min(1) Integer sanctionedTenureMonths,
        @DecimalMin("0") BigDecimal annualRatePct,@NotBlank @Size(max=80) String authorityCode,
        @NotBlank @Size(max=100) String authorityEvidenceRef) {}
    public record Offer(@NotBlank @Size(max=300) String documentRef,@NotNull OffsetDateTime expiresAt) {}
    public record AcceptOffer(@NotNull @Positive Long disbursementAccountId,
        @NotNull @Positive Long repaymentAccountId) {}
    public record Document(@NotBlank @Size(max=40) String type,
        @NotBlank @Size(max=500) String storageReference,
        @NotBlank @Pattern(regexp="[a-fA-F0-9]{64}") String sha256Hex) {}
}
