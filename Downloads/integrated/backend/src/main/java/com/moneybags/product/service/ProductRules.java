package com.moneybags.product.service;

import com.moneybags.common.api.BusinessException;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.http.HttpStatus;

/** Typed schema normalization and business validation. No expressions or scripts are evaluated. */
public final class ProductRules {

  private ProductRules() {}

  /** Rejects unknown/generated fields and applies the schema's types, lengths and precision. */
  public static Map<String, Object> normalize(
    ProductRuleCatalog.Family family,
    Map<String, Object> values
  ) {
    Map<String, Object> result = new LinkedHashMap<>();
    var allowed = family
      .fields()
      .stream()
      .map(ProductRuleCatalog.Field::name)
      .toList();
    for (String key : values.keySet())
      if (!allowed.contains(key)) throw invalid("Unknown rule field: " + key);
    for (var f : family.fields()) {
      Object value = values.get(f.name());
      String definition = f.definition();
      if (value instanceof String s && s.isBlank()) value = null;
      if (
        value == null &&
        !f.nullable() &&
        (values.containsKey(f.name()) || !definition.contains("DEFAULT"))
      ) throw invalid(f.name() + " is required");
      if (value == null) {
        if (values.containsKey(f.name())) result.put(f.name(), null);
        continue;
      }
      try {
        value = switch (f.javaType()) {
          case "BigDecimal" -> new BigDecimal(value.toString());
          case "Long" -> new BigDecimal(value.toString()).longValueExact();
          case "Integer" -> new BigDecimal(value.toString()).intValueExact();
          case "OffsetDateTime" -> OffsetDateTime.parse(value.toString());
          case "LocalDate" -> LocalDate.parse(value.toString());
          default -> value.toString().trim();
        };
      } catch (Exception e) {
        throw invalid(f.name() + " has an invalid " + f.javaType() + " value");
      }
      if (value instanceof String s) {
        var m = Pattern.compile("(?:VARCHAR2|CHAR)\\((\\d+)").matcher(
          definition
        );
        if (
          m.find() &&
          s.codePointCount(0, s.length()) > Integer.parseInt(m.group(1))
        ) throw invalid(f.name() + " is too long");
      }
      if (value instanceof Number) {
        var m = Pattern.compile("NUMBER\\((\\d+)(?:,(\\d+))?\\)").matcher(
          definition
        );
        if (m.find()) {
          BigDecimal d = new BigDecimal(value.toString());
          int scale = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
          try {
            d = d.setScale(scale, RoundingMode.UNNECESSARY);
          } catch (Exception e) {
            throw invalid(f.name() + " exceeds decimal scale " + scale);
          }
          if (d.precision() > Integer.parseInt(m.group(1))) throw invalid(
            f.name() + " exceeds numeric precision"
          );
        }
      }
      result.put(f.name(), value);
    }
    List<String> errors = new ArrayList<>();
    check(family.code(), result, errors);
    if (!errors.isEmpty()) throw invalid(String.join("; ", errors));
    return result;
  }

