package com.moneybags.cif.dto;

import jakarta.validation.constraints.*;
import java.time.*;

/** Explicit API inputs; ciphertext and internal document paths are never client fields. */
public final class CifDtos {

  private CifDtos() {}

  public record CustomerInput(
    @NotBlank @Pattern(regexp = "INDIVIDUAL|ORGANIZATION") String partyType,
    @NotBlank @Size(max = 300) String legalName,
    LocalDate dateOfBirth,
    LocalDate incorporatedOn,
    @NotBlank @Size(max = 80) String homeBranchRef,
    @Size(max = 40) String segmentCode
  ) {}

  public record Revision(
    @Min(1) long rowVersion,
    @NotBlank @Size(max = 1000) String reason
  ) {}

  public record ProfileInput(
    @Min(1) long rowVersion,
    @NotBlank @Size(max = 300) String legalName,
    @Size(max = 40) String segmentCode,
    @NotBlank @Size(max = 1000) String reason
  ) {}

  public record ContactInput(
    @Pattern(regexp = "MOBILE|EMAIL") @NotBlank String contactType,
    @NotBlank @Size(max = 240) String value
  ) {}

  public record IdentifierInput(
    @NotBlank @Size(max = 40) String identifierType,
    @NotBlank @Size(max = 200) String value,
    @Size(max = 80) String issuerCode,
    LocalDate expiresOn
  ) {}

  public record AddressInput(
    @Pattern(
      regexp = "RESIDENTIAL|MAILING|REGISTERED"
    ) @NotBlank String addressType,
    @NotBlank @Size(max = 240) String line1,
    @Size(max = 240) String line2,
    @NotBlank @Size(max = 120) String city,
    @Size(max = 120) String region,
    @Size(max = 24) String postalCode,
    @Pattern(regexp = "[A-Z]{2}") @NotBlank String countryCode,
    @Size(max = 36) String evidenceDocumentId
  ) {}

  public record CaseInput(
    @Pattern(
      regexp = "ONBOARDING|PERIODIC|REMEDIATION"
    ) @NotBlank String caseType
  ) {}

  public record Assignment(
    @Min(1) long rowVersion,
    @NotBlank @Size(max = 36) String officerUserId
  ) {}

  public record ReviewInput(
    @Min(1) long rowVersion,
    @Pattern(regexp = "APPROVE|REJECT|REQUEST_INFO") @NotBlank String outcome,
    @Pattern(regexp = "LOW|MEDIUM|HIGH") String riskLevel,
    OffsetDateTime reviewDueAt,
    @NotBlank @Size(max = 80) String reasonCode,
    @NotBlank @Size(max = 2000) String comments
  ) {}

  public record DocumentReview(
    @Pattern(regexp = "CLEAN|QUARANTINED|FAILED") @NotBlank String scanStatus,
    @Pattern(regexp = "VERIFIED|REJECTED") @NotBlank String verificationStatus,
    @NotBlank @Size(max = 1000) String reason
  ) {}

  public record StatusInput(
    @Min(1) long rowVersion,
    @Pattern(
      regexp = "ACTIVE|RESTRICTED|DORMANT|CLOSED"
    ) @NotBlank String status,
    @NotBlank @Size(max = 1000) String reason
  ) {}

  public record ConsentInput(
    @NotBlank @Size(max = 80) String purposeCode,
    @NotBlank @Size(max = 40) String captureChannel,
    @NotBlank @Size(max = 240) String evidenceRef
  ) {}

  public record RelationshipInput(
    @NotBlank @Size(max = 36) String targetCifId,
    @NotBlank @Size(max = 32) String relationshipType,
    @Pattern(
      regexp = "NONE|VIEW|TRANSACT|REPRESENT"
    ) @NotBlank String operatingAuthority,
    OffsetDateTime validTo
  ) {}
}
