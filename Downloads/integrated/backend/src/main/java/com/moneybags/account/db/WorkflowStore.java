package com.moneybags.account.db;

import com.moneybags.account.api.ApiException;
import com.moneybags.account.api.Models.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.*;

/** Persists account controls, approvals, and closure cases in M04 tables. */
@Repository
public class WorkflowStore {
    private final JdbcClient db;
    public WorkflowStore(JdbcClient db) { this.db = db; }

    /** Converts a canonical UUID to Oracle RAW(16) without changing its byte order. */
    public static byte[] rawUuid(String value) {
        UUID uuid = UUID.fromString(value);
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
    /** Reads a control request by request ID to support retries. */
    public Optional<Map<String, Object>> controlByRequest(String requestId) {
        return db.sql("SELECT * FROM M04_ACCOUNT_CONTROL_SYNC WHERE REQUEST_ID=:r")
                .param("r", requestId).query().listOfRows().stream().findFirst();
    }
    /** Reads a restriction by its unique request ID. */
    public Optional<Map<String, Object>> restrictionByRequest(String requestId) {
        return db.sql("SELECT * FROM M04_ACCOUNT_RESTRICTION WHERE REQUEST_ID=:r")
                .param("r", requestId).query().listOfRows().stream().findFirst();
    }
    /** Reads a restriction while locked so apply/remove acknowledgements serialize. */
    public Map<String, Object> restriction(long id) {
        return db.sql("SELECT * FROM M04_ACCOUNT_RESTRICTION WHERE RESTRICTION_ID=:r FOR UPDATE")
                .param("r", id).query().singleRow();
    }
    /** Creates a pending restriction; it is ineffective until Module 5 acknowledges. */
    public long insertRestriction(long accountId, RestrictionCommand c, String actor) {
        db.sql("INSERT INTO M04_ACCOUNT_RESTRICTION (ACCOUNT_ID,REQUEST_ID,RESTRICTION_TYPE,RESTRICTION_AMOUNT,SOURCE_SYSTEM,SOURCE_REFERENCE,REASON_CODE,REASON_REMARKS,ENDS_AT,CREATED_BY_USER_ID) VALUES (:a,:r,:t,:v,:s,:ref,:reason,:remarks,:ends,:u)")
                .param("a", accountId).param("r", c.requestId()).param("t", c.type()).param("v", c.amount())
                .param("s", c.sourceSystem()).param("ref", c.sourceReference()).param("reason", c.reasonCode())
                .param("remarks", c.remarks()).param("ends", c.endsAt()).param("u", actor).update();
        return ((Number) restrictionByRequest(c.requestId()).orElseThrow().get("RESTRICTION_ID")).longValue();
    }
    /** Advances a restriction to pending removal; old controls remain effective. */
    public void pendingRemove(long restrictionId) {
        db.sql("UPDATE M04_ACCOUNT_RESTRICTION SET RESTRICTION_STATUS='PENDING_REMOVE' WHERE RESTRICTION_ID=:r AND RESTRICTION_STATUS='ACTIVE'")
                .param("r", restrictionId).update();
    }
    /** Returns true if account controls still prohibit closure. */
    public boolean liveRestriction(long accountId) {
        return db.sql("SELECT COUNT(*) FROM M04_ACCOUNT_RESTRICTION WHERE ACCOUNT_ID=:a AND RESTRICTION_STATUS IN ('PENDING_APPLY','ACTIVE','PENDING_REMOVE')")
                .param("a", accountId).query(Long.class).single() > 0;
    }
    /** Checks for existing version-bound overrides before an account adopts new rules. */
    public boolean activeOverrides(long accountId) {
        long limits = db.sql("SELECT COUNT(*) FROM M04_ACCOUNT_LIMIT WHERE ACCOUNT_ID=:a AND IS_ACTIVE='Y' AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>SYSDATE)")
                .param("a", accountId).query(Long.class).single();
        long rates = db.sql("SELECT COUNT(*) FROM M04_ACCOUNT_INTEREST_OVERRIDE WHERE ACCOUNT_ID=:a AND (EFFECTIVE_TO_AT IS NULL OR EFFECTIVE_TO_AT>SYSTIMESTAMP)")
                .param("a", accountId).query(Long.class).single();
        return limits + rates > 0;
    }
    /** Returns the newest lifecycle control to gate closure approval. */
    public Optional<Map<String, Object>> latestLifecycleControl(long accountId) {
        return db.sql("SELECT * FROM M04_ACCOUNT_CONTROL_SYNC WHERE ACCOUNT_ID=:a AND CONTROL_TYPE='LIFECYCLE' ORDER BY CONTROL_VERSION DESC FETCH FIRST 1 ROWS ONLY")
                .param("a", accountId).query().listOfRows().stream().findFirst();
    }
    /** Inserts a versioned request that Module 5 must acknowledge. */
    public long insertControl(long accountId, Long restrictionId, String requestId,
                              String action, String type, BigDecimal amount, String eventId) {
        long pending = db.sql("SELECT COUNT(*) FROM M04_ACCOUNT_CONTROL_SYNC WHERE ACCOUNT_ID=:a AND SYNC_STATUS='PENDING'")
                .param("a", accountId).query(Long.class).single();
        if (pending > 0) throw new ApiException(HttpStatus.CONFLICT, "CONTROL_PENDING",
                "Wait for the prior Module 5 control acknowledgement");
        long version = db.sql("SELECT FINANCIAL_CONTROL_VERSION FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=:a")
                .param("a", accountId).query(Long.class).single() + 1;
        db.sql("UPDATE M04_BANK_ACCOUNT SET FINANCIAL_CONTROL_VERSION=:v,ROW_VERSION=ROW_VERSION+1,UPDATED_AT=SYSTIMESTAMP WHERE ACCOUNT_ID=:a")
                .param("v", version).param("a", accountId).update();
        db.sql("INSERT INTO M04_ACCOUNT_CONTROL_SYNC (ACCOUNT_ID,RESTRICTION_ID,REQUEST_ID,CONTROL_VERSION,TARGET_ACTION,CONTROL_TYPE,CONTROL_AMOUNT,SOURCE_EVENT_ID) VALUES (:a,:r,:q,:v,:action,:type,:amount,:event)")
                .param("a", accountId).param("r", restrictionId).param("q", requestId).param("v", version)
                .param("action", action).param("type", type).param("amount", amount)
                .param("event", rawUuid(eventId)).update();
        return version;
    }
    /** Locks the pending control that matches an acknowledgement. */
    public Optional<Map<String, Object>> controlForAck(long accountId, long version) {
        return db.sql("SELECT c.*, RAWTOHEX(c.SOURCE_EVENT_ID) AS SOURCE_EVENT_HEX FROM M04_ACCOUNT_CONTROL_SYNC c WHERE ACCOUNT_ID=:a AND CONTROL_VERSION=:v FOR UPDATE")
                .param("a", accountId).param("v", version).query().listOfRows().stream().findFirst();
    }
    /** Marks a control acknowledged or failed, using the fields required by its SQL check. */
    public void finishControl(long accountId, long version, ControlAck ack) {
        boolean success = "ACKNOWLEDGED".equals(ack.outcome());
        db.sql("UPDATE M04_ACCOUNT_CONTROL_SYNC SET SYNC_STATUS=:s,MODULE5_ACK_EVENT_ID=:e,MODULE5_FENCE_VERSION=:f,ACKNOWLEDGED_AT=SYSTIMESTAMP,FAILURE_CODE=:error WHERE ACCOUNT_ID=:a AND CONTROL_VERSION=:v")
                .param("s", success ? "ACKNOWLEDGED" : "FAILED")
                .param("e", success ? rawUuid(ack.eventId()) : null).param("f", ack.fenceVersion())
                .param("error", success ? null : ack.failureCode()).param("a", accountId)
                .param("v", version).update();
    }
    /** Sets the restriction's final state after the corresponding control result. */
    public void finishRestriction(long id, boolean applying, boolean success, String actor, String failure) {
        String status = success ? (applying ? "ACTIVE" : "REMOVED") : (applying ? "FAILED" : "ACTIVE");
        db.sql("UPDATE M04_ACCOUNT_RESTRICTION SET RESTRICTION_STATUS=:s,EFFECTIVE_AT=CASE WHEN :s='ACTIVE' AND EFFECTIVE_AT IS NULL THEN SYSTIMESTAMP ELSE EFFECTIVE_AT END,REMOVED_AT=CASE WHEN :s='REMOVED' THEN SYSTIMESTAMP ELSE NULL END,REMOVED_BY_USER_ID=CASE WHEN :s='REMOVED' THEN :u ELSE NULL END,FAILURE_CODE=:f WHERE RESTRICTION_ID=:r")
                .param("s", status).param("u", actor).param("f", success ? null : applying ? failure : null)
                .param("r", id).update();
    }
    /** Derives an active account's display status from all acknowledged restrictions. */
    public String activeDisplayStatus(long accountId) {
        List<String> types = db.sql("SELECT RESTRICTION_TYPE FROM M04_ACCOUNT_RESTRICTION WHERE ACCOUNT_ID=:a AND RESTRICTION_STATUS IN ('ACTIVE','PENDING_REMOVE')")
                .param("a", accountId).query(String.class).list();
        if (types.contains("FREEZE")) return "FROZEN";
        if (types.contains("DEBIT_BLOCK")) return "DEBIT_BLOCKED";
        if (types.contains("CREDIT_BLOCK")) return "CREDIT_BLOCKED";
        return "ACTIVE";
    }
    /** Saves a custom limit after external product and IAM policy checks. */
    public void insertLimit(long id, LimitCommand c, String actor) {
        db.sql("INSERT INTO M04_ACCOUNT_LIMIT (ACCOUNT_ID,CHANGE_REQUEST_ID,LIMIT_TYPE,OPERATION_CODE,CHANNEL_CODE,PERIOD_CODE,RESET_RULE_CODE,TIME_ZONE_ID,LIMIT_AMOUNT,PRODUCT_LIMIT_RULE_ID,OVERRIDE_POLICY_ID,EFFECTIVE_FROM,EFFECTIVE_TO,APPROVED_BY_USER_ID,CREATED_BY_USER_ID) VALUES (:a,:q,:t,:o,:c,:p,:reset,:tz,:amount,:rule,:policy,:fromDate,:toDate,:approver,:u)")
                .param("a", id).param("q", c.requestId()).param("t", c.limitType()).param("o", c.operationCode())
                .param("c", c.channelCode()).param("p", c.periodCode()).param("reset", c.resetRuleCode())
                .param("tz", c.timeZoneId()).param("amount", c.amount()).param("rule", c.productLimitRuleId())
                .param("policy", c.overridePolicyId()).param("fromDate", java.sql.Date.valueOf(c.effectiveFrom()))
                .param("toDate", c.effectiveTo() == null ? null : java.sql.Date.valueOf(c.effectiveTo()))
                .param("approver", c.approvedByUserId()).param("u", actor).update();
    }
    /** Prevents two active limits for the same operation/channel/period window. */
    public boolean limitOverlap(long id, LimitCommand c) {
        String sql = "SELECT COUNT(*) FROM M04_ACCOUNT_LIMIT WHERE ACCOUNT_ID=:a AND IS_ACTIVE='Y' AND LIMIT_TYPE=:type AND OPERATION_CODE=:op AND PERIOD_CODE=:period AND (CHANNEL_CODE=:channel OR (CHANNEL_CODE IS NULL AND :channel IS NULL)) AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>:startDate)";
        if (c.effectiveTo() != null) sql += " AND EFFECTIVE_FROM<:endDate";
        var query = db.sql(sql)
                .param("a", id).param("type", c.limitType()).param("op", c.operationCode())
                .param("period", c.periodCode()).param("channel", c.channelCode())
                .param("startDate", java.sql.Date.valueOf(c.effectiveFrom()));
        if (c.effectiveTo() != null) query = query.param("endDate", java.sql.Date.valueOf(c.effectiveTo()));
        long count = query.query(Long.class).single();
        return count > 0;
    }
    /** Saves an approved, time-bounded interest rate exception. */
    public void insertInterest(long id, long productVersion, InterestCommand c, String actor) {
        db.sql("INSERT INTO M04_ACCOUNT_INTEREST_OVERRIDE (ACCOUNT_ID,CHANGE_REQUEST_ID,PRODUCT_VERSION_ID,OVERRIDE_POLICY_ID,RATE_PCT,REASON,EFFECTIVE_FROM_AT,EFFECTIVE_TO_AT,APPROVED_BY_USER_ID,CREATED_BY_USER_ID) VALUES (:a,:q,:v,:p,:r,:reason,:start,:end,:approver,:u)")
                .param("a", id).param("q", c.requestId()).param("v", productVersion)
                .param("p", c.overridePolicyId()).param("r", c.ratePct()).param("reason", c.reason())
                .param("start", c.effectiveFrom()).param("end", c.effectiveTo())
                .param("approver", c.approvedByUserId()).param("u", actor).update();
    }
    /** Detects overlapping account-specific interest windows under the account row lock. */
    public boolean interestOverlap(long id, InterestCommand c) {
        String sql = "SELECT COUNT(*) FROM M04_ACCOUNT_INTEREST_OVERRIDE WHERE ACCOUNT_ID=:a AND (EFFECTIVE_TO_AT IS NULL OR EFFECTIVE_TO_AT>:startAt)";
        if (c.effectiveTo() != null) sql += " AND EFFECTIVE_FROM_AT<:endAt";
        var query = db.sql(sql).param("a", id).param("startAt", c.effectiveFrom());
        if (c.effectiveTo() != null) query = query.param("endAt", c.effectiveTo());
        long count = query.query(Long.class).single();
        return count > 0;
    }
    /** Atomically records product version adoption and updates the pinned version. */
    public void adopt(long id, long fromVersion, VersionCommand c, String actor, String correlation) {
        db.sql("INSERT INTO M04_ACCOUNT_PRODUCT_VERSION_HISTORY (ACCOUNT_ID,FROM_PRODUCT_VERSION_ID,TO_PRODUCT_VERSION_ID,VERSION_TREATMENT_ID,TREATMENT_EVENT_ID,CONSENT_REFERENCE,REASON_CODE,ADOPTED_BY_USER_ID,CORRELATION_ID) VALUES (:a,:old,:new,:t,:event,:consent,:reason,:u,:corr)")
                .param("a", id).param("old", fromVersion).param("new", c.targetVersionId())
                .param("t", c.treatmentId()).param("event", c.treatmentEventId())
                .param("consent", c.consentReference()).param("reason", c.reasonCode())
                .param("u", actor).param("corr", correlation).update();
        db.sql("UPDATE M04_BANK_ACCOUNT SET PRODUCT_VERSION_ID=:v,UPDATED_BY_USER_ID=:u,UPDATED_AT=SYSTIMESTAMP,ROW_VERSION=ROW_VERSION+1 WHERE ACCOUNT_ID=:a")
                .param("v", c.targetVersionId()).param("u", actor).param("a", id).update();
    }
    /** Creates a pending closure request, preserving the prior lifecycle state. */
    public void requestClosure(long id, ReasonCommand c, String previous, String actor, String correlation) {
        db.sql("INSERT INTO M04_ACCOUNT_CLOSURE_REQUEST (ACCOUNT_ID,REQUEST_ID,CLOSURE_REASON_CODE,CLOSURE_REMARKS,PRE_CLOSURE_LIFECYCLE_STATUS,REQUESTED_BY_USER_ID,CORRELATION_ID) VALUES (:a,:q,:r,:m,:p,:u,:corr)")
                .param("a", id).param("q", c.requestId()).param("r", c.reasonCode())
                .param("m", c.remarks()).param("p", previous).param("u", actor)
                .param("corr", correlation).update();
    }
    /** Locks a closure case before approval or rejection. */
    public Map<String, Object> closure(long id, String requestId) {
        return db.sql("SELECT * FROM M04_ACCOUNT_CLOSURE_REQUEST WHERE ACCOUNT_ID=:a AND REQUEST_ID=:r FOR UPDATE")
                .param("a", id).param("r", requestId).query().singleRow();
    }
    /** Finds an existing closure request before attempting a duplicate insert. */
    public Optional<Map<String, Object>> closureByRequest(String requestId) {
        return db.sql("SELECT * FROM M04_ACCOUNT_CLOSURE_REQUEST WHERE REQUEST_ID=:r")
                .param("r", requestId).query().listOfRows().stream().findFirst();
    }
    /** Completes a closure case with the required clearance evidence. */
    public void decideClosure(long id, String requestId, String status, String actor,
                              String clearance, long rowVersion, String rejection) {
        db.sql("UPDATE M04_ACCOUNT_CLOSURE_REQUEST SET REQUEST_STATUS=:s,DECIDED_BY_USER_ID=:u,DECIDED_AT=SYSTIMESTAMP,SETTLEMENT_CLEARANCE_REF=:c,SETTLEMENT_CHECKED_AT=CASE WHEN :c IS NOT NULL THEN SYSTIMESTAMP ELSE NULL END,CLEARED_ACCOUNT_ROW_VERSION=:v,REJECTION_REASON=:r WHERE ACCOUNT_ID=:a AND REQUEST_ID=:q")
                .param("s", status).param("u", actor).param("c", clearance)
                .param("v", clearance == null ? null : rowVersion).param("r", rejection)
                .param("a", id).param("q", requestId).update();
    }
    /** Creates the review workflow that follows a minor reaching majority. */
    public void startMajorityReview(long id, String requestId, OffsetDateTime due) {
        db.sql("INSERT INTO M04_ACCOUNT_MAJORITY_REVIEW (ACCOUNT_ID,REVIEW_REQUEST_ID,DUE_AT) VALUES (:a,:r,:d)")
                .param("a", id).param("r", requestId).param("d", due).update();
        db.sql("UPDATE M04_BANK_ACCOUNT SET MAJORITY_REVIEW_STATUS='PENDING',ROW_VERSION=ROW_VERSION+1 WHERE ACCOUNT_ID=:a")
                .param("a", id).update();
    }
    /** Completes or escalates a review, preserving reviewer and reason. */
    public int decideMajority(long id, String requestId, String status, String actor, String reason) {
        return db.sql("UPDATE M04_ACCOUNT_MAJORITY_REVIEW SET REVIEW_STATUS=:s,DECIDED_BY_USER_ID=:u,DECIDED_AT=SYSTIMESTAMP,DECISION_REASON=:r WHERE ACCOUNT_ID=:a AND REVIEW_REQUEST_ID=:q AND REVIEW_STATUS='PENDING'")
                .param("s", status).param("u", actor).param("r", reason)
                .param("a", id).param("q", requestId).update();
    }
}