  /** Rule-family invariants augment the SQL checks before data is saved. */
  public static void check(
    String family,
    Map<String, Object> r,
    List<String> errors
  ) {
    for (var e : r.entrySet())
      if (
        e.getValue() != null &&
        e.getValue() instanceof Number &&
        !e.getKey().equals("SPREAD_PCT") &&
        decimal(e.getValue()).signum() < 0
      ) errors.add(e.getKey() + " must be nonnegative");
    range(r, "MIN_AMOUNT", "MAX_AMOUNT", errors);
    range(r, "MIN_VALUE", "MAX_VALUE", errors);
    range(r, "MIN_LOAN_AMOUNT", "MAX_LOAN_AMOUNT", errors);
    range(r, "MIN_DEPOSIT_AMOUNT", "MAX_DEPOSIT_AMOUNT", errors);
    range(r, "MIN_TENURE_MONTHS", "MAX_TENURE_MONTHS", errors);
    dates(r, "EFFECTIVE_FROM_AT", "EFFECTIVE_TO_AT", errors);
    dates(r, "OFFER_FROM_AT", "OFFER_TO_AT", errors);
    dates(r, "VALID_FROM", "VALID_TO", errors);
    if (family.equals("term")) {
      if (r.get("MIN_DEPOSIT_AMOUNT") != null && decimal(r.get("MIN_DEPOSIT_AMOUNT")).signum() <= 0)
        errors.add("Minimum fixed deposit amount must be positive");
      if (r.get("MIN_TENURE_MONTHS") != null && decimal(r.get("MIN_TENURE_MONTHS")).signum() <= 0)
        errors.add("Minimum fixed deposit term must be positive");
    }
    switch (family) {
      case "interest" -> {
        choice(r, "INTEREST_TYPE", errors, "FIXED", "FLOATING");
        choice(
          r,
          "INTEREST_METHOD",
          errors,
          "SIMPLE",
          "COMPOUND",
          "TIERED",
          "REDUCING_BALANCE",
          "FLAT"
        );
        choice(
          r,
          "DAY_COUNT_BASIS",
          errors,
          "ACT_365",
          "ACT_360",
          "ACT_ACT",
          "30_360"
        );
        choice(
          r,
          "ROUNDING_MODE",
          errors,
          "HALF_UP",
          "HALF_EVEN",
          "DOWN",
          "UP"
        );
        if (
          "FIXED".equals(r.get("INTEREST_TYPE")) &&
          (r.get("FIXED_RATE_PCT") == null ||
            r.get("RATE_INDEX_CODE") != null ||
            r.get("SPREAD_PCT") != null)
        ) errors.add("Fixed interest needs only FIXED_RATE_PCT");
        if (
          "FLOATING".equals(r.get("INTEREST_TYPE")) &&
          (r.get("FIXED_RATE_PCT") != null ||
            r.get("RATE_INDEX_CODE") == null ||
            r.get("SPREAD_PCT") == null)
        ) errors.add(
          "Floating interest needs index and spread, without fixed rate"
        );
      }
      case "tiers" -> {
        if (
          r.get("AMOUNT_TO") != null &&
          decimal(r.get("AMOUNT_TO")).compareTo(
            decimal(r.get("AMOUNT_FROM"))
          ) <=
          0
        ) errors.add("Tier end must exceed start");
      }
      case "fees" -> {
        choice(r, "CHARGE_BASIS", errors, "FIXED", "RATE");
        basis(r, "CHARGE_BASIS", errors);
      }
      case "penalties" -> {
        choice(r, "FORMULA_CODE", errors, "FIXED", "RATE");
        basis(r, "FORMULA_CODE", errors);
      }
      case "eligibility" -> {
        choice(
          r,
          "ATTRIBUTE_CODE",
          errors,
          "AGE",
          "KYC_STATUS",
          "SEGMENT",
          "PARTY_TYPE",
          "COUNTRY",
          "RISK_LEVEL",
          "INCORPORATED_ON"
        );
        choice(
          r,
          "OPERATOR_CODE",
          errors,
          "EQ",
          "NE",
          "GT",
          "GE",
          "LT",
          "LE",
          "IN",
          "NOT_IN"
        );
        choice(r, "VALUE_TYPE", errors, "TEXT", "NUMBER", "DATE", "BOOLEAN");
        String type = Objects.toString(r.get("VALUE_TYPE"), "");
        String field = "VALUE_" + type;
        long count = List.of(
          "VALUE_TEXT",
          "VALUE_NUMBER",
          "VALUE_DATE",
          "VALUE_BOOLEAN"
        )
          .stream()
          .filter(k -> r.get(k) != null)
          .count();
        if (count != 1 || r.get(field) == null) errors.add(
          "Exactly one value matching VALUE_TYPE is required"
        );
        if (
          "AGE".equals(r.get("ATTRIBUTE_CODE")) && !"NUMBER".equals(type)
        ) errors.add("AGE requires NUMBER");
        if (
          "INCORPORATED_ON".equals(r.get("ATTRIBUTE_CODE")) &&
          !"DATE".equals(type)
        ) errors.add("INCORPORATED_ON requires DATE");
        if (
          !Set.of("AGE", "INCORPORATED_ON").contains(
            Objects.toString(r.get("ATTRIBUTE_CODE"), "")
          ) &&
          !"TEXT".equals(type)
        ) errors.add("This attribute requires TEXT");
        if (
          Set.of("IN", "NOT_IN").contains(r.get("OPERATOR_CODE")) &&
          !"TEXT".equals(type)
        ) errors.add("IN operators require comma-separated TEXT");
      }
      case "account" -> {
        flags(r, errors, "MINOR_ALLOWED", "JOINT_ALLOWED", "NOMINEE_REQUIRED");
        String minor = Objects.toString(r.getOrDefault("MINOR_ALLOWED", "N"));
        if (
          (minor.equals("Y") && r.get("MINOR_DAILY_DEBIT_LIMIT") == null) ||
          (minor.equals("N") && r.get("MINOR_DAILY_DEBIT_LIMIT") != null)
        ) errors.add("Minor debit limit must correspond to MINOR_ALLOWED");
        if (
          !"Y".equals(r.get("JOINT_ALLOWED")) &&
          r.get("MAX_HOLDERS") != null &&
          decimal(r.get("MAX_HOLDERS")).compareTo(BigDecimal.ONE) != 0
        ) errors.add("Non-joint products need one holder");
      }
      case "loan" -> {
        choice(
          r,
          "LOAN_CATEGORY",
          errors,
          "PERSONAL",
          "HOME",
          "VEHICLE",
          "EDUCATION",
          "BUSINESS",
          "OTHER"
        );
        choice(r, "ALLOWED_INTEREST_TYPE", errors, "FIXED", "FLOATING", "BOTH");
        choice(
          r,
          "REPAYMENT_FREQUENCY",
          errors,
          "WEEKLY",
          "FORTNIGHTLY",
          "MONTHLY",
          "QUARTERLY",
          "BULLET"
        );
        choice(
          r,
          "AMORTIZATION_METHOD",
          errors,
          "REDUCING_BALANCE",
          "FLAT",
          "BULLET",
          "CUSTOM"
        );
        choice(r, "DISBURSEMENT_MODE", errors, "SINGLE", "TRANCHE", "BOTH");
        flags(
          r,
          errors,
          "COLLATERAL_REQUIRED",
          "GUARANTOR_REQUIRED",
          "PREPAYMENT_ALLOWED",
          "PARTIAL_PREPAYMENT_ALLOWED",
          "FORECLOSURE_ALLOWED"
        );
        if (
          "Y".equals(r.get("PARTIAL_PREPAYMENT_ALLOWED")) &&
          "N".equals(r.get("PREPAYMENT_ALLOWED"))
        ) errors.add("Partial prepayment requires prepayment");
      }
      case "transactions" -> {
        choice(r, "ACTION_CODE", errors, "ALLOW", "BLOCK");
        choice(r, "DIRECTION_CODE", errors, "DEBIT", "CREDIT", "BOTH");
      }
      case "allocation" -> {
        choice(r, "ALLOCATION_FLOW", errors, "DEBIT", "CREDIT");
        choice(r, "PAYMENT_KIND", errors, "REGULAR", "PREPAYMENT", "RECOVERY");
        choice(
          r,
          "COMPONENT_CODE",
          errors,
          "PENALTY",
          "FEE",
          "INTEREST",
          "PRINCIPAL",
          "UNAPPLIED"
        );
        choice(
          r,
          "FUTURE_INSTALLMENT_RULE",
          errors,
          "NEXT_DUE",
          "EARLIEST",
          "LATEST"
        );
      }
    }
    if (r.get("CURRENCY_CODE") != null) try {
      Currency.getInstance(r.get("CURRENCY_CODE").toString());
    } catch (Exception e) {
      errors.add("Unknown currency");
    }
  }

