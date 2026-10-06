package com.moneybags.product.service;

import static com.moneybags.common.database.BusinessRepository.*;
import static com.moneybags.iam.repository.IamRepository.map;

import com.moneybags.cif.service.CustomerFactsPort;
import com.moneybags.common.api.*;
import com.moneybags.common.database.*;
import com.moneybags.common.security.*;
import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.product.dto.ProductDtos.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable commercial definitions, independent approval and deterministic context resolution. */
@Service
@Transactional
public class ProductService implements ProductDefinitionsPort {

  private final BusinessRepository db;
  private final DomainAccess access;
  private final CustomerFactsPort customers;
  private final Clock clock;

  public ProductService(
    BusinessRepository db,
    DomainAccess access,
    CustomerFactsPort customers,
    Clock clock
  ) {
    this.db = db;
    this.access = access;
    this.customers = customers;
    this.clock = clock;
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  private static void conflict(boolean when, String code, String message) {
    if (when) throw new BusinessException(HttpStatus.CONFLICT, code, message);
  }

  private Map<String, Object> product(BigDecimal id, boolean lock) {
    return db.one(
      "SELECT * FROM M03_PM_PRODUCT WHERE PRODUCT_ID=?" +
        (lock ? " FOR UPDATE" : ""),
      id
    );
  }

  private Map<String, Object> versionRow(BigDecimal id, boolean lock) {
    if (lock) {
      var row = versionRow(id, false);
      product(decimal(row.get("PRODUCT_ID")), true);
    }
    return db.one(
      "SELECT * FROM M03_PM_PRODUCT_VERSION WHERE PRODUCT_VERSION_ID=?" +
        (lock ? " FOR UPDATE" : ""),
      id
    );
  }

  private void permit(
    UserPrincipal u,
    String permission,
    Map<String, Object> p,
    String branch,
    String maker
  ) {
    access.require(
      u,
      permission,
      branch,
      null,
      str(p, "PRODUCT_TYPE"),
      str(p, "CURRENCY_CODE"),
      maker
    );
  }

  /** All administration evaluates product type/currency plus the explicit branch context. */
  public List<Map<String, Object>> list(
    UserPrincipal u,
    String search,
    String branch
  ) {
    return db
      .rows(
        "SELECT * FROM M03_PM_PRODUCT WHERE (? IS NULL OR UPPER(PRODUCT_CODE) LIKE ? OR UPPER(PRODUCT_NAME) LIKE ?) ORDER BY PRODUCT_CODE",
        search == null || search.isBlank() ? null : search,
        "%" + Objects.toString(search, "").toUpperCase(Locale.ROOT) + "%",
        "%" + Objects.toString(search, "").toUpperCase(Locale.ROOT) + "%"
      )
      .stream()
      .filter(p ->
        access.allowed(
          u,
          "PRODUCT_READ",
          branch,
          null,
          str(p, "PRODUCT_TYPE"),
          str(p, "CURRENCY_CODE")
        )
      )
      .limit(200)
      .toList();
  }

  /** Product identity does not itself make any version available for adoption. */
  public Map<String, Object> create(
    UserPrincipal u,
    ProductInput in,
    String branch
  ) {
    access.require(
      u,
      "PRODUCT_CREATE",
      branch,
      null,
      in.productType(),
      in.currencyCode(),
      null
    );
    dates(in.salesStartAt(), in.salesEndAt());
    try {
      Currency.getInstance(in.currencyCode());
    } catch (Exception e) {
      throw ProductRules.invalid("Unknown currency");
    }
    conflict(
      db.count(
          "SELECT COUNT(*) FROM M03_PM_PRODUCT WHERE UPPER(PRODUCT_CODE)=?",
          in.productCode()
        ) >
        0,
      "PRODUCT_CODE_EXISTS",
      "Use a unique stable product code"
    );
    Number id = db.key(
      SchemaTable.M03_PM_PRODUCT,
      map(
        "PRODUCT_CODE",
        in.productCode(),
        "PRODUCT_NAME",
        in.productName(),
        "PRODUCT_TYPE",
        in.productType(),
        "CURRENCY_CODE",
        in.currencyCode(),
        "BUSINESS_OWNER_REF",
        in.businessOwnerRef(),
        "PRODUCT_MANAGER_REF",
        in.productManagerRef(),
        "SUPPORT_REF",
        in.supportRef(),
        "DESCRIPTION",
        in.description(),
        "SALES_START_AT",
        in.salesStartAt(),
        "SALES_END_AT",
        in.salesEndAt(),
        "CREATED_BY_USER_ID",
        u.userId()
      ),
      "PRODUCT_ID"
    );
    event(u, "ProductCreated", decimal(id), null, null);
    return detail(u, decimal(id), branch);
  }

  /** Draft cloning copies rules, rebinding tier parents rather than copying generated identities. */
  public Map<String, Object> draft(
    UserPrincipal u,
    BigDecimal productId,
    DraftInput in,
    String branch
  ) {
    var p = product(productId, true);
    permit(u, "PRODUCT_CREATE", p, branch, null);
    conflict(
      "RETIRED".equals(str(p, "STATUS")),
      "PRODUCT_RETIRED",
      "Retired products cannot create drafts"
    );
    dates(in.effectiveFromAt(), in.effectiveToAt());
    if (in.sourceVersionId() != null) {
      var source = versionRow(in.sourceVersionId(), false);
      conflict(
        decimal(source.get("PRODUCT_ID")).compareTo(productId) != 0,
        "SOURCE_PRODUCT_MISMATCH",
        "Source version belongs to another product"
      );
      conflict(
        "DRAFT".equals(str(source, "VERSION_STATE")),
        "SOURCE_NOT_APPROVED",
        "Clone an approved or historical version"
      );
    }
    long next =
      db.count(
        "SELECT NVL(MAX(VERSION_NO),0) FROM M03_PM_PRODUCT_VERSION WHERE PRODUCT_ID=?",
        productId
      ) +
      1;
    BigDecimal v = decimal(
      db.key(
        SchemaTable.M03_PM_PRODUCT_VERSION,
        map(
          "PRODUCT_ID",
          productId,
          "VERSION_NO",
          next,
          "SOURCE_VERSION_ID",
          in.sourceVersionId(),
          "EFFECTIVE_FROM_AT",
          in.effectiveFromAt(),
          "EFFECTIVE_TO_AT",
          in.effectiveToAt(),
          "DEFAULT_TXN_ACTION",
          in.defaultTxnAction(),
          "CHANGE_REASON",
          in.changeReason(),
          "CREATED_BY_USER_ID",
          u.userId()
        ),
        "PRODUCT_VERSION_ID"
      )
    );
    if (in.sourceVersionId() != null) cloneRules(in.sourceVersionId(), v);
    event(u, "ProductDraftCreated", productId, v, in.changeReason());
    return versionDetail(u, v, branch);
  }

  private void cloneRules(BigDecimal source, BigDecimal target) {
    Map<BigDecimal, Number> interestIds = new HashMap<>();
    for (var f : ProductRuleCatalog.FAMILIES.values()) {
      for (var row : rules(source, f)) {
        var values = new LinkedHashMap<String, Object>();
        for (var field : f.fields())
          if (row.containsKey(field.name())) values.put(
            field.name(),
            row.get(field.name())
          );
        if (f.code().equals("tiers")) values.put(
          "INTEREST_RULE_ID",
          interestIds.get(decimal(row.get("INTEREST_RULE_ID")))
        );
        else values.put("PRODUCT_VERSION_ID", target);
        if (f.key().equals("PRODUCT_VERSION_ID")) db.insert(
          SchemaTable.valueOf(f.table()),
          values
        );
        else {
          Number key = db.key(SchemaTable.valueOf(f.table()), values, f.key());
          if (f.code().equals("interest")) interestIds.put(
            decimal(row.get(f.key())),
            key
          );
        }
      }
    }
  }

  /** Returns the catalogue's history; unpublished version data remains permissioned. */
  public Map<String, Object> detail(
    UserPrincipal u,
    BigDecimal productId,
    String branch
  ) {
    var p = product(productId, false);
    permit(u, "PRODUCT_READ", p, branch, null);
    return map(
      "product",
      p,
      "versions",
      db.rows(
        "SELECT * FROM M03_PM_PRODUCT_VERSION WHERE PRODUCT_ID=? ORDER BY VERSION_NO DESC",
        productId
      ),
      "approvals",
      db.rows(
        "SELECT * FROM M03_PM_APPROVAL WHERE PRODUCT_ID=? ORDER BY SUBMITTED_AT DESC",
        productId
      ),
      "treatments",
      db.rows(
        "SELECT * FROM M03_PM_VERSION_TREATMENT WHERE PRODUCT_ID=?",
        productId
      )
    );
  }

  public Map<String, Object> versionDetail(
    UserPrincipal u,
    BigDecimal versionId,
    String branch
  ) {
    var v = versionRow(versionId, false);
    var p = product(decimal(v.get("PRODUCT_ID")), false);
    permit(u, "PRODUCT_READ", p, branch, null);
    var snapshot = allRules(versionId);
    return map(
      "product",
      p,
      "version",
      v,
      "rules",
      snapshot,
      "approvals",
      db.rows(
        "SELECT * FROM M03_PM_APPROVAL WHERE PRODUCT_VERSION_ID=? ORDER BY SUBMITTED_AT DESC",
        versionId
      ),
      "validation",
      validate(p, snapshot)
    );
  }

  private Map<String, Object> editable(
    UserPrincipal u,
    BigDecimal versionId,
    long expected,
    String branch
  ) {
    var v = versionRow(versionId, true);
    permit(
      u,
      "PRODUCT_CREATE",
      product(decimal(v.get("PRODUCT_ID")), false),
      branch,
      null
    );
    version(v, expected);
    conflict(
      !"DRAFT".equals(str(v, "VERSION_STATE")),
      "VERSION_IMMUTABLE",
      "Approved versions cannot be edited; create a replacement draft"
    );
    conflict(
      !u.userId().equals(str(v, "CREATED_BY_USER_ID")),
      "DRAFT_MAKER_ONLY",
      "Only the draft maker may change or submit it"
    );
    conflict(
      db.count(
          "SELECT COUNT(*) FROM M03_PM_APPROVAL WHERE PRODUCT_VERSION_ID=? AND REQUEST_STATUS='PENDING'",
          versionId
        ) >
        0,
      "VERSION_FROZEN",
      "Withdraw or decide the pending request before editing"
    );
    return v;
  }

  /** Revision dates are mutable only before submission and approval. */
  public Map<String, Object> edit(
    UserPrincipal u,
    BigDecimal versionId,
    DraftEdit in,
    String branch
  ) {
    var v = editable(u, versionId, in.rowVersion(), branch);
    dates(in.effectiveFromAt(), in.effectiveToAt());
    db
      .jdbc()
      .update(
        "UPDATE M03_PM_PRODUCT_VERSION SET EFFECTIVE_FROM_AT=?,EFFECTIVE_TO_AT=?,DEFAULT_TXN_ACTION=?,CHANGE_REASON=?,UPDATED_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE PRODUCT_VERSION_ID=?",
        in.effectiveFromAt(),
        in.effectiveToAt(),
        in.defaultTxnAction(),
        in.changeReason(),
        now(),
        versionId
      );
    event(
      u,
      "ProductDraftEdited",
      decimal(v.get("PRODUCT_ID")),
      versionId,
      in.changeReason()
    );
    return versionDetail(u, versionId, branch);
  }

  private static ProductRuleCatalog.Family family(String code) {
    var f = ProductRuleCatalog.FAMILIES.get(code);
    if (f == null) throw ProductRules.invalid("Unknown rule family");
    return f;
  }

  private List<Map<String, Object>> rules(
    BigDecimal versionId,
    ProductRuleCatalog.Family f
  ) {
    if (f.code().equals("tiers")) return db.rows(
      "SELECT T.*,R.RULE_CODE PARENT_RULE_CODE FROM M03_PM_INTEREST_TIER T JOIN M03_PM_INTEREST_RULE R ON R.INTEREST_RULE_ID=T.INTEREST_RULE_ID WHERE R.PRODUCT_VERSION_ID=? ORDER BY R.RULE_CODE,T.TIER_NO",
      versionId
    );
    return db.rows(
      "SELECT * FROM " +
        f.table() +
        " WHERE PRODUCT_VERSION_ID=? ORDER BY " +
        f.key(),
      versionId
    );
  }

  private Map<String, Object> allRules(BigDecimal v) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var f : ProductRuleCatalog.FAMILIES.values())
      result.put(f.code(), rules(v, f));
    return result;
  }

  /** Only the named family is writable; schema types and semantic checks reject unsafe configuration. */
  public Map<String, Object> addRule(
    UserPrincipal u,
    BigDecimal vId,
    String code,
    RuleInput in,
    String branch
  ) {
    var v = editable(u, vId, in.rowVersion(), branch);
    var f = family(code);
    var values = ProductRules.normalize(f, in.values());
    if (code.equals("tiers")) conflict(
      db.count(
          "SELECT COUNT(*) FROM M03_PM_INTEREST_RULE WHERE INTEREST_RULE_ID=? AND PRODUCT_VERSION_ID=?",
          values.get("INTEREST_RULE_ID"),
          vId
        ) ==
        0,
      "TIER_PARENT_MISMATCH",
      "Select an interest rule on this version"
    );
    else values.put("PRODUCT_VERSION_ID", vId);
    String type = str(
      product(decimal(v.get("PRODUCT_ID")), false),
      "PRODUCT_TYPE"
    );
    conflict(
      code.equals("account") && !Set.of("SAVINGS", "CURRENT").contains(type),
      "RULE_PRODUCT_MISMATCH",
      "Account terms apply only to savings/current"
    );
    conflict(
      (code.equals("loan") || code.equals("allocation")) &&
        !type.equals("LOAN"),
      "RULE_PRODUCT_MISMATCH",
      "Loan terms apply only to loan products"
    );
    if (f.key().equals("PRODUCT_VERSION_ID")) {
      db
        .jdbc()
        .update(
          "DELETE FROM " + f.table() + " WHERE PRODUCT_VERSION_ID=?",
          vId
        );
      db.insert(SchemaTable.valueOf(f.table()), values);
    } else db.key(SchemaTable.valueOf(f.table()), values, f.key());
    bump(vId);
    event(u, "ProductRuleAdded", decimal(v.get("PRODUCT_ID")), vId, code);
    return versionDetail(u, vId, branch);
  }

  /** A draft rule is removed explicitly; approved rule history is never deleted. */
  public Map<String, Object> deleteRule(
    UserPrincipal u,
    BigDecimal vId,
    String code,
    BigDecimal key,
    long expected,
    String branch
  ) {
    var v = editable(u, vId, expected, branch);
    var f = family(code);
    if (code.equals("interest")) {
      conflict(
        db.count(
            "SELECT COUNT(*) FROM M03_PM_INTEREST_RULE WHERE INTEREST_RULE_ID=? AND PRODUCT_VERSION_ID=?",
            key,
            vId
          ) ==
          0,
        "RULE_NOT_FOUND",
        "Rule does not belong to this draft"
      );
      db
        .jdbc()
        .update(
          "DELETE FROM M03_PM_INTEREST_TIER WHERE INTEREST_RULE_ID=?",
          key
        );
    }
    int n = code.equals("tiers")
      ? db
          .jdbc()
          .update(
            "DELETE FROM M03_PM_INTEREST_TIER WHERE INTEREST_TIER_ID=? AND INTEREST_RULE_ID IN (SELECT INTEREST_RULE_ID FROM M03_PM_INTEREST_RULE WHERE PRODUCT_VERSION_ID=?)",
            key,
            vId
          )
      : db
          .jdbc()
          .update(
            "DELETE FROM " +
              f.table() +
              " WHERE " +
              f.key() +
              "=? AND PRODUCT_VERSION_ID=?",
            key,
            vId
          );
    conflict(n == 0, "RULE_NOT_FOUND", "Rule does not belong to this draft");
    bump(vId);
    event(u, "ProductRuleRemoved", decimal(v.get("PRODUCT_ID")), vId, code);
    return versionDetail(u, vId, branch);
  }

  private void bump(BigDecimal v) {
    db
      .jdbc()
      .update(
        "UPDATE M03_PM_PRODUCT_VERSION SET ROW_VERSION=ROW_VERSION+1,UPDATED_AT=? WHERE PRODUCT_VERSION_ID=?",
        now(),
        v
      );
  }

  /** Rule replacement is atomic: invalid replacement data restores the original draft row. */
  public Map<String, Object> updateRule(
    UserPrincipal u,
    BigDecimal vId,
    String code,
    BigDecimal key,
    RuleInput in,
    String branch
  ) {
    editable(u, vId, in.rowVersion(), branch);
    var f = family(code);
    var normalized = ProductRules.normalize(f, in.values());
    if (code.equals("tiers")) conflict(
      db.count(
          "SELECT COUNT(*) FROM M03_PM_INTEREST_RULE WHERE INTEREST_RULE_ID=? AND PRODUCT_VERSION_ID=?",
          normalized.get("INTEREST_RULE_ID"),
          vId
        ) ==
        0,
      "TIER_PARENT_MISMATCH",
      "Select an interest rule on this version"
    );
    else normalized.put("PRODUCT_VERSION_ID", vId);
    var matches = rules(vId, f)
      .stream()
      .filter(r -> decimal(r.get(f.key())).compareTo(key) == 0)
      .toList();
    conflict(
      matches.isEmpty(),
      "RULE_NOT_FOUND",
      "Rule does not belong to this draft"
    );
    String assignments = String.join(
      ",",
      normalized
        .keySet()
        .stream()
        .filter(k -> !k.equals("PRODUCT_VERSION_ID"))
        .map(k -> k + "=?")
        .toList()
    );
    var values = new ArrayList<Object>();
    for (var entry : normalized.entrySet())
      if (!entry.getKey().equals("PRODUCT_VERSION_ID")) values.add(
        entry.getValue()
      );
    values.add(key);
    if (assignments.isEmpty()) throw ProductRules.invalid("Supply rule fields");
    db
      .jdbc()
      .update(
        "UPDATE " +
          f.table() +
          " SET " +
          assignments +
          " WHERE " +
          f.key() +
          "=?",
        values.toArray()
      );
    bump(vId);
    event(
      u,
      "ProductRuleUpdated",
      decimal(versionRow(vId, false).get("PRODUCT_ID")),
      vId,
      code
    );
    return versionDetail(u, vId, branch);
  }

  /** Submission performs complete rule validation and binds approval to a canonical content hash. */
  public Map<String, Object> submit(
    UserPrincipal u,
    BigDecimal vId,
    Revision in,
    String branch
  ) {
    var v = editable(u, vId, in.rowVersion(), branch);
    List<String> issues = validate(vId);
    conflict(
      !issues.isEmpty(),
      "PRODUCT_INCOMPLETE",
      String.join("; ", issues)
    );
    String hash = signature(vId);
    db
      .jdbc()
      .update(
        "UPDATE M03_PM_PRODUCT_VERSION SET RULE_SET_HASH=?,ROW_VERSION=ROW_VERSION+1,UPDATED_AT=? WHERE PRODUCT_VERSION_ID=?",
        hash,
        now(),
        vId
      );
    BigDecimal request = approval(
      u,
      decimal(v.get("PRODUCT_ID")),
      vId,
      "VERSION",
      hash,
      in.reason(),
      in.impactSummary()
    );
    event(
      u,
      "ProductVersionSubmitted",
      decimal(v.get("PRODUCT_ID")),
      vId,
      in.reason()
    );
    return db.one("SELECT * FROM M03_PM_APPROVAL WHERE APPROVAL_ID=?", request);
  }

  private BigDecimal approval(
    UserPrincipal u,
    BigDecimal p,
    BigDecimal v,
    String action,
    String hash,
    String reason,
    String impact
  ) {
    return decimal(
      db.key(
        SchemaTable.M03_PM_APPROVAL,
        map(
          "PRODUCT_ID",
          p,
          "PRODUCT_VERSION_ID",
          v,
          "ACTION_CODE",
          action,
          "MAKER_USER_ID",
          u.userId(),
          "MAKER_SESSION_ID",
          u.sessionId(),
          "CONTENT_HASH",
          hash,
          "REASON",
          reason,
          "IMPACT_SUMMARY",
          impact,
          "CORRELATION_ID",
          Correlation.current()
        ),
        "APPROVAL_ID"
      )
    );
  }

  /** A checker reviews a frozen revision; the same maker cannot approve it. */
  public Map<String, Object> decide(
    UserPrincipal u,
    BigDecimal requestId,
    Decision in,
    String branch
  ) {
    var a = db.one(
      "SELECT * FROM M03_PM_APPROVAL WHERE APPROVAL_ID=? FOR UPDATE",
      requestId
    );
    version(a, in.rowVersion());
    var p = product(decimal(a.get("PRODUCT_ID")), true);
    permit(u, "PRODUCT_APPROVE", p, branch, str(a, "MAKER_USER_ID"));
    conflict(
      !"PENDING".equals(str(a, "REQUEST_STATUS")),
      "REQUEST_DECIDED",
      "Request already decided"
    );
    BigDecimal v =
      a.get("PRODUCT_VERSION_ID") == null
        ? null
        : decimal(a.get("PRODUCT_VERSION_ID"));
    String action = str(a, "ACTION_CODE");
    if ("VERSION".equals(action)) {
      var row = versionRow(v, true);
      conflict(
        !signature(v).equals(str(a, "CONTENT_HASH")),
        "CONTENT_CHANGED",
        "Submitted rules no longer match approval hash"
      );
      conflict(
        !"DRAFT".equals(str(row, "VERSION_STATE")),
        "INVALID_STATE",
        "Version is not a submitted draft"
      );
      if ("APPROVED".equals(in.decision())) {
        var errors = validate(v);
        conflict(
          !errors.isEmpty(),
          "PRODUCT_INCOMPLETE",
          String.join("; ", errors)
        );
        checkOverlap(v);
        db
          .jdbc()
          .update(
            "UPDATE M03_PM_PRODUCT_VERSION SET VERSION_STATE='APPROVED',UPDATED_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE PRODUCT_VERSION_ID=?",
            now(),
            v
          );
      }
    } else if ("TREATMENT".equals(action)) {
      conflict(
        !PrivateData.hash(
          str(a, "IMPACT_SUMMARY").getBytes(StandardCharsets.UTF_8)
        ).equals(str(a, "CONTENT_HASH")),
        "CONTENT_CHANGED",
        "Treatment proposal changed"
      );
      if ("APPROVED".equals(in.decision())) applyTreatment(a, requestId);
    } else {
      conflict(
        !lifecycleHash(p, v, action).equals(str(a, "CONTENT_HASH")),
        "CONTENT_CHANGED",
        "Lifecycle state changed since submission"
      );
      if ("APPROVED".equals(in.decision())) {
        String state = action.equals("SUSPEND")
          ? "SUSPENDED"
          : action.equals("RETIRE")
            ? "RETIRED"
            : "ACTIVE";
        if (v == null) db
          .jdbc()
          .update(
            "UPDATE M03_PM_PRODUCT SET STATUS=?,ROW_VERSION=ROW_VERSION+1,UPDATED_AT=? WHERE PRODUCT_ID=?",
            state,
            now(),
            a.get("PRODUCT_ID")
          );
        else {
          if (state.equals("ACTIVE")) {
            checkOverlap(v);
            checkEffective(versionRow(v, false));
          }
          db
            .jdbc()
            .update(
              "UPDATE M03_PM_PRODUCT_VERSION SET VERSION_STATE=?,ROW_VERSION=ROW_VERSION+1,UPDATED_AT=? WHERE PRODUCT_VERSION_ID=?",
              state,
              now(),
              v
            );
        }
      }
    }
    var vr = v == null ? null : versionRow(v, false);
    db
      .jdbc()
      .update(
        "UPDATE M03_PM_APPROVAL SET REQUEST_STATUS=?,CHECKER_USER_ID=?,CHECKER_SESSION_ID=?,DECIDED_AT=?,CHECKER_COMMENT=?,APPROVED_EFFECTIVE_FROM_AT=?,APPROVED_EFFECTIVE_TO_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE APPROVAL_ID=?",
        in.decision(),
        u.userId(),
        u.sessionId(),
        now(),
        in.comment(),
        "VERSION".equals(action) && in.decision().equals("APPROVED")
          ? vr.get("EFFECTIVE_FROM_AT")
          : null,
        "VERSION".equals(action) && in.decision().equals("APPROVED")
          ? vr.get("EFFECTIVE_TO_AT")
          : null,
        requestId
      );
    event(
      u,
      in.decision().equals("APPROVED")
        ? "Product" + action + "Approved"
        : "ProductRequestRejected",
      decimal(p.get("PRODUCT_ID")),
      v,
      in.comment()
    );
    return db.one(
      "SELECT * FROM M03_PM_APPROVAL WHERE APPROVAL_ID=?",
      requestId
    );
  }

  /** Makers may withdraw pending proposals; history remains and drafts become editable. */
  public void withdraw(
    UserPrincipal u,
    BigDecimal requestId,
    long expected,
    String branch
  ) {
    var a = db.one(
      "SELECT * FROM M03_PM_APPROVAL WHERE APPROVAL_ID=? FOR UPDATE",
      requestId
    );
    version(a, expected);
    permit(
      u,
      "PRODUCT_CREATE",
      product(decimal(a.get("PRODUCT_ID")), false),
      branch,
      null
    );
    conflict(
      !u.userId().equals(str(a, "MAKER_USER_ID")) ||
        !"PENDING".equals(str(a, "REQUEST_STATUS")),
      "WITHDRAW_NOT_ALLOWED",
      "Only the maker may withdraw a pending request"
    );
    db
      .jdbc()
      .update(
        "UPDATE M03_PM_APPROVAL SET REQUEST_STATUS='WITHDRAWN',DECIDED_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE APPROVAL_ID=?",
        now(),
        requestId
      );
    event(
      u,
      "ProductRequestWithdrawn",
      decimal(a.get("PRODUCT_ID")),
      a.get("PRODUCT_VERSION_ID") == null
        ? null
        : decimal(a.get("PRODUCT_VERSION_ID")),
      null
    );
  }

  /** Explicit activation checks approved content, effective time and overlap again. */
  public Map<String, Object> activate(
    UserPrincipal u,
    BigDecimal vId,
    Revision in,
    String branch
  ) {
    var v = versionRow(vId, true);
    version(v, in.rowVersion());
    var p = product(decimal(v.get("PRODUCT_ID")), true);
    permit(u, "PRODUCT_APPROVE", p, branch, str(v, "CREATED_BY_USER_ID"));
    conflict(
      !"ACTIVE".equals(str(p, "STATUS")),
      "PRODUCT_UNAVAILABLE",
      "Product must be active"
    );
    conflict(
      !"APPROVED".equals(str(v, "VERSION_STATE")),
      "VERSION_NOT_APPROVED",
      "Activate an approved version"
    );
    checkEffective(v);
    checkOverlap(vId);
    conflict(
      !signature(vId).equals(str(v, "RULE_SET_HASH")),
      "CONTENT_CHANGED",
      "Approved content hash mismatch"
    );
    db
      .jdbc()
      .update(
        "UPDATE M03_PM_PRODUCT_VERSION SET VERSION_STATE='ACTIVE',UPDATED_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE PRODUCT_VERSION_ID=?",
        now(),
        vId
      );
    event(
      u,
      "ProductVersionActivated",
      decimal(p.get("PRODUCT_ID")),
      vId,
      in.reason()
    );
    return versionDetail(u, vId, branch);
  }

  private void checkEffective(Map<String, Object> v) {
    conflict(
      now().isBefore((OffsetDateTime) v.get("EFFECTIVE_FROM_AT")) ||
        (v.get("EFFECTIVE_TO_AT") != null &&
          !now().isBefore((OffsetDateTime) v.get("EFFECTIVE_TO_AT"))),
      "OUTSIDE_EFFECTIVE_PERIOD",
      "Current time is outside the approved effective period"
    );
  }

  /** Suspension, retirement and reactivation are approved proposals, not direct mutable toggles. */
  public Map<String, Object> lifecycle(
    UserPrincipal u,
    BigDecimal pId,
    BigDecimal vId,
    Lifecycle in,
    String branch
  ) {
    var p = product(pId, true);
    permit(u, "PRODUCT_CREATE", p, branch, null);
    var row = vId == null ? p : versionRow(vId, true);
    version(row, in.rowVersion());
    if (vId != null) conflict(
      decimal(row.get("PRODUCT_ID")).compareTo(pId) != 0,
      "VERSION_PRODUCT_MISMATCH",
      "Version belongs to another product"
    );
    String state = str(row, vId == null ? "STATUS" : "VERSION_STATE");
    conflict(
      "DRAFT".equals(state) || "RETIRED".equals(state),
      "INVALID_STATE",
      "Draft/retired resources cannot use this transition"
    );
    conflict(
      in.action().equals("REACTIVATE")
        ? !state.equals("SUSPENDED")
        : in.action().equals("SUSPEND")
          ? !state.equals("ACTIVE")
          : false,
      "INVALID_STATE",
      "Transition is not permitted from current state"
    );
    conflict(
      db.count(
          "SELECT COUNT(*) FROM M03_PM_APPROVAL WHERE PRODUCT_ID=? AND REQUEST_STATUS='PENDING' AND ACTION_CODE IN ('SUSPEND','RETIRE','REACTIVATE')",
          pId
        ) >
        0,
      "REQUEST_EXISTS",
      "Decide the current lifecycle request first"
    );
    BigDecimal id = approval(
      u,
      pId,
      vId,
      in.action(),
      lifecycleHash(p, vId, in.action()),
      in.reason(),
      null
    );
    return db.one("SELECT * FROM M03_PM_APPROVAL WHERE APPROVAL_ID=?", id);
  }

  private String lifecycleHash(
    Map<String, Object> p,
    BigDecimal v,
    String action
  ) {
    return PrivateData.hash(
      (
        p.toString() +
        "|" +
        (v == null ? "" : versionRow(v, false).toString()) +
        "|" +
        action
      ).getBytes(StandardCharsets.UTF_8)
    );
  }

  /** Treatment changes describe downstream adoption; they never migrate accounts here. */
  public Map<String, Object> treatment(
    UserPrincipal u,
    BigDecimal pId,
    Treatment in,
    String branch
  ) {
    var p = product(pId, true);
    permit(u, "PRODUCT_CREATE", p, branch, null);
    var source = versionRow(in.sourceVersionId(), false);
    var target = versionRow(in.targetVersionId(), false);
    conflict(
      in.sourceVersionId().compareTo(in.targetVersionId()) == 0 ||
        decimal(source.get("PRODUCT_ID")).compareTo(pId) != 0 ||
        decimal(target.get("PRODUCT_ID")).compareTo(pId) != 0,
      "INVALID_TREATMENT",
      "Select distinct versions of this product"
    );
    conflict(
      "DRAFT".equals(str(source, "VERSION_STATE")) ||
        "DRAFT".equals(str(target, "VERSION_STATE")),
      "VERSION_NOT_APPROVED",
      "Treatment needs approved versions"
    );
    conflict(
      Set.of("SCHEDULED", "MANDATORY").contains(in.treatmentCode()) &&
        (in.migrationFromAt() == null || !in.migrationFromAt().isAfter(now())),
      "MIGRATION_DATE_REQUIRED",
      "Supply a future migration date"
    );
    conflict(
      in.treatmentCode().equals("OPT_IN") && !in.consentRequired(),
      "CONSENT_REQUIRED",
      "Opt-in treatment requires consent"
    );
    String payload =
      in.sourceVersionId() +
      "|" +
      in.targetVersionId() +
      "|" +
      in.treatmentCode() +
      "|" +
      Objects.toString(in.migrationFromAt(), "") +
      "|" +
      (in.consentRequired() ? "Y" : "N");
    BigDecimal id = approval(
      u,
      pId,
      in.targetVersionId(),
      "TREATMENT",
      PrivateData.hash(payload.getBytes(StandardCharsets.UTF_8)),
      in.reason(),
      payload
    );
    return db.one("SELECT * FROM M03_PM_APPROVAL WHERE APPROVAL_ID=?", id);
  }

  private void applyTreatment(Map<String, Object> a, BigDecimal approval) {
    String[] data = str(a, "IMPACT_SUMMARY").split("\\|", -1);
    db.insert(
      SchemaTable.M03_PM_VERSION_TREATMENT,
      map(
        "PRODUCT_ID",
        a.get("PRODUCT_ID"),
        "SOURCE_VERSION_ID",
        new BigDecimal(data[0]),
        "TARGET_VERSION_ID",
        new BigDecimal(data[1]),
        "TREATMENT_CODE",
        data[2],
        "MIGRATION_FROM_AT",
        data[3].isEmpty() ? null : OffsetDateTime.parse(data[3]),
        "CONSENT_REQUIRED",
        data[4],
        "APPROVAL_ID",
        approval
      )
    );
  }

  /** Full version checks are repeatable; validation returns stable actionable messages. */
  public List<String> validate(BigDecimal vId) {
    var v = versionRow(vId, false);
    return validate(
      product(decimal(v.get("PRODUCT_ID")), false),
      allRules(vId)
    );
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> snapshotRules(
    Map<String, Object> snapshot,
    String code
  ) {
    return (List<Map<String, Object>>) snapshot.get(code);
  }

  /** Reuses the response snapshot so a screen does not query each rule family twice. */
  private List<String> validate(
    Map<String, Object> p,
    Map<String, Object> snapshot
  ) {
    List<String> errors = new ArrayList<>();
    String type = str(p, "PRODUCT_TYPE");
    if (snapshotRules(snapshot, "availability").isEmpty()) errors.add(
      "Availability is required"
    );
    if (
      Set.of("SAVINGS", "CURRENT").contains(type) &&
      snapshotRules(snapshot, "account").isEmpty()
    ) errors.add("Savings/current account terms are required");
    if (
      type.equals("SAVINGS") &&
      (snapshotRules(snapshot, "interest").isEmpty() ||
        snapshotRules(snapshot, "balance").isEmpty())
    ) errors.add("Savings needs interest and minimum balance rules");
    if (
      type.equals("LOAN") &&
      (snapshotRules(snapshot, "loan").isEmpty() ||
        snapshotRules(snapshot, "interest").isEmpty() ||
        snapshotRules(snapshot, "allocation").isEmpty())
    ) errors.add("Loan needs loan, interest and allocation rules");
    if (
      type.equals("DEPOSIT") && snapshotRules(snapshot, "interest").isEmpty()
    ) errors.add("Deposit needs an interest rule");
    for (var f : ProductRuleCatalog.FAMILIES.values())
      for (var r : snapshotRules(snapshot, f.code()))
        ProductRules.check(f.code(), r, errors);
    var interest = snapshotRules(snapshot, "interest");
    for (var r : interest) {
      var tiers = snapshotRules(snapshot, "tiers")
        .stream()
        .filter(
          t ->
            decimal(t.get("INTEREST_RULE_ID")).compareTo(
              decimal(r.get("INTEREST_RULE_ID"))
            ) ==
            0
        )
        .toList();
      if (
        "TIERED".equals(r.get("INTEREST_METHOD")) && tiers.isEmpty()
      ) errors.add("Tiered interest requires bands");
      for (int i = 0; i < tiers.size(); i++) for (
        int j = i + 1;
        j < tiers.size();
        j++
      ) if (
        ProductRules.overlaps(
          decimal(tiers.get(i).get("AMOUNT_FROM")),
          nullableDecimal(tiers.get(i).get("AMOUNT_TO")),
          decimal(tiers.get(j).get("AMOUNT_FROM")),
          nullableDecimal(tiers.get(j).get("AMOUNT_TO"))
        )
      ) errors.add("Interest tiers overlap");
    }
    if (type.equals("LOAN")) for (var loan : snapshotRules(snapshot, "loan"))
      for (var r : interest)
        if (
          !"BOTH".equals(str(loan, "ALLOWED_INTEREST_TYPE")) &&
          !Objects.equals(
            loan.get("ALLOWED_INTEREST_TYPE"),
            r.get("INTEREST_TYPE")
          )
        ) errors.add("Loan interest type must match its approved allowance");
    for (var a : snapshotRules(snapshot, "availability"))
      if (!str(p, "CURRENCY_CODE").equals(str(a, "CURRENCY_CODE"))) errors.add(
        "Availability currency must match product"
      );
    for (var override : snapshotRules(snapshot, "overrides")) {
      String code = str(override, "RULE_FAMILY").toLowerCase(Locale.ROOT);
      var f = ProductRuleCatalog.FAMILIES.get(code);
      if (
        f == null ||
        !f
          .fields()
          .stream()
          .anyMatch(
            x ->
              x.name().equals(str(override, "FIELD_CODE")) &&
              Set.of("BigDecimal", "Long", "Integer").contains(x.javaType())
          ) ||
        snapshotRules(snapshot, code)
          .stream()
          .noneMatch(x ->
            Objects.equals(str(x, "RULE_CODE"), str(override, "RULE_CODE"))
          )
      ) errors.add(
        "Override policy must reference an existing numeric rule field"
      );
    }
    return errors.stream().distinct().toList();
  }

  private static BigDecimal nullableDecimal(Object value) {
    return value == null ? null : decimal(value);
  }

  private static BigDecimal decimal(Object value) {
    return ProductRules.decimal(value);
  }

  private static void dates(OffsetDateTime from, OffsetDateTime to) {
    if (to != null && !to.isAfter(from)) throw ProductRules.invalid(
      "End date must follow start date"
    );
  }

  /** Canonical content includes every typed rule and excludes generated row identities. */
  public String signature(BigDecimal vId) {
    var v = versionRow(vId, false);
    var p = product(decimal(v.get("PRODUCT_ID")), false);
    Map<String, Object> content = new TreeMap<>();
    for (String key : List.of(
      "PRODUCT_CODE",
      "PRODUCT_TYPE",
      "CURRENCY_CODE",
      "SALES_START_AT",
      "SALES_END_AT"
    ))
      content.put(key, p.get(key));
    for (String key : List.of(
      "EFFECTIVE_FROM_AT",
      "EFFECTIVE_TO_AT",
      "DEFAULT_TXN_ACTION",
      "CHANGE_REASON"
    ))
      content.put(key, v.get(key));
    for (var f : ProductRuleCatalog.FAMILIES.values()) {
      List<String> rows = new ArrayList<>();
      for (var row : rules(vId, f)) {
        Map<String, Object> sorted = new TreeMap<>(row);
        sorted.remove(f.key());
        sorted.remove("PRODUCT_VERSION_ID");
        if (f.code().equals("tiers")) sorted.remove("INTEREST_RULE_ID");
        rows.add(sorted.toString());
      }
      Collections.sort(rows);
      content.put(f.code(), rows);
    }
    return PrivateData.hash(
      content.toString().getBytes(StandardCharsets.UTF_8)
    );
  }

  /** Overlap is checked on the same product lock and only for intersecting sales contexts. */
  private void checkOverlap(BigDecimal vId) {
    var v = versionRow(vId, false);
    var others = db.rows(
      "SELECT * FROM M03_PM_PRODUCT_VERSION WHERE PRODUCT_ID=? AND PRODUCT_VERSION_ID<>? AND VERSION_STATE IN ('APPROVED','ACTIVE','SUSPENDED')",
      v.get("PRODUCT_ID"),
      vId
    );
    for (var other : others) {
      for (var a : rules(vId, family("availability")))
        for (var b : rules(
          decimal(other.get("PRODUCT_VERSION_ID")),
          family("availability")
        )) {
          boolean dims = List.of(
            "SEGMENT_CODE",
            "BRANCH_CODE",
            "CHANNEL_CODE",
            "CURRENCY_CODE"
          )
            .stream()
            .allMatch(
              k ->
                a.get(k) == null ||
                b.get(k) == null ||
                a.get(k).equals(b.get(k))
            );
          if (dims && offerOverlap(v, a, other, b)) throw new BusinessException(
            HttpStatus.CONFLICT,
            "VERSION_OVERLAP",
            "Another approved version overlaps this sales context"
          );
        }
    }
  }

  /** Intersects both version periods and both offer periods as one half-open window. */
  public static boolean offerOverlap(
    Map<String, Object> left,
    Map<String, Object> leftOffer,
    Map<String, Object> right,
    Map<String, Object> rightOffer
  ) {
    OffsetDateTime start = null,
      end = null;
    for (var pair : List.of(left, right, leftOffer, rightOffer)) {
      String from = pair.containsKey("EFFECTIVE_FROM_AT")
        ? "EFFECTIVE_FROM_AT"
        : "OFFER_FROM_AT";
      String to = from.equals("EFFECTIVE_FROM_AT")
        ? "EFFECTIVE_TO_AT"
        : "OFFER_TO_AT";
      var a = (OffsetDateTime) pair.get(from);
      var b = (OffsetDateTime) pair.get(to);
      if (start == null || a.isAfter(start)) start = a;
      if (b != null && (end == null || b.isBefore(end))) end = b;
    }
    return end == null || start.isBefore(end);
  }

  private static boolean period(
    OffsetDateTime a,
    OffsetDateTime b,
    OffsetDateTime c,
    OffsetDateTime d
  ) {
    return (b == null || c.isBefore(b)) && (d == null || a.isBefore(d));
  }

  /** Historical definitions remain readable after suspension/retirement. */
  public Map<String, Object> historical(BigDecimal vId) {
    var v = versionRow(vId, false);
    conflict(
      "DRAFT".equals(str(v, "VERSION_STATE")),
      "VERSION_NOT_APPROVED",
      "Draft is not an adoptable historical definition"
    );
    return map(
      "version",
      v,
      "product",
      product(decimal(v.get("PRODUCT_ID")), false),
      "rules",
      allRules(vId)
    );
  }

  /** Returns exactly one active definition; ambiguity fails rather than choosing arbitrarily. */
  public Map<String, Object> resolve(
    BigDecimal pId,
    String branch,
    String segment,
    String channel,
    String currency,
    OffsetDateTime at
  ) {
    var p = product(pId, false);
    conflict(
      !"ACTIVE".equals(str(p, "STATUS")) ||
        !str(p, "CURRENCY_CODE").equals(currency) ||
        !inside(
          at,
          (OffsetDateTime) p.get("SALES_START_AT"),
          (OffsetDateTime) p.get("SALES_END_AT")
        ),
      "PRODUCT_UNAVAILABLE",
      "Product is unavailable for this context"
    );
    var matching = db
      .rows(
        "SELECT * FROM M03_PM_PRODUCT_VERSION WHERE PRODUCT_ID=? AND VERSION_STATE='ACTIVE'",
        pId
      )
      .stream()
      .filter(v -> available(v, branch, segment, channel, currency, at))
      .toList();
    conflict(
      matching.size() != 1,
      "NO_EFFECTIVE_VERSION",
      matching.isEmpty()
        ? "No active version matches context"
        : "Ambiguous active versions"
    );
    return historical(decimal(matching.get(0).get("PRODUCT_VERSION_ID")));
  }

  private boolean available(
    Map<String, Object> v,
    String branch,
    String segment,
    String channel,
    String currency,
    OffsetDateTime at
  ) {
    if (
      !inside(
        at,
        (OffsetDateTime) v.get("EFFECTIVE_FROM_AT"),
        (OffsetDateTime) v.get("EFFECTIVE_TO_AT")
      )
    ) return false;
    return rules(decimal(v.get("PRODUCT_VERSION_ID")), family("availability"))
      .stream()
      .anyMatch(
        a ->
          matches(a, "BRANCH_CODE", branch) &&
          matches(a, "SEGMENT_CODE", segment) &&
          matches(a, "CHANNEL_CODE", channel) &&
          matches(a, "CURRENCY_CODE", currency) &&
          inside(
            at,
            (OffsetDateTime) a.get("OFFER_FROM_AT"),
            (OffsetDateTime) a.get("OFFER_TO_AT")
          )
      );
  }

  private static boolean matches(
    Map<String, Object> a,
    String key,
    String value
  ) {
    return a.get(key) == null || Objects.equals(a.get(key), value);
  }

  private static boolean inside(
    OffsetDateTime time,
    OffsetDateTime from,
    OffsetDateTime to
  ) {
    return !time.isBefore(from) && (to == null || time.isBefore(to));
  }

  /** Discovery honors availability and current IAM context; it never returns drafts. */
  public List<Map<String, Object>> discovery(
    UserPrincipal u,
    String branch,
    String segment,
    String channel,
    String currency,
    OffsetDateTime at
  ) {
    List<Map<String, Object>> found = new ArrayList<>();
    for (var p : list(u, "", branch)) {
      if (
        !"ACTIVE".equals(str(p, "STATUS")) ||
        !Objects.equals(currency, p.get("CURRENCY_CODE")) ||
        !inside(
          at,
          (OffsetDateTime) p.get("SALES_START_AT"),
          (OffsetDateTime) p.get("SALES_END_AT")
        )
      ) continue;
      for (var v : db.rows(
        "SELECT * FROM M03_PM_PRODUCT_VERSION WHERE PRODUCT_ID=? AND VERSION_STATE='ACTIVE'",
        p.get("PRODUCT_ID")
      ))
        if (available(v, branch, segment, channel, currency, at)) found.add(
          map("product", p, "version", v)
        );
    }
    return found;
  }

  /** Eligibility obtains authoritative minimal CIF facts; clients cannot invent KYC or age. */
  public Map<String, Object> eligibility(
    UserPrincipal u,
    BigDecimal pId,
    Eligibility in
  ) {
    var facts = customers.facts(in.cifId());
    access.require(
      u,
      "CIF_READ",
      facts.branch(),
      facts.cifId(),
      null,
      null,
      null
    );
    var p = product(pId, false);
    access.require(
      u,
      "PRODUCT_READ",
      facts.branch(),
      facts.cifId(),
      str(p, "PRODUCT_TYPE"),
      str(p, "CURRENCY_CODE"),
      null
    );
    OffsetDateTime at = in.at() == null ? now() : in.at();
    var definition = resolve(
      pId,
      facts.branch(),
      facts.segment(),
      in.channel(),
      str(p, "CURRENCY_CODE"),
      at
    );
    BigDecimal v = decimal(
      ((Map<?, ?>) definition.get("version")).get("PRODUCT_VERSION_ID")
    );
    List<String> failures = new ArrayList<>();
    if (!facts.status().equals("ACTIVE")) failures.add("CUSTOMER_NOT_ACTIVE");
    if (!facts.kycStatus().equals("VERIFIED")) failures.add("KYC_NOT_VERIFIED");
    Map<String, Object> attrs = map(
      "AGE",
      facts.dateOfBirth() == null
        ? null
        : Period.between(facts.dateOfBirth(), at.toLocalDate()).getYears(),
      "KYC_STATUS",
      facts.kycStatus(),
      "SEGMENT",
      facts.segment(),
      "PARTY_TYPE",
      facts.partyType(),
      "COUNTRY",
      facts.countryCode(),
      "RISK_LEVEL",
      facts.risk(),
      "INCORPORATED_ON",
      facts.incorporatedOn()
    );
    for (var rule : rules(v, family("eligibility")))
      if (
        !satisfies(attrs.get(str(rule, "ATTRIBUTE_CODE")), rule)
      ) failures.add(str(rule, "FAILURE_REASON_CODE"));
    for (var rule : rules(v, family("account")))
      if (
        in.openingAmount() == null ||
        in.openingAmount().compareTo(decimal(rule.get("MIN_OPENING_BALANCE"))) <
        0
      ) failures.add("OPENING_BALANCE_REQUIRED");
    for (var rule : rules(v, family("loan"))) {
      if (
        in.loanAmount() == null ||
        in.loanAmount().compareTo(decimal(rule.get("MIN_LOAN_AMOUNT"))) < 0 ||
        in.loanAmount().compareTo(decimal(rule.get("MAX_LOAN_AMOUNT"))) > 0
      ) failures.add("LOAN_AMOUNT_OUTSIDE_RANGE");
      if (
        in.tenureMonths() == null ||
        in.tenureMonths() < number(rule, "MIN_TENURE_MONTHS") ||
        in.tenureMonths() > number(rule, "MAX_TENURE_MONTHS")
      ) failures.add("LOAN_TENURE_OUTSIDE_RANGE");
    }
    event(u, "ProductEligibilityChecked", pId, v, null);
    return map(
      "eligible",
      failures.isEmpty(),
      "reasonCodes",
      failures.stream().distinct().toList(),
      "productId",
      pId,
      "productVersionId",
      v,
      "evaluatedAt",
      at,
      "validUntil",
      at.plusMinutes(5)
    );
  }

  /** Comparison is data-only and deterministic; unsupported/null facts fail closed. */
  public static boolean satisfies(Object actual, Map<String, Object> rule) {
    if (actual == null) return false;
    String type = str(rule, "VALUE_TYPE"),
      op = str(rule, "OPERATOR_CODE");
    Object expected = rule.get("VALUE_" + type);
    if (expected == null) return false;
    if (op.equals("IN") || op.equals("NOT_IN")) {
      boolean contains = Arrays.stream(expected.toString().split(","))
        .map(String::trim)
        .anyMatch(actual.toString()::equals);
      return op.equals("IN") == contains;
    }
    int compare = type.equals("NUMBER")
      ? decimal(actual).compareTo(decimal(expected))
      : type.equals("DATE")
        ? LocalDate.parse(actual.toString()).compareTo(
            LocalDate.parse(expected.toString())
          )
        : actual.toString().compareTo(expected.toString());
    return switch (op) {
      case "EQ" -> compare == 0;
      case "NE" -> compare != 0;
      case "GT" -> compare > 0;
      case "GE" -> compare >= 0;
      case "LT" -> compare < 0;
      case "LE" -> compare <= 0;
      default -> false;
    };
  }

  /** Version comparison excludes generated IDs and reports complete rule-family snapshots. */
  public Map<String, Object> compare(
    UserPrincipal u,
    BigDecimal left,
    BigDecimal right,
    String branch
  ) {
    var a = versionDetail(u, left, branch);
    var b = versionDetail(u, right, branch);
    conflict(
      decimal(((Map<?, ?>) a.get("version")).get("PRODUCT_ID")).compareTo(
          decimal(((Map<?, ?>) b.get("version")).get("PRODUCT_ID"))
        ) !=
        0,
      "COMPARE_PRODUCT_MISMATCH",
      "Compare versions of the same product"
    );
    return map(
      "left",
      a,
      "right",
      b,
      "leftHash",
      signature(left),
      "rightHash",
      signature(right)
    );
  }

  public List<Map<String, Object>> audit(
    UserPrincipal u,
    BigDecimal pId,
    String branch
  ) {
    permit(u, "PRODUCT_READ", product(pId, false), branch, null);
    return db.rows(
      "SELECT * FROM M03_PM_AUDIT_EVENT WHERE PRODUCT_ID=? ORDER BY OCCURRED_AT DESC FETCH FIRST 200 ROWS ONLY",
      pId
    );
  }

  /** Customer discovery derives branch/segment from an authorized linked CIF and exposes active offers only. */
  public List<Map<String, Object>> customerOffers(
    UserPrincipal u,
    String cifId,
    String channel,
    String currency
  ) {
    var facts = customers.facts(cifId);
    access.require(u, "CIF_READ", facts.branch(), cifId, null, null, null);
    List<Map<String, Object>> result = new ArrayList<>();
    for (var p : db.rows(
      "SELECT * FROM M03_PM_PRODUCT WHERE STATUS='ACTIVE' AND CURRENCY_CODE=? ORDER BY PRODUCT_CODE",
      currency
    )) {
      if (
        !access.allowed(
          u,
          "PRODUCT_READ",
          facts.branch(),
          cifId,
          str(p, "PRODUCT_TYPE"),
          currency
        ) ||
        !inside(
          now(),
          (OffsetDateTime) p.get("SALES_START_AT"),
          (OffsetDateTime) p.get("SALES_END_AT")
        )
      ) continue;
      for (var v : db.rows(
        "SELECT * FROM M03_PM_PRODUCT_VERSION WHERE PRODUCT_ID=? AND VERSION_STATE='ACTIVE'",
        p.get("PRODUCT_ID")
      ))
        if (
          available(
            v,
            facts.branch(),
            facts.segment(),
            channel,
            currency,
            now()
          )
        ) result.add(
          map(
            "product",
            map(
              "PRODUCT_ID",
              p.get("PRODUCT_ID"),
              "PRODUCT_CODE",
              p.get("PRODUCT_CODE"),
              "PRODUCT_NAME",
              p.get("PRODUCT_NAME"),
              "PRODUCT_TYPE",
              p.get("PRODUCT_TYPE"),
              "CURRENCY_CODE",
              currency,
              "DESCRIPTION",
              p.get("DESCRIPTION")
            ),
            "version",
            map(
              "PRODUCT_VERSION_ID",
              v.get("PRODUCT_VERSION_ID"),
              "VERSION_NO",
              v.get("VERSION_NO")
            ),
            "rules",
            allRules(decimal(v.get("PRODUCT_VERSION_ID")))
          )
        );
    }
    return result;
  }

  public Map<String, Object> effectiveFor(
    UserPrincipal u,
    BigDecimal pId,
    String branch,
    String segment,
    String channel,
    String currency,
    OffsetDateTime at
  ) {
    permit(u, "PRODUCT_READ", product(pId, false), branch, null);
    return resolve(pId, branch, segment, channel, currency, at);
  }

  /** Domain events carry only stable aggregate references; publishing is a separate adapter. */
  private void event(
    UserPrincipal u,
    String type,
    BigDecimal p,
    BigDecimal v,
    String reason
  ) {
    db.insert(
      SchemaTable.M03_PM_AUDIT_EVENT,
      map(
        "AUDIT_ID",
        UUID.randomUUID().toString(),
        "EVENT_TYPE",
        type,
        "ACTOR_USER_ID",
        u.userId(),
        "SESSION_ID",
        u.sessionId(),
        "PRODUCT_ID",
        p,
        "PRODUCT_VERSION_ID",
        v,
        "RESULT",
        "SUCCESS",
        "REASON_CODE",
        reason == null
          ? null
          : reason.substring(0, Math.min(80, reason.length())),
        "CORRELATION_ID",
        Correlation.current()
      )
    );
    db.insert(
      SchemaTable.M03_PM_OUTBOX_EVENT,
      map(
        "EVENT_ID",
        UUID.randomUUID().toString(),
        "EVENT_TYPE",
        type,
        "SCHEMA_VERSION",
        1,
        "AGGREGATE_TYPE",
        "PRODUCT",
        "AGGREGATE_ID",
        p.toPlainString(),
        "PRODUCT_ID",
        p,
        "PRODUCT_VERSION_ID",
        v,
        "CORRELATION_ID",
        Correlation.current(),
        "PAYLOAD",
        "{\"schemaVersion\":1,\"productId\":\"" +
          p +
          "\",\"versionId\":" +
          (v == null ? "null" : "\"" + v + "\"") +
          ",\"eventType\":\"" +
          type +
          "\"}"
      )
    );
  }
}
