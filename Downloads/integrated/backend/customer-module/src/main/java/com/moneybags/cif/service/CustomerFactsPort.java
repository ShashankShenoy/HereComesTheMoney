package com.moneybags.cif.service;

import java.time.LocalDate;

/** Stable, minimal contract consumed by Product/Account/Loan modules; no documents or identifiers. */
public interface CustomerFactsPort {
  record Facts(
    String cifId,
    String status,
    String kycStatus,
    String branch,
    String segment,
    String risk,
    String partyType,
    LocalDate dateOfBirth,
    LocalDate incorporatedOn,
    String countryCode
  ) {}

  Facts facts(String cifId);
}