  /** Interest bands are half-open: [from,to); contiguous boundaries are permitted. */
  public static boolean overlaps(
    BigDecimal a,
    BigDecimal b,
    BigDecimal c,
    BigDecimal d
  ) {
    return (
      (b == null || c.compareTo(b) < 0) && (d == null || a.compareTo(d) < 0)
    );
  }

  public static BigDecimal decimal(Object n) {
    return new BigDecimal(n.toString());
  }

  private static void range(
    Map<String, Object> r,
    String min,
    String max,
    List<String> e
  ) {
    if (
      r.get(min) != null &&
      r.get(max) != null &&
      decimal(r.get(min)).compareTo(decimal(r.get(max))) > 0
    ) e.add(min + " exceeds " + max);
  }

  private static void dates(
    Map<String, Object> r,
    String from,
    String to,
    List<String> e
  ) {
    if (
      r.get(to) != null &&
      (r.get(from) == null ||
        !OffsetDateTime.parse(r.get(to).toString()).isAfter(
          OffsetDateTime.parse(r.get(from).toString())
        ))
    ) e.add(to + " must follow " + from);
  }

  private static void choice(
    Map<String, Object> r,
    String k,
    List<String> e,
    String... values
  ) {
    if (
      r.get(k) != null && !List.of(values).contains(r.get(k).toString())
    ) e.add("Unsupported " + k);
  }

  private static void flags(
    Map<String, Object> r,
    List<String> e,
    String... fields
  ) {
    for (String f : fields) choice(r, f, e, "Y", "N");
  }

  private static void basis(Map<String, Object> r, String k, List<String> e) {
    if (
      "FIXED".equals(r.get(k)) &&
      (r.get("FIXED_AMOUNT") == null || r.get("RATE_PCT") != null)
    ) e.add("FIXED requires only FIXED_AMOUNT");
    if (
      "RATE".equals(r.get(k)) &&
      (r.get("RATE_PCT") == null || r.get("FIXED_AMOUNT") != null)
    ) e.add("RATE requires only RATE_PCT");
  }

  public static BusinessException invalid(String message) {
    return new BusinessException(
      HttpStatus.BAD_REQUEST,
      "INVALID_RULE",
      message
    );
  }
}
