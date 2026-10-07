package com.moneybags.cif.service;

import static com.moneybags.common.database.BusinessRepository.*;
import static com.moneybags.iam.repository.IamRepository.map;

import com.moneybags.cif.dto.CifDtos.*;
import com.moneybags.common.api.*;
import com.moneybags.common.database.*;
import com.moneybags.common.security.*;
import com.moneybags.iam.security.UserPrincipal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer master and KYC state machine. All state changes and evidence share a transaction. */
@Service
@Transactional
public class CifService implements CustomerFactsPort {

  private final BusinessRepository db;
  private final DomainAccess access;
  private final PrivateData privateData;
  private final Clock clock;
  private final com.moneybags.iam.service.CustomerAccessLifecycle iamLifecycle;

  public CifService(
    BusinessRepository db,
    DomainAccess access,
    PrivateData privateData,
    Clock clock,
    com.moneybags.iam.service.CustomerAccessLifecycle iamLifecycle
  ) {
    this.db = db;
    this.access = access;
    this.privateData = privateData;
    this.clock = clock;
    this.iamLifecycle = iamLifecycle;
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private static void bad(boolean condition, String code, String message) {
    if (condition) throw new BusinessException(
      HttpStatus.CONFLICT,
      code,
      message
    );
  }

  private Map<String, Object> customer(String id, boolean lock) {
    return db.one(
      "SELECT * FROM M02_CIF_CUSTOMER WHERE CIF_ID=?" +
        (lock ? " FOR UPDATE" : ""),
      id
    );
  }

  private void permit(
    UserPrincipal user,
    String permission,
    Map<String, Object> c,
    String maker
  ) {
    access.require(
      user,
      permission,
      str(c, "HOME_BRANCH_REF"),
      str(c, "CIF_ID"),
      null,
      null,
      maker
    );
  }

  private Map<String, Object> editable(UserPrincipal user, String cif) {
    var c = customer(cif, true);
    permit(user, "CIF_UPDATE", c, null);
    bad(
      "CLOSED".equals(str(c, "STATUS")),
      "CUSTOMER_CLOSED",
      "Closed customer history cannot be changed"
    );
    var cases = db.rows(
      "SELECT * FROM M02_KYC_CASE WHERE CIF_ID=? AND STATUS IN ('DRAFT','DOCUMENTS_PENDING','MORE_INFO_REQUIRED') ORDER BY CREATED_AT DESC",
      cif
    );
    bad(
      cases.isEmpty(),
      "EDIT_CASE_REQUIRED",
      "Open a draft KYC case before changing verified profile information"
    );
    bad(
      !user.userId().equals(str(cases.get(0), "MAKER_USER_ID")),
      "CASE_MAKER_ONLY",
      "Only this case's maker may change the proposed profile"
    );
    return c;
  }

  /** Returns only customers covered by the caller's branch/global/self assignment. */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> list(
    UserPrincipal user,
    String search,
    String status
  ) {
    if (!user.permissions().contains("CIF_READ")) throw new BusinessException(
      HttpStatus.FORBIDDEN,
      "FORBIDDEN",
      "CIF_READ permission required"
    );
    var rows = db.rows(
      "SELECT C.*, (SELECT FULL_NAME FROM M02_CIF_NAME N WHERE N.PARTY_ID=C.PARTY_ID AND N.NAME_TYPE='LEGAL' AND N.VALID_TO IS NULL ORDER BY N.VALID_FROM DESC FETCH FIRST 1 ROWS ONLY) LEGAL_NAME FROM M02_CIF_CUSTOMER C WHERE (? IS NULL OR C.STATUS=?) AND (? IS NULL OR UPPER(C.CIF_NUMBER) LIKE ? OR EXISTS (SELECT 1 FROM M02_CIF_NAME N WHERE N.PARTY_ID=C.PARTY_ID AND UPPER(N.FULL_NAME) LIKE ?)) ORDER BY C.CREATED_AT DESC",
      empty(status),
      empty(status),
      empty(search),
      like(search),
      like(search)
    );
    return rows
      .stream()
      .filter(c ->
        access.allowed(
          user,
          "CIF_READ",
          str(c, "HOME_BRANCH_REF"),
          str(c, "CIF_ID"),
          null,
          null
        )
      )
      .limit(200)
      .toList();
  }

  private static String empty(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static String like(String s) {
    return s == null ? null : "%" + s.trim().toUpperCase(Locale.ROOT) + "%";
  }

  /** Creates a party, CIF, legal name and onboarding case atomically. */
  public Map<String, Object> create(UserPrincipal user, CustomerInput in) {
    access.require(
      user,
      "CIF_CREATE",
      in.homeBranchRef(),
      null,
      null,
      null,
      null
    );
    bad(
      in.dateOfBirth() != null &&
        in.dateOfBirth().isAfter(LocalDate.now(clock)),
      "INVALID_DATES",
      "Birth date cannot be in the future"
    );
    bad(
      in.incorporatedOn() != null &&
        in.incorporatedOn().isAfter(LocalDate.now(clock)),
      "INVALID_DATES",
      "Incorporation date cannot be in the future"
    );
    bad(
      "INDIVIDUAL".equals(in.partyType())
        ? (in.dateOfBirth() == null || in.incorporatedOn() != null)
        : (in.incorporatedOn() == null || in.dateOfBirth() != null),
      "INVALID_PARTY_DATES",
      "Supply the date corresponding to the party type"
    );
    String party = id(),
      cif = id();
    db.insert(
      SchemaTable.M02_CIF_PARTY,
      map(
        "PARTY_ID",
        party,
        "PARTY_TYPE",
        in.partyType(),
        "DATE_OF_BIRTH",
        in.dateOfBirth(),
        "INCORPORATED_ON",
        in.incorporatedOn()
      )
    );
    db.insert(
      SchemaTable.M02_CIF_CUSTOMER,
      map(
        "CIF_ID",
        cif,
        "CIF_NUMBER",
        "CIF-" +
          UUID.randomUUID()
            .toString()
            .replace("-", "")
            .toUpperCase(Locale.ROOT),
        "PARTY_ID",
        party,
        "HOME_BRANCH_REF",
        in.homeBranchRef(),
        "SEGMENT_CODE",
        in.segmentCode()
      )
    );
    name(party, in.legalName());
    newCase(user, cif, "ONBOARDING");
    event(user, "CifCreated", cif, null, null);
    return detail(user, cif);
  }

  private void name(String party, String name) {
    db.insert(
      SchemaTable.M02_CIF_NAME,
      map(
        "NAME_ID",
        id(),
        "PARTY_ID",
        party,
        "NAME_TYPE",
        "LEGAL",
        "FULL_NAME",
        name.trim(),
        "VALID_FROM",
        now()
      )
    );
  }

  /** Safe projections retain profile history but omit ciphertext, keyed hashes and file paths. */
  public Map<String, Object> detail(UserPrincipal user, String cif) {
    var c = customer(cif, false);
    permit(user, "CIF_READ", c, null);
    String party = str(c, "PARTY_ID");
    var result = map(
      "customer",
      c,
      "party",
      db.one("SELECT * FROM M02_CIF_PARTY WHERE PARTY_ID=?", party),
      "names",
      db.rows(
        "SELECT * FROM M02_CIF_NAME WHERE PARTY_ID=? ORDER BY VALID_FROM DESC",
        party
      ),
      "contacts",
      db.rows(
        "SELECT CONTACT_ID,CONTACT_TYPE,DISPLAY_HINT,VERIFICATION_STATUS,VERIFIED_AT,VALID_FROM,VALID_TO FROM M02_CIF_CONTACT WHERE PARTY_ID=? ORDER BY VALID_FROM DESC",
        party
      ),
      "identifiers",
      db.rows(
        "SELECT IDENTIFIER_ID,IDENTIFIER_TYPE,DISPLAY_HINT,ISSUER_CODE,EXPIRES_ON,VERIFICATION_STATUS,VALID_FROM,VALID_TO FROM M02_CIF_IDENTIFIER WHERE PARTY_ID=? ORDER BY VALID_FROM DESC",
        party
      ),
      "addresses",
      db.rows(
        "SELECT * FROM M02_CIF_ADDRESS WHERE PARTY_ID=? ORDER BY VALID_FROM DESC",
        party
      ),
      "cases",
      db.rows(
        "SELECT * FROM M02_KYC_CASE WHERE CIF_ID=? ORDER BY CREATED_AT DESC",
        cif
      ),
      "consents",
      db.rows(
        "SELECT * FROM M02_CIF_CONSENT WHERE CIF_ID=? ORDER BY CAPTURED_AT DESC",
        cif
      ),
      "relationships",
      db.rows(
        "SELECT * FROM M02_CIF_RELATIONSHIP WHERE SOURCE_PARTY_ID=? OR TARGET_PARTY_ID=? ORDER BY VALID_FROM DESC",
        party,
        party
      )
    );
    audit(user, "CifViewed", cif, null, null);
    return result;
  }

  /** Proposed profile edits retain verified history until a checker approves. */
  public Map<String, Object> profile(
    UserPrincipal user,
    String cif,
    ProfileInput in
  ) {
    var c = editable(user, cif);
    version(c, in.rowVersion());
    String party = str(c, "PARTY_ID");
    db
      .jdbc()
      .update(
        "UPDATE M02_CIF_NAME SET VALID_TO=? WHERE PARTY_ID=? AND VERIFICATION_STATUS='PENDING' AND VALID_TO IS NULL",
        now(),
        party
      );
    name(party, in.legalName());
    db
      .jdbc()
      .update(
        "UPDATE M02_CIF_CUSTOMER SET SEGMENT_CODE=?,UPDATED_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE CIF_ID=?",
        in.segmentCode(),
        now(),
        cif
      );
    event(user, "CifProfileProposed", cif, null, in.reason());
    return detail(user, cif);
  }

  /** Stores only encrypted contact values and masked display hints. */
  public void contact(UserPrincipal user, String cif, ContactInput in) {
    var c = editable(user, cif);
    bad(
      "EMAIL".equals(in.contactType())
        ? !in.value().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        : !in.value().matches("\\+?[0-9 -]{7,20}"),
      "INVALID_CONTACT",
      "Use a valid email or telephone number"
    );
    db.insert(
      SchemaTable.M02_CIF_CONTACT,
      map(
        "CONTACT_ID",
        id(),
        "PARTY_ID",
        c.get("PARTY_ID"),
        "CONTACT_TYPE",
        in.contactType(),
        "VALUE_CIPHERTEXT",
        privateData.encrypt(in.value().getBytes(StandardCharsets.UTF_8)),
        "DISPLAY_HINT",
        PrivateData.hint(in.value()),
        "VALID_FROM",
        now()
      )
    );
    event(user, "ContactProposed", cif, null, null);
  }

  /** Duplicate matching is keyed; a duplicate never reveals another customer's identity. */
  public void identifier(UserPrincipal user, String cif, IdentifierInput in) {
    var c = editable(user, cif);
    String hash = privateData.lookup(
      in.identifierType().toUpperCase(Locale.ROOT),
      in.value()
    );
    bad(
      db.count(
          "SELECT COUNT(*) FROM M02_CIF_IDENTIFIER WHERE IDENTIFIER_TYPE=? AND LOOKUP_HMAC=?",
          in.identifierType().toUpperCase(Locale.ROOT),
          hash
        ) >
        0,
      "DUPLICATE_IDENTIFIER",
      "Identifier already registered; refer for controlled duplicate review"
    );
    bad(
      in.expiresOn() != null && !in.expiresOn().isAfter(LocalDate.now(clock)),
      "IDENTIFIER_EXPIRED",
      "Use an unexpired identifier"
    );
    db.insert(
      SchemaTable.M02_CIF_IDENTIFIER,
      map(
        "IDENTIFIER_ID",
        id(),
        "PARTY_ID",
        c.get("PARTY_ID"),
        "IDENTIFIER_TYPE",
        in.identifierType().toUpperCase(Locale.ROOT),
        "VALUE_CIPHERTEXT",
        privateData.encrypt(in.value().getBytes(StandardCharsets.UTF_8)),
        "DISPLAY_HINT",
        PrivateData.hint(in.value()),
        "LOOKUP_HMAC",
        hash,
        "ISSUER_CODE",
        in.issuerCode(),
        "EXPIRES_ON",
        in.expiresOn(),
        "VALID_FROM",
        now()
      )
    );
    event(user, "IdentifierProposed", cif, null, null);
  }

  /** Links evidence only from a KYC case belonging to this CIF. */
  public void address(UserPrincipal user, String cif, AddressInput in) {
    var c = editable(user, cif);
    if (in.evidenceDocumentId() != null) bad(
      db.count(
          "SELECT COUNT(*) FROM M02_KYC_DOCUMENT D JOIN M02_KYC_CASE K ON K.CASE_ID=D.CASE_ID WHERE D.DOCUMENT_ID=? AND K.CIF_ID=?",
          in.evidenceDocumentId(),
          cif
        ) ==
        0,
      "INVALID_EVIDENCE",
      "Evidence must belong to this customer"
    );
    db.insert(
      SchemaTable.M02_CIF_ADDRESS,
      map(
        "ADDRESS_ID",
        id(),
        "PARTY_ID",
        c.get("PARTY_ID"),
        "ADDRESS_TYPE",
        in.addressType(),
        "LINE1",
        in.line1(),
        "LINE2",
        in.line2(),
        "CITY",
        in.city(),
        "REGION",
        in.region(),
        "POSTAL_CODE",
        in.postalCode(),
        "COUNTRY_CODE",
        in.countryCode(),
        "EVIDENCE_DOCUMENT_ID",
        in.evidenceDocumentId(),
        "VALID_FROM",
        now()
      )
    );
    event(user, "AddressProposed", cif, null, null);
  }

  /** Only one open case may exist per CIF; serialized on the customer row. */
  public Map<String, Object> openCase(
    UserPrincipal user,
    String cif,
    CaseInput in
  ) {
    var c = customer(cif, true);
    permit(user, "CIF_UPDATE", c, null);
    bad(
      "CLOSED".equals(str(c, "STATUS")),
      "CUSTOMER_CLOSED",
      "Closed customers cannot open cases"
    );
    bad(
      db.count(
          "SELECT COUNT(*) FROM M02_KYC_CASE WHERE CIF_ID=? AND STATUS NOT IN ('APPROVED','REJECTED','EXPIRED')",
          cif
        ) >
        0,
      "CASE_EXISTS",
      "Complete the current open case first"
    );
    return newCase(user, cif, in.caseType());
  }

  private Map<String, Object> newCase(
    UserPrincipal user,
    String cif,
    String type
  ) {
    String caseId = id();
    db.insert(
      SchemaTable.M02_KYC_CASE,
      map(
        "CASE_ID",
        caseId,
        "CIF_ID",
        cif,
        "CASE_TYPE",
        type,
        "MAKER_USER_ID",
        user.userId()
      )
    );
    // A pending replacement profile must not qualify for new products using unapproved facts.
    db
      .jdbc()
      .update(
        "UPDATE M02_CIF_CUSTOMER SET KYC_STATUS='PENDING',STATUS=CASE WHEN STATUS='ACTIVE' THEN 'RESTRICTED' ELSE STATUS END,UPDATED_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE CIF_ID=? AND KYC_STATUS='VERIFIED'",
        now(),
        cif
      );
    event(user, "KycCaseOpened", cif, caseId, null);
    return db.one("SELECT * FROM M02_KYC_CASE WHERE CASE_ID=?", caseId);
  }

  /** Reusable case authorization for the document adapter. */
  public Map<String, Object> caseAccess(
    UserPrincipal user,
    String caseId,
    String permission,
    boolean lock
  ) {
    var initial = db.one("SELECT * FROM M02_KYC_CASE WHERE CASE_ID=?", caseId);
    var c = customer(str(initial, "CIF_ID"), lock);
    permit(user, permission, c, null);
    return lock
      ? db.one("SELECT * FROM M02_KYC_CASE WHERE CASE_ID=? FOR UPDATE", caseId)
      : initial;
  }

  public Map<String, Object> caseDetail(UserPrincipal user, String caseId) {
    var k = caseAccess(user, caseId, "CIF_READ", false);
    return map(
      "case",
      k,
      "documents",
      db.rows(
        "SELECT DOCUMENT_ID,CASE_ID,DOCUMENT_TYPE,VERSION_NO,SHA256_HEX,MIME_TYPE,BYTE_SIZE,SCAN_STATUS,VERIFICATION_STATUS,UPLOADED_BY_USER_ID,UPLOADED_AT FROM M02_KYC_DOCUMENT WHERE CASE_ID=? ORDER BY DOCUMENT_TYPE,VERSION_NO DESC",
        caseId
      ),
      "reviews",
      db.rows(
        "SELECT * FROM M02_KYC_REVIEW WHERE CASE_ID=? ORDER BY REVIEWED_AT",
        caseId
      )
    );
  }

  /** Ready checks are repeated at submission and approval, never trusted from the UI. */
  public List<String> completeness(
    String cif,
    String caseId,
    boolean approval
  ) {
    var c = customer(cif, false);
    String party = str(c, "PARTY_ID");
    List<String> issues = new ArrayList<>();
    for (String table : List.of(
      "M02_CIF_NAME",
      "M02_CIF_CONTACT",
      "M02_CIF_ADDRESS",
      "M02_CIF_IDENTIFIER"
    ))
      if (
        db.count(
          "SELECT COUNT(*) FROM " +
            table +
            " WHERE PARTY_ID=? AND VALID_TO IS NULL AND VERIFICATION_STATUS<>'REJECTED'",
          party
        ) ==
        0
      ) issues.add("MISSING_" + table.substring(8));
    for (String type : List.of("IDENTITY", "ADDRESS"))
      if (
        db.count(
          "SELECT COUNT(*) FROM M02_KYC_DOCUMENT WHERE CASE_ID=? AND DOCUMENT_TYPE=?",
          caseId,
          type
        ) ==
        0
      ) issues.add("MISSING_" + type + "_DOCUMENT");
    if (approval) {
      var latest = db.rows(
        "SELECT D.* FROM M02_KYC_DOCUMENT D WHERE CASE_ID=? AND VERSION_NO=(SELECT MAX(X.VERSION_NO) FROM M02_KYC_DOCUMENT X WHERE X.CASE_ID=D.CASE_ID AND X.DOCUMENT_TYPE=D.DOCUMENT_TYPE)",
        caseId
      );
      if (
        latest
          .stream()
          .anyMatch(
            d ->
              !"CLEAN".equals(str(d, "SCAN_STATUS")) ||
              !"VERIFIED".equals(str(d, "VERIFICATION_STATUS"))
          )
      ) issues.add("DOCUMENT_REVIEW_REQUIRED");
      if (
        db.count(
          "SELECT COUNT(*) FROM M02_CIF_IDENTIFIER WHERE PARTY_ID=? AND VALID_TO IS NULL AND VERIFICATION_STATUS<>'REJECTED' AND EXPIRES_ON IS NOT NULL AND EXPIRES_ON<=?",
          party,
          LocalDate.now(clock)
        ) >
        0
      ) issues.add("EXPIRED_IDENTIFIER");
    }
    return issues;
  }

  /** Submission freezes profile edits until an independent request-for-information decision. */
  public Map<String, Object> submit(
    UserPrincipal user,
    String caseId,
    Revision in
  ) {
    var k = caseAccess(user, caseId, "CIF_UPDATE", true);
    version(k, in.rowVersion());
    draft(k, user);
    String cif = str(k, "CIF_ID");
    List<String> issues = completeness(cif, caseId, false);
    bad(!issues.isEmpty(), "KYC_INCOMPLETE", String.join(", ", issues));
    db
      .jdbc()
      .update(
        "UPDATE M02_KYC_CASE SET STATUS='SUBMITTED',SUBMITTED_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE CASE_ID=?",
        now(),
        caseId
      );
    db
      .jdbc()
      .update(
        "UPDATE M02_CIF_CUSTOMER SET STATUS=CASE WHEN STATUS='PROSPECT' THEN 'PENDING_VERIFICATION' ELSE STATUS END,KYC_STATUS='PENDING',UPDATED_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE CIF_ID=?",
        now(),
        cif
      );
    event(user, "KycSubmitted", cif, caseId, in.reason());
    return caseDetail(user, caseId);
  }

  public static void draft(Map<String, Object> k, UserPrincipal user) {
    bad(
      !Set.of("DRAFT", "DOCUMENTS_PENDING", "MORE_INFO_REQUIRED").contains(
        str(k, "STATUS")
      ),
      "CASE_FROZEN",
      "Case is not editable"
    );
    bad(
      !user.userId().equals(str(k, "MAKER_USER_ID")),
      "CASE_MAKER_ONLY",
      "Only this case's maker can submit or upload evidence"
    );
  }

  /** Routes to an officer with current KYC_REVIEW scope; assignment does not approve. */
  public Map<String, Object> assign(
    UserPrincipal user,
    String caseId,
    Assignment in
  ) {
    var k = caseAccess(user, caseId, "KYC_REVIEW", true);
    version(k, in.rowVersion());
    bad(
      !Set.of("SUBMITTED", "UNDER_REVIEW").contains(str(k, "STATUS")),
      "INVALID_STATE",
      "Submit the case first"
    );
    bad(
      in.officerUserId().equals(str(k, "MAKER_USER_ID")),
      "MAKER_CHECKER_SEPARATION",
      "Maker cannot review their case"
    );
    db.one(
      "SELECT * FROM M01_IAM_USER WHERE USER_ID=? AND STATUS='ACTIVE' AND USER_TYPE='EMPLOYEE'",
      in.officerUserId()
    );
    // Officer entitlement is checked again when that officer performs the decision.
    var c = customer(str(k, "CIF_ID"), false);
    bad(
      db.count(
          "SELECT COUNT(*) FROM M01_IAM_USER_ROLE A JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID JOIN M01_IAM_ROLE_PERMISSION RP ON RP.ROLE_ID=A.ROLE_ID JOIN M01_IAM_PERMISSION P ON P.PERMISSION_ID=RP.PERMISSION_ID WHERE A.USER_ID=? AND A.STATUS='ACTIVE' AND R.STATUS='ACTIVE' AND P.PERMISSION_CODE='KYC_REVIEW' AND A.VALID_FROM<=? AND (A.VALID_TO IS NULL OR A.VALID_TO>?) AND (A.SCOPE_TYPE='GLOBAL' OR (A.SCOPE_TYPE='BRANCH' AND A.SCOPE_REF=?)) AND NOT EXISTS (SELECT 1 FROM M01_IAM_USER_ROLE_SCOPE D WHERE D.ASSIGNMENT_ID=A.ASSIGNMENT_ID AND D.DIMENSION_CODE IN ('PRODUCT_TYPE','CURRENCY'))",
          in.officerUserId(),
          now(),
          now(),
          c.get("HOME_BRANCH_REF")
        ) ==
        0,
      "OFFICER_NOT_ELIGIBLE",
      "Officer needs effective KYC_REVIEW scope for this customer"
    );
    db
      .jdbc()
      .update(
        "UPDATE M02_KYC_CASE SET STATUS='UNDER_REVIEW',ASSIGNED_OFFICER_USER_ID=?,ROW_VERSION=ROW_VERSION+1 WHERE CASE_ID=?",
        in.officerUserId(),
        caseId
      );
    event(user, "KycAssigned", str(k, "CIF_ID"), caseId, null);
    return caseDetail(user, caseId);
  }

  /** Independent checker decisions preserve review history and activate verified evidence. */
  public Map<String, Object> review(
    UserPrincipal user,
    String caseId,
    ReviewInput in
  ) {
    var k = caseAccess(user, caseId, "KYC_REVIEW", true);
    version(k, in.rowVersion());
    String cif = str(k, "CIF_ID"),
      maker = str(k, "MAKER_USER_ID");
    var c = customer(cif, true);
    permit(user, "KYC_REVIEW", c, maker);
    bad(
      !Set.of("SUBMITTED", "UNDER_REVIEW").contains(str(k, "STATUS")),
      "INVALID_STATE",
      "Case must be submitted"
    );
    bad(
      k.get("ASSIGNED_OFFICER_USER_ID") != null &&
        !user.userId().equals(str(k, "ASSIGNED_OFFICER_USER_ID")),
      "OFFICER_MISMATCH",
      "Only the assigned officer can decide"
    );
    String outcome = in.outcome();
    if ("APPROVE".equals(outcome)) {
      bad(
        in.riskLevel() == null ||
          in.reviewDueAt() == null ||
          !in.reviewDueAt().isAfter(now()),
        "REVIEW_DUE_REQUIRED",
        "Approval needs risk and a future periodic review date"
      );
      var issues = completeness(cif, caseId, true);
      bad(!issues.isEmpty(), "KYC_INCOMPLETE", String.join(", ", issues));
    }
    db.insert(
      SchemaTable.M02_KYC_REVIEW,
      map(
        "REVIEW_ID",
        id(),
        "CASE_ID",
        caseId,
        "MAKER_USER_ID",
        maker,
        "REVIEWER_USER_ID",
        user.userId(),
        "REVIEWER_SESSION_ID",
        user.sessionId(),
        "OUTCOME",
        outcome,
        "REASON_CODE",
        in.reasonCode(),
        "COMMENTS",
        in.comments(),
        "CORRELATION_ID",
        Correlation.current()
      )
    );
    String state = switch (outcome) {
      case "APPROVE" -> "APPROVED";
      case "REJECT" -> "REJECTED";
      default -> "MORE_INFO_REQUIRED";
    };
    db
      .jdbc()
      .update(
        "UPDATE M02_KYC_CASE SET STATUS=?,RISK_LEVEL=?,REVIEW_DUE_AT=?,DECIDED_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE CASE_ID=?",
        state,
        in.riskLevel(),
        in.reviewDueAt(),
        "REQUEST_INFO".equals(outcome) ? null : now(),
        caseId
      );
    if ("APPROVE".equals(outcome)) {
      approveHistory(str(c, "PARTY_ID"));
      db
        .jdbc()
        .update(
          "UPDATE M02_CIF_CUSTOMER SET STATUS=CASE WHEN STATUS IN ('PROSPECT','PENDING_VERIFICATION','RESTRICTED') THEN 'ACTIVE' ELSE STATUS END,KYC_STATUS='VERIFIED',RISK_LEVEL=?,NEXT_REVIEW_DUE_AT=?,UPDATED_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE CIF_ID=?",
          in.riskLevel(),
          in.reviewDueAt(),
          now(),
          cif
        );
    }
    if ("REJECT".equals(outcome)) {
      db
        .jdbc()
        .update(
          "UPDATE M02_CIF_CUSTOMER SET KYC_STATUS='REJECTED',STATUS=CASE WHEN STATUS='ACTIVE' THEN 'RESTRICTED' ELSE STATUS END,UPDATED_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE CIF_ID=?",
          now(),
          cif
        );
      rejectPending(str(c, "PARTY_ID"));
    }
    event(
      user,
      "APPROVE".equals(outcome)
        ? "KycVerified"
        : "REJECT".equals(outcome)
          ? "KycRejected"
          : "KycInformationRequested",
      cif,
      caseId,
      in.reasonCode()
    );
    return caseDetail(user, caseId);
  }

  private void approveHistory(String party) {
    for (String table : List.of(
      "M02_CIF_NAME",
      "M02_CIF_CONTACT",
      "M02_CIF_ADDRESS",
      "M02_CIF_IDENTIFIER"
    )) {
      String type = switch (table) {
        case "M02_CIF_NAME" -> "NAME_TYPE";
        case "M02_CIF_CONTACT" -> "CONTACT_TYPE";
        case "M02_CIF_ADDRESS" -> "ADDRESS_TYPE";
        default -> "IDENTIFIER_TYPE";
      };
      // Latest proposed value per type wins; verified old values remain as history.
      db
        .jdbc()
        .update(
          "UPDATE " +
            table +
            " X SET VALID_TO=? WHERE X.PARTY_ID=? AND X.VALID_TO IS NULL AND EXISTS (SELECT 1 FROM " +
            table +
            " N WHERE N.PARTY_ID=X.PARTY_ID AND N." +
            type +
            "=X." +
            type +
            " AND N.VALID_TO IS NULL AND N.VERIFICATION_STATUS='PENDING' AND N.VALID_FROM>X.VALID_FROM)",
          now(),
          party
        );
      db
        .jdbc()
        .update(
          "UPDATE " +
            table +
            " SET VERIFICATION_STATUS='VERIFIED'" +
            (table.equals("M02_CIF_CONTACT") ? ",VERIFIED_AT=?" : "") +
            " WHERE PARTY_ID=? AND VALID_TO IS NULL AND VERIFICATION_STATUS='PENDING'",
          table.equals("M02_CIF_CONTACT")
            ? new Object[] { now(), party }
            : new Object[] { party }
        );
    }
  }

  private void rejectPending(String party) {
    for (String t : List.of(
      "M02_CIF_NAME",
      "M02_CIF_CONTACT",
      "M02_CIF_ADDRESS",
      "M02_CIF_IDENTIFIER"
    ))
      db
        .jdbc()
        .update(
          "UPDATE " +
            t +
            " SET VERIFICATION_STATUS='REJECTED',VALID_TO=? WHERE PARTY_ID=? AND VERIFICATION_STATUS='PENDING' AND VALID_TO IS NULL",
          now(),
          party
        );
  }

  /** Lifecycle checks keep CIF state separate from digital credentials and account state. */
  public Map<String, Object> status(
    UserPrincipal user,
    String cif,
    StatusInput in
  ) {
    var c = customer(cif, true);
    permit(user, "CIF_UPDATE", c, null);
    version(c, in.rowVersion());
    bad(
      "CLOSED".equals(str(c, "STATUS")),
      "CUSTOMER_CLOSED",
      "Closure is terminal"
    );
    bad(
      "ACTIVE".equals(in.status()) && !"VERIFIED".equals(str(c, "KYC_STATUS")),
      "KYC_REQUIRED",
      "Only verified customers can activate"
    );
    if ("CLOSED".equals(in.status())) {
      bad(db.count("SELECT COUNT(*) FROM M11_CC_CARD WHERE CIF_ID=? AND STATUS<>'CLOSED'",cif)>0
        || db.count("SELECT COUNT(*) FROM M11_CC_APPLICATION WHERE CIF_ID=? AND STATUS='PENDING'",cif)>0,
        "OPEN_CREDIT_CARDS", "Close credit cards and cancel pending card applications before CIF closure");
      bad(
        db.count(
            "SELECT COUNT(*) FROM M04_BANK_ACCOUNT A WHERE (A.PRIMARY_CIF_ID=? OR EXISTS (SELECT 1 FROM M04_ACCOUNT_PARTY AP WHERE AP.ACCOUNT_ID=A.ACCOUNT_ID AND AP.CIF_ID=?)) AND A.LIFECYCLE_STATUS NOT IN ('CLOSED','CANCELLED')",
            cif,
            cif
          ) >
          0,
        "OPEN_ACCOUNTS",
        "Close customer accounts before CIF closure"
      );
    }
    db
      .jdbc()
      .update(
        "UPDATE M02_CIF_CUSTOMER SET STATUS=?,UPDATED_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE CIF_ID=?",
        in.status(),
        now(),
        cif
      );
    if ("CLOSED".equals(in.status())) iamLifecycle.customerClosed(cif);
    event(
      user,
      "CLOSED".equals(in.status()) ? "CustomerClosed" : "CustomerStatusChanged",
      cif,
      null,
      in.reason()
    );
    return detail(user, cif);
  }

  /** Explicit periodic-review sweep, scoped to the caller, with committed expiry evidence. */
  public int expireDue(UserPrincipal user) {
    int changed = 0;
    for (var row : db.rows(
      "SELECT * FROM M02_CIF_CUSTOMER WHERE KYC_STATUS='VERIFIED' AND NEXT_REVIEW_DUE_AT<=? AND STATUS<>'CLOSED'",
      now()
    )) {
      if (
        !access.allowed(
          user,
          "KYC_REVIEW",
          str(row, "HOME_BRANCH_REF"),
          str(row, "CIF_ID"),
          null,
          null
        )
      ) continue;
      String id = str(row, "CIF_ID");
      var c = customer(id, true);
      if (!"VERIFIED".equals(str(c, "KYC_STATUS"))) continue;
      db
        .jdbc()
        .update(
          "UPDATE M02_CIF_CUSTOMER SET KYC_STATUS='EXPIRED',STATUS=CASE WHEN STATUS='ACTIVE' THEN 'RESTRICTED' ELSE STATUS END,ROW_VERSION=ROW_VERSION+1,UPDATED_AT=? WHERE CIF_ID=?",
          now(),
          id
        );
      db
        .jdbc()
        .update(
          "UPDATE M02_KYC_CASE SET STATUS='EXPIRED',ROW_VERSION=ROW_VERSION+1 WHERE CIF_ID=? AND STATUS='APPROVED' AND REVIEW_DUE_AT<=?",
          id,
          now()
        );
      event(user, "KycExpired", id, null, "PERIODIC_REVIEW_DUE");
      changed++;
    }
    return changed;
  }

  /** Consent is recorded with evidence; withdrawal preserves the original record. */
  public void consent(UserPrincipal user, String cif, ConsentInput in) {
    var c = customer(cif, true);
    permit(user, "CIF_UPDATE", c, null);
    db.insert(
      SchemaTable.M02_CIF_CONSENT,
      map(
        "CONSENT_ID",
        id(),
        "CIF_ID",
        cif,
        "PURPOSE_CODE",
        in.purposeCode(),
        "STATUS",
        "GRANTED",
        "CAPTURE_CHANNEL",
        in.captureChannel(),
        "CAPTURED_AT",
        now(),
        "EVIDENCE_REF",
        in.evidenceRef()
      )
    );
    event(user, "ConsentGranted", cif, null, in.purposeCode());
  }

  public void withdrawConsent(UserPrincipal user, String cif, String consent) {
    var c = customer(cif, true);
    permit(user, "CIF_UPDATE", c, null);
    int n = db
      .jdbc()
      .update(
        "UPDATE M02_CIF_CONSENT SET STATUS='WITHDRAWN',WITHDRAWN_AT=? WHERE CONSENT_ID=? AND CIF_ID=? AND STATUS='GRANTED'",
        now(),
        consent,
        cif
      );
    bad(
      n == 0,
      "CONSENT_NOT_ACTIVE",
      "Consent is missing or already withdrawn"
    );
    event(user, "ConsentWithdrawn", cif, null, null);
  }

  /** Relationship records do not implicitly grant digital-user or account authority. */
  public void relationship(
    UserPrincipal user,
    String cif,
    RelationshipInput in
  ) {
    var c = customer(cif, true);
    var target = customer(in.targetCifId(), false);
    permit(user, "CIF_UPDATE", c, null);
    permit(user, "CIF_READ", target, null);
    bad(
      cif.equals(in.targetCifId()),
      "SELF_RELATIONSHIP",
      "Choose another party"
    );
    bad(
      in.validTo() != null && !in.validTo().isAfter(now()),
      "INVALID_DATES",
      "End date must be in the future"
    );
    db.insert(
      SchemaTable.M02_CIF_RELATIONSHIP,
      map(
        "RELATIONSHIP_ID",
        id(),
        "SOURCE_PARTY_ID",
        c.get("PARTY_ID"),
        "TARGET_PARTY_ID",
        target.get("PARTY_ID"),
        "RELATIONSHIP_TYPE",
        in.relationshipType(),
        "OPERATING_AUTHORITY",
        in.operatingAuthority(),
        "VALID_FROM",
        now(),
        "VALID_TO",
        in.validTo()
      )
    );
    event(user, "RelationshipCreated", cif, null, null);
  }

  public void endRelationship(UserPrincipal user, String cif, String rel) {
    var c = customer(cif, true);
    permit(user, "CIF_UPDATE", c, null);
    bad(
      db
          .jdbc()
          .update(
            "UPDATE M02_CIF_RELATIONSHIP SET VALID_TO=? WHERE RELATIONSHIP_ID=? AND SOURCE_PARTY_ID=? AND VALID_TO IS NULL",
            now(),
            rel,
            c.get("PARTY_ID")
          ) ==
        0,
      "RELATIONSHIP_NOT_ACTIVE",
      "Relationship not active"
    );
    event(user, "RelationshipEnded", cif, null, null);
  }

  /** Minimal integration facts intentionally exclude direct personal identifiers. */
  @Transactional(readOnly = true)
  public Facts facts(String cif) {
    var c = customer(cif, false);
    var p = db.one(
      "SELECT * FROM M02_CIF_PARTY WHERE PARTY_ID=?",
      c.get("PARTY_ID")
    );
    var addresses = db.rows(
      "SELECT COUNTRY_CODE FROM M02_CIF_ADDRESS WHERE PARTY_ID=? AND VERIFICATION_STATUS='VERIFIED' AND VALID_TO IS NULL ORDER BY VALID_FROM DESC",
      c.get("PARTY_ID")
    );
    // Fail closed immediately; an operator's expiry sweep only materializes the state and event.
    String kyc = str(c, "KYC_STATUS");
    var due = (OffsetDateTime) c.get("NEXT_REVIEW_DUE_AT");
    if (
      "VERIFIED".equals(kyc) &&
      ((due != null && !due.isAfter(now())) ||
        db.count(
          "SELECT COUNT(*) FROM M02_CIF_IDENTIFIER WHERE PARTY_ID=? AND VALID_TO IS NULL AND VERIFICATION_STATUS='VERIFIED' AND EXPIRES_ON<=?",
          c.get("PARTY_ID"),
          LocalDate.now(clock)
        ) >
        0)
    ) kyc = "EXPIRED";
    return new Facts(
      cif,
      str(c, "STATUS"),
      kyc,
      str(c, "HOME_BRANCH_REF"),
      str(c, "SEGMENT_CODE"),
      str(c, "RISK_LEVEL"),
      str(p, "PARTY_TYPE"),
      BusinessRepository.localDate(p.get("DATE_OF_BIRTH")),
      BusinessRepository.localDate(p.get("INCORPORATED_ON")),
      addresses.isEmpty() ? null : str(addresses.get(0), "COUNTRY_CODE")
    );
  }

  /** External adapters must authenticate and authorize before obtaining minimal eligibility facts. */
  public Facts factsFor(UserPrincipal user, String cif) {
    permit(user, "CIF_READ", customer(cif, false), null);
    audit(user, "CustomerFactsRead", cif, null, null);
    return facts(cif);
  }

  /** Records safe aggregate references, never names, document contents or plaintext identifiers. */
  public void event(
    UserPrincipal user,
    String type,
    String cif,
    String caseId,
    String reason
  ) {
    audit(user, type, cif, caseId, reason);
    db.insert(
      SchemaTable.M02_CIF_OUTBOX_EVENT,
      map(
        "EVENT_ID",
        id(),
        "EVENT_TYPE",
        type,
        "SCHEMA_VERSION",
        1,
        "AGGREGATE_TYPE",
        "CUSTOMER",
        "AGGREGATE_ID",
        cif,
        "CORRELATION_ID",
        Correlation.current(),
        "PAYLOAD",
        "{\"schemaVersion\":1,\"cifId\":\"" +
          cif +
          "\",\"eventType\":\"" +
          type +
          "\"}"
      )
    );
  }

  private void audit(
    UserPrincipal user,
    String type,
    String cif,
    String caseId,
    String reason
  ) {
    db.insert(
      SchemaTable.M02_CIF_AUDIT_EVENT,
      map(
        "AUDIT_ID",
        id(),
        "EVENT_TYPE",
        type,
        "ACTOR_USER_ID",
        user.userId(),
        "SESSION_ID",
        user.sessionId(),
        "CIF_ID",
        cif,
        "CASE_ID",
        caseId,
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
  }

  public List<Map<String, Object>> audit(UserPrincipal user, String cif) {
    var c = customer(cif, false);
    permit(user, "CIF_READ", c, null);
    return db.rows(
      "SELECT * FROM M02_CIF_AUDIT_EVENT WHERE CIF_ID=? ORDER BY OCCURRED_AT DESC FETCH FIRST 200 ROWS ONLY",
      cif
    );
  }
}

