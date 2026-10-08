package com.moneybags.account.db;

import com.moneybags.account.api.ApiException;
import com.moneybags.account.api.Models.*;
import com.moneybags.account.domain.AccountNumbers;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

/** Oracle JDBC operations against the supplied M04 tables; no schema creation occurs here. */
@Repository
public class AccountStore {
    private final JdbcClient db;
    public AccountStore(JdbcClient db) { this.db = db; }

    /** Finds an account, optionally taking an Oracle row lock for a state transition. */
    public AccountView account(long id, boolean lock) {
        return db.sql("SELECT * FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(this::mapAccount).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found"));
    }
    /** Finds a prior opening so callers can safely retry with the same request ID. */
    public Optional<AccountView> byOpenRequest(String requestId) {
        return db.sql("SELECT * FROM M04_BANK_ACCOUNT WHERE OPEN_REQUEST_ID = :id")
                .param("id", requestId).query(this::mapAccount).optional();
    }
    public String createdBy(long id) {
        return db.sql("SELECT CREATED_BY_USER_ID FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=:id")
                .param("id",id).query(String.class).single();
    }
    /** Resolves a customer-facing account number for payment and service workflows. */
    public Optional<AccountView> byNumber(String number) {
        return db.sql("SELECT * FROM M04_BANK_ACCOUNT WHERE ACCOUNT_NUMBER=:n")
                .param("n", number).query(this::mapAccount).optional();
    }
    /** Lists bounded account summaries for every active holder role of a CIF. */
    public List<AccountView> byCif(String cif, int limit, int offset) {
        return db.sql("SELECT a.* FROM M04_BANK_ACCOUNT a WHERE EXISTS (SELECT 1 FROM M04_ACCOUNT_PARTY p WHERE p.ACCOUNT_ID=a.ACCOUNT_ID AND p.CIF_ID=:cif AND p.IS_ACTIVE='Y' AND p.EFFECTIVE_FROM<=SYSDATE AND (p.EFFECTIVE_TO IS NULL OR p.EFFECTIVE_TO>SYSDATE)) ORDER BY a.ACCOUNT_ID OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY")
                .param("cif", cif).param("offset", offset).param("limit", limit).query(this::mapAccount).list();
    }
    /** Looks up whether a CIF currently has a role on this account. */
    public boolean activeParty(long accountId, String cif) {
        if (cif == null) return false;
        return db.sql("SELECT COUNT(*) FROM M04_ACCOUNT_PARTY WHERE ACCOUNT_ID=:a AND CIF_ID=:c AND IS_ACTIVE='Y' AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>SYSDATE)")
                .param("a", accountId).param("c", cif).query(Long.class).single() > 0;
    }
    /** Requires an active primary-holder role for customer-initiated changes. */
    public boolean activePrimary(long accountId, String cif) {
        if (cif == null) return false;
        return db.sql("SELECT COUNT(*) FROM M04_ACCOUNT_PARTY WHERE ACCOUNT_ID=:a AND CIF_ID=:c AND PARTY_ROLE='PRIMARY_HOLDER' AND IS_ACTIVE='Y' AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>SYSDATE)")
                .param("a", accountId).param("c", cif).query(Long.class).single() > 0;
    }
    /** Checks whether at least one current guardian or joint-holder role exists. */
    public boolean hasActiveRole(long accountId, String role) {
        return activeRoleCount(accountId, role) > 0;
    }
    /** Counts currently effective parties in a role for operation-mode invariants. */
    public long activeRoleCount(long accountId, String role) {
        return db.sql("SELECT COUNT(*) FROM M04_ACCOUNT_PARTY WHERE ACCOUNT_ID=:a AND PARTY_ROLE=:r AND IS_ACTIVE='Y' AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>SYSDATE)")
                .param("a", accountId).param("r", role).query(Long.class).single();
    }
    /** Reads the role being ended to prevent removal of required guardian authority. */
    public Optional<String> partyRole(long accountId, long partyId) {
        return db.sql("SELECT PARTY_ROLE FROM M04_ACCOUNT_PARTY WHERE ACCOUNT_ID=:a AND ACCOUNT_PARTY_ID=:p AND IS_ACTIVE='Y'")
                .param("a", accountId).param("p", partyId).query(String.class).optional();
    }
    /** Ends all guardian roles after an approved majority transition. */
    public void endGuardians(long accountId) {
        db.sql("UPDATE M04_ACCOUNT_PARTY SET IS_ACTIVE='N',EFFECTIVE_TO=CASE WHEN SYSDATE<=EFFECTIVE_FROM THEN EFFECTIVE_FROM+1/86400 ELSE SYSDATE END WHERE ACCOUNT_ID=:a AND PARTY_ROLE='GUARDIAN' AND IS_ACTIVE='Y'")
                .param("a", accountId).update();
    }
    /** Gets the next database sequence value and formats a bounded account number. */
    public String nextNumber(String branch, String productType) {
        long seq = db.sql("SELECT M04_ACCOUNT_NUMBER_SEQ.NEXTVAL FROM DUAL").query(Long.class).single();
        return AccountNumbers.format(branch, productType, seq);
    }
    /** Creates a pending account and returns its generated Oracle identity. */
    public long insertAccount(OpenAccount command, String number, String actor, boolean minor) {
        db.sql("INSERT INTO M04_BANK_ACCOUNT (ACCOUNT_NUMBER,PRIMARY_CIF_ID,PRODUCT_ID,PRODUCT_VERSION_ID,BRANCH_CODE,CURRENCY_CODE,LIFECYCLE_STATUS,ACCOUNT_STATUS,ACCOUNT_OPERATION_MODE,MAJORITY_REVIEW_STATUS,OPEN_REQUEST_ID,CREATED_BY_USER_ID) VALUES (:n,:c,:p,:v,:b,'INR','PENDING_OPEN','PENDING_OPEN',:m,:r,:q,:u)")
                .param("n", number).param("c", command.primaryCifId()).param("p", command.productId())
                .param("v", command.productVersionId()).param("b", command.branchCode())
                .param("m", command.operationMode()).param("r", minor ? "PENDING" : "NOT_APPLICABLE")
                .param("q", command.requestId()).param("u", actor).update();
        return byOpenRequest(command.requestId()).orElseThrow().id();
    }
    /** Saves one CIF-backed holder or signatory assignment. */
    public void addParty(long accountId, PartyInput party, String actor) {
        db.sql("INSERT INTO M04_ACCOUNT_PARTY (ACCOUNT_ID,CIF_ID,PARTY_ROLE,OPERATING_INSTRUCTION,IS_ACTIVE,EFFECTIVE_FROM,CREATED_BY_USER_ID) VALUES (:a,:c,:r,:o,'Y',SYSDATE,:u)")
                .param("a", accountId).param("c", party.cifId()).param("r", party.role())
                .param("o", party.operatingInstruction()).param("u", actor).update();
    }
    /** Ends an existing non-primary role without deleting its history. */
    public int endParty(long accountId, long partyId) {
        return db.sql("UPDATE M04_ACCOUNT_PARTY SET IS_ACTIVE='N',EFFECTIVE_TO=CASE WHEN SYSDATE<=EFFECTIVE_FROM THEN EFFECTIVE_FROM+1/86400 ELSE SYSDATE END WHERE ACCOUNT_ID=:a AND ACCOUNT_PARTY_ID=:p AND IS_ACTIVE='Y' AND PARTY_ROLE<>'PRIMARY_HOLDER'")
                .param("a", accountId).param("p", partyId).update();
    }
    /** Records the exact CIF, Product Master, and IAM evidence used in opening. */
    public void openingEvidence(long accountId, String attempt, String cifRef, String productRef,
                                String iamRef, long versionId, String ruleHash, String result, String failure) {
        db.sql("INSERT INTO M04_ACCOUNT_OPENING_EVIDENCE (ACCOUNT_ID,ATTEMPT_ID,CIF_DECISION_REF,PRODUCT_DECISION_REF,IAM_DECISION_REF,PRODUCT_VERSION_ID,PRODUCT_RULE_SET_HASH,DECISION_RESULT,FAILURE_REASON_CODE) VALUES (:a,:t,:c,:p,:i,:v,:h,:r,:f)")
                .param("a", accountId).param("t", attempt).param("c", cifRef).param("p", productRef)
                .param("i", iamRef).param("v", versionId).param("h", ruleHash)
                .param("r", result).param("f", failure).update();
    }
    /** Replaces active nominations while retaining old rows for audit and retention policy. */
    public void replaceNominees(long accountId, List<NomineeInput> nominees, String actor) {
        db.sql("UPDATE M04_ACCOUNT_NOMINEE SET IS_ACTIVE='N' WHERE ACCOUNT_ID=:a AND IS_ACTIVE='Y'")
                .param("a", accountId).update();
        for (NomineeInput n : nominees) {
            db.sql("INSERT INTO M04_ACCOUNT_NOMINEE (ACCOUNT_ID,NOMINEE_CIF_ID,NOMINEE_NAME,RELATIONSHIP,DATE_OF_BIRTH,MOBILE_NUMBER,ADDRESS_LINE1,ADDRESS_LINE2,CITY,STATE,POSTAL_CODE,SHARE_PERCENTAGE,GUARDIAN_NAME,GUARDIAN_RELATIONSHIP,CREATED_BY_USER_ID) VALUES (:a,:c,:n,:r,:d,:m,:l1,:l2,:city,:state,:postal,:share,:g,:gr,:u)")
                    .param("a", accountId).param("c", n.nomineeCifId()).param("n", n.name())
                    .param("r", n.relationship()).param("d", n.dateOfBirth() == null ? null : java.sql.Date.valueOf(n.dateOfBirth()))
                    .param("m", n.mobileNumber()).param("l1", n.addressLine1()).param("l2", n.addressLine2())
                    .param("city", n.city()).param("state", n.state()).param("postal", n.postalCode())
                    .param("share", n.sharePercentage()).param("g", n.guardianName())
                    .param("gr", n.guardianRelationship()).param("u", actor).update();
        }
    }
    /** Returns account-owned rows from a fixed allowlist, preventing SQL identifier injection. */
    public List<Map<String, Object>> children(long accountId, String kind) {
        if ("parties".equals(kind)) {
            return db.sql("SELECT ap.*, (SELECT n.FULL_NAME FROM M02_CIF_CUSTOMER c " +
                    "JOIN M02_CIF_NAME n ON n.PARTY_ID=c.PARTY_ID " +
                    "WHERE c.CIF_ID=ap.CIF_ID AND n.NAME_TYPE='LEGAL' AND n.VALID_TO IS NULL " +
                    "ORDER BY n.VALID_FROM DESC FETCH FIRST 1 ROW ONLY) AS PARTY_NAME " +
                    "FROM M04_ACCOUNT_PARTY ap WHERE ap.ACCOUNT_ID=:a " +
                    "ORDER BY ap.ACCOUNT_PARTY_ID DESC FETCH FIRST 200 ROWS ONLY")
                    .param("a", accountId).query().listOfRows();
        }
        String table = switch (kind) {
            case "nominees" -> "M04_ACCOUNT_NOMINEE";
            case "restrictions" -> "M04_ACCOUNT_RESTRICTION";
            case "limits" -> "M04_ACCOUNT_LIMIT";
            case "interest-overrides" -> "M04_ACCOUNT_INTEREST_OVERRIDE";
            case "status-history" -> "M04_ACCOUNT_STATUS_HISTORY";
            case "opening-evidence" -> "M04_ACCOUNT_OPENING_EVIDENCE";
            case "closures" -> "M04_ACCOUNT_CLOSURE_REQUEST";
            case "product-versions" -> "M04_ACCOUNT_PRODUCT_VERSION_HISTORY";
            case "majority-reviews" -> "M04_ACCOUNT_MAJORITY_REVIEW";
            case "control-sync" -> "M04_ACCOUNT_CONTROL_SYNC";
            default -> throw new IllegalArgumentException("Unknown account collection");
        };
        return db.sql("SELECT * FROM " + table + " WHERE ACCOUNT_ID=:a ORDER BY 1 DESC FETCH FIRST 200 ROWS ONLY")
                .param("a", accountId).query().listOfRows();
    }
    /** Returns only active CIF IDs and roles needed by peer authorization checks. */
    public List<Map<String, Object>> activeParties(long id) {
        return db.sql("SELECT CIF_ID,PARTY_ROLE,OPERATING_INSTRUCTION FROM M04_ACCOUNT_PARTY WHERE ACCOUNT_ID=:a AND IS_ACTIVE='Y' AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>SYSDATE)")
                .param("a", id).query().listOfRows();
    }
    /** Updates lifecycle and derived display status under a prior row lock. */
    public void lifecycle(long accountId, String lifecycle, String status, String actor) {
        db.sql("UPDATE M04_BANK_ACCOUNT SET LIFECYCLE_STATUS=:l,ACCOUNT_STATUS=:s,ACTIVATED_AT=CASE WHEN :l='ACTIVE' AND ACTIVATED_AT IS NULL THEN SYSTIMESTAMP ELSE ACTIVATED_AT END,CANCELLED_AT=CASE WHEN :l='CANCELLED' THEN SYSTIMESTAMP ELSE CANCELLED_AT END,CLOSED_AT=CASE WHEN :l='CLOSED' THEN SYSTIMESTAMP ELSE CLOSED_AT END,UPDATED_BY_USER_ID=:u,UPDATED_AT=SYSTIMESTAMP,ROW_VERSION=ROW_VERSION+1 WHERE ACCOUNT_ID=:a")
                .param("l", lifecycle).param("s", status).param("u", actor).param("a", accountId).update();
    }
    /** Changes only the display status after restrictions are acknowledged. */
    public void displayStatus(long accountId, String status, String actor) {
        db.sql("UPDATE M04_BANK_ACCOUNT SET ACCOUNT_STATUS=:s,UPDATED_BY_USER_ID=:u,UPDATED_AT=SYSTIMESTAMP,ROW_VERSION=ROW_VERSION+1 WHERE ACCOUNT_ID=:a AND LIFECYCLE_STATUS='ACTIVE'")
                .param("s", status).param("u", actor).param("a", accountId).update();
    }
    /** Changes an account's operation mode only after a reviewed majority decision. */
    public void operationMode(long accountId, String mode, String actor) {
        db.sql("UPDATE M04_BANK_ACCOUNT SET ACCOUNT_OPERATION_MODE=:m,MAJORITY_REVIEW_STATUS='COMPLETED',UPDATED_BY_USER_ID=:u,UPDATED_AT=SYSTIMESTAMP,ROW_VERSION=ROW_VERSION+1 WHERE ACCOUNT_ID=:a")
                .param("m", mode).param("u", actor).param("a", accountId).update();
    }
    /** Updates the visible majority-review state after escalation. */
    public void majorityStatus(long accountId, String reviewStatus, String actor) {
        db.sql("UPDATE M04_BANK_ACCOUNT SET MAJORITY_REVIEW_STATUS=:s,UPDATED_BY_USER_ID=:u,UPDATED_AT=SYSTIMESTAMP,ROW_VERSION=ROW_VERSION+1 WHERE ACCOUNT_ID=:a")
                .param("s", reviewStatus).param("u", actor).param("a", accountId).update();
    }
    /** Appends an immutable status history row. */
    public void statusHistory(long id, String kind, String oldStatus, String newStatus,
                              String reason, String remarks, String actor, String correlation) {
        db.sql("INSERT INTO M04_ACCOUNT_STATUS_HISTORY (ACCOUNT_ID,CHANGE_KIND,OLD_STATUS,NEW_STATUS,REASON_CODE,REASON_REMARKS,CHANGED_BY_USER_ID,CORRELATION_ID) VALUES (:a,:k,:o,:n,:r,:text,:u,:c)")
                .param("a", id).param("k", kind).param("o", oldStatus).param("n", newStatus)
                .param("r", reason).param("text", remarks).param("u", actor).param("c", correlation).update();
    }
    /** Maps Oracle's zoned timestamps and exact-decimal money into a public view. */
    private AccountView mapAccount(ResultSet rs, int ignored) throws SQLException {
        var positions=db.sql("SELECT POSTED_BALANCE,ACTIVE_HOLD_AMOUNT+ACTIVE_BLOCK_AMOUNT BLOCKED,ACTIVE_LIEN_AMOUNT,OVERDRAFT_LIMIT,SPENDABLE_BALANCE,POSITION_VERSION FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=:id").param("id",rs.getLong("ACCOUNT_ID")).query().listOfRows();
        var p=positions.isEmpty()?java.util.Map.<String,Object>of():positions.get(0);
        return new AccountView(rs.getLong("ACCOUNT_ID"), rs.getString("ACCOUNT_NUMBER"),
                rs.getString("PRIMARY_CIF_ID"), rs.getLong("PRODUCT_ID"), rs.getLong("PRODUCT_VERSION_ID"),
                rs.getString("BRANCH_CODE"), rs.getString("CURRENCY_CODE"),
                rs.getString("LIFECYCLE_STATUS"), rs.getString("ACCOUNT_STATUS"),
                rs.getString("ACCOUNT_OPERATION_MODE"), rs.getString("MAJORITY_REVIEW_STATUS"),
                (BigDecimal)p.getOrDefault("POSTED_BALANCE",BigDecimal.ZERO), (BigDecimal)p.getOrDefault("BLOCKED",BigDecimal.ZERO),
                (BigDecimal)p.getOrDefault("ACTIVE_LIEN_AMOUNT",BigDecimal.ZERO), (BigDecimal)p.getOrDefault("OVERDRAFT_LIMIT",BigDecimal.ZERO),
                (BigDecimal)p.getOrDefault("SPENDABLE_BALANCE",BigDecimal.ZERO),
                rs.getBigDecimal("INTEREST_ACCRUED"), rs.getObject("LAST_INTEREST_CALCULATION_AT", OffsetDateTime.class),
                rs.getObject("LAST_INTEREST_POSTING_AT", OffsetDateTime.class),
                ((Number)p.getOrDefault("POSITION_VERSION",0)).longValue(),
                rs.getLong("FINANCIAL_CONTROL_VERSION"), rs.getLong("ROW_VERSION"),
                rs.getObject("OPENED_AT", OffsetDateTime.class),
                rs.getObject("ACTIVATED_AT", OffsetDateTime.class),
                rs.getObject("CLOSED_AT", OffsetDateTime.class));
    }
}
