package com.moneybags.product.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/** Read contract for future account/loan adoption; callers must also enforce their own resource rules. */
public interface ProductDefinitionsPort {
  Map<String, Object> resolve(
    BigDecimal productId,
    String branch,
    String segment,
    String channel,
    String currency,
    OffsetDateTime at
  );
  Map<String, Object> historical(BigDecimal versionId);

  /** Rechecks authoritative CIF facts against the approved version before adoption. */
  void requireCustomerEligible(
    BigDecimal productId,
    BigDecimal versionId,
    String cifId,
    OffsetDateTime at
  );
}
