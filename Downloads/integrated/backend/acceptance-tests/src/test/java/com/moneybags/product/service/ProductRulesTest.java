package com.moneybags.product.service;

import static org.junit.jupiter.api.Assertions.*;

import com.moneybags.common.api.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductRulesTest {
  @Test void notInEligibilityRejectsEmptyListMembers() {
    var error = assertThrows(BusinessException.class, () -> ProductRules.normalize(
      ProductRuleCatalog.FAMILIES.get("eligibility"),
      Map.of("RULE_CODE", "SEGMENT_EXCLUSION", "ATTRIBUTE_CODE", "SEGMENT",
        "OPERATOR_CODE", "NOT_IN", "VALUE_TYPE", "TEXT", "VALUE_TEXT", "VIP, ",
        "FAILURE_REASON_CODE", "SEGMENT_BLOCKED", "SEQUENCE_NO", 1)
    ));
    assertEquals("INVALID_RULE", error.code());
    assertTrue(error.getMessage().contains("List values cannot be blank"));
  }
}
