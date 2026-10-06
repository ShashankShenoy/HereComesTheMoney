package com.moneybags.txn.core;

import com.moneybags.txn.api.ApiException;
import com.moneybags.txn.api.OperationsContracts.*;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Small controlled setup surface for the Module 5 chart, fees, and period closing. */
@Service
public class OperationsService {
    private final JdbcTemplate db;

    /** Injects JDBC for Oracle's preinstalled Module 5 tables. */
    public OperationsService(JdbcTemplate db) { this.db = db; }

    /** Adds one INR GL account after validating its class and normal side. */
    @Transactional
    public Map<String, Object> createGl(GlAccountRequest request) {
        if (!List.of("ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE").contains(request.accountClass()) ||
                !List.of("DR", "CR").contains(request.normalSide()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "GL_CLASS", "Invalid GL account class or normal side");
        long id = insertId("INSERT INTO M05_GL_ACCOUNT(GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE) VALUES (?,?,?,?)", "GL_ACCOUNT_ID",
                request.code(), request.name(), request.accountClass(), request.normalSide());
        return Map.of("glAccountId", id, "code", request.code());
    }

    /** Maps one product version and posting role to an active INR GL account. */
    @Transactional
    public Map<String, Object> createMapping(MappingRequest request, String actor) {
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "EFFECTIVE_DATES", "Effective end precedes start");
        List<Long> product = db.query("SELECT PRODUCT_VERSION_ID FROM M03_PM_PRODUCT_VERSION WHERE PRODUCT_VERSION_ID=? FOR UPDATE", (rs, n) -> rs.getLong(1), request.productVersionId());
        if (product.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_VERSION_MISSING", "Product version does not exist");
        List<Map<String, Object>> gl = db.queryForList("SELECT ACCOUNT_CLASS,ACTIVE_FLAG FROM M05_GL_ACCOUNT WHERE GL_ACCOUNT_ID=?", request.glAccountId());
        if (gl.isEmpty() || !"Y".equals(gl.get(0).get("ACTIVE_FLAG")))
            throw new ApiException(HttpStatus.CONFLICT, "GL_INACTIVE", "GL account is missing or inactive");
        if ("CUSTOMER_LIABILITY".equals(request.roleCode()) && !"LIABILITY".equals(gl.get(0).get("ACCOUNT_CLASS")))
            throw new ApiException(HttpStatus.BAD_REQUEST, "GL_CLASS", "Customer liability role requires a liability GL");
        Integer overlaps = db.queryForObject("SELECT COUNT(*) FROM M05_GL_PRODUCT_MAPPING WHERE PRODUCT_VERSION_ID=? AND POSTING_TYPE=? AND GL_ROLE_CODE=? AND STATUS='ACTIVE' AND EFFECTIVE_FROM<=NVL(?,DATE '9999-12-31') AND NVL(EFFECTIVE_TO,DATE '9999-12-31')>=?",
                Integer.class, request.productVersionId(), request.postingType(), request.roleCode(), request.effectiveTo(), request.effectiveFrom());
        if (overlaps != null && overlaps > 0) throw new ApiException(HttpStatus.CONFLICT, "MAPPING_OVERLAP", "Active mapping effective dates overlap");
        long id = insertId("INSERT INTO M05_GL_PRODUCT_MAPPING(PRODUCT_VERSION_ID,POSTING_TYPE,GL_ROLE_CODE,GL_ACCOUNT_ID,EFFECTIVE_FROM,EFFECTIVE_TO,CREATED_BY) VALUES (?,?,?,?,?,?,?)", "MAPPING_ID",
                request.productVersionId(), request.postingType(), request.roleCode(), request.glAccountId(), request.effectiveFrom(), request.effectiveTo(), actor);
        return Map.of("mappingId", id, "status", "ACTIVE");
    }

    /** Requests a period close and records its maker for independent review. */
    @Transactional
    public Map<String, Object> requestClose(CloseRequest request, String maker) {
        List<Map<String, Object>> old = db.queryForList("SELECT CLOSE_ID,STATUS FROM M05_GL_PERIOD_CLOSE WHERE REQUEST_KEY=?", request.requestKey());
        if (!old.isEmpty()) return old.get(0);
        long id = insertId("INSERT INTO M05_GL_PERIOD_CLOSE(REQUEST_KEY,CLOSED_THROUGH_DATE,MAKER_USER_ID,REASON_TEXT) VALUES (?,?,?,?)", "CLOSE_ID",
                request.requestKey(), request.closedThroughDate(), maker, request.reasonText());
        return Map.of("closeId", id, "status", "PENDING");
    }

    /** Approves a close only after both Oracle reconciliation views are clear. */
    @Transactional
    public Map<String, Object> decideClose(long id, CloseDecision decision, String checker) {
        List<Map<String, Object>> rows = db.queryForList("SELECT MAKER_USER_ID,STATUS,CLOSED_THROUGH_DATE FROM M05_GL_PERIOD_CLOSE WHERE CLOSE_ID=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "CLOSE_NOT_FOUND", "Period close request does not exist");
        Map<String, Object> close = rows.get(0);
        if (!"PENDING".equals(close.get("STATUS"))) throw new ApiException(HttpStatus.CONFLICT, "CLOSE_DECIDED", "Period close is already decided");
        if (checker.equals(close.get("MAKER_USER_ID"))) throw new ApiException(HttpStatus.FORBIDDEN, "MAKER_CHECKER", "Maker cannot decide their own period close");
        if (!List.of("APPROVED", "REJECTED").contains(decision.decision())) throw new ApiException(HttpStatus.BAD_REQUEST, "DECISION", "Decision must be APPROVED or REJECTED");
        if ("APPROVED".equals(decision.decision())) {
            Integer badJournals = db.queryForObject("SELECT COUNT(*) FROM M05_V_GL_JOURNAL_CONTROL WHERE IS_BALANCED='N'", Integer.class);
            Integer badPositions = db.queryForObject("SELECT COUNT(*) FROM M05_V_ACCOUNT_POSITION_RECON WHERE IS_MATCHED='N'", Integer.class);
            if ((badJournals != null && badJournals > 0) || (badPositions != null && badPositions > 0))
                throw new ApiException(HttpStatus.CONFLICT, "RECONCILIATION_OPEN", "Reconciliation exceptions must be resolved before close");
        }
        db.update("UPDATE M05_GL_PERIOD_CLOSE SET STATUS=?,CHECKER_USER_ID=?,DECIDED_AT=SYSTIMESTAMP WHERE CLOSE_ID=?", decision.decision(), checker, id);
        return Map.of("closeId", id, "status", decision.decision());
    }

    /** Creates an assessed fee instance without silently posting a financial effect. */
    @Transactional
    public Map<String, Object> assessFee(FeeRequest request) {
        if ((request.bankAccountId() == null) == (request.loanFacilityId() == null) ||
                request.assessedAmount().scale() > 2 || request.assessedAmount().precision() > 18)
            throw new ApiException(HttpStatus.BAD_REQUEST, "FEE_SCOPE", "Fee needs exactly one account or loan and a valid INR amount");
        List<Map<String, Object>> old = db.queryForList("SELECT * FROM M05_FEE_ASSESSMENT WHERE FEE_KEY=?", request.feeKey());
        if (!old.isEmpty()) {
            if (((BigDecimal) old.get(0).get("ASSESSED_AMOUNT")).compareTo(request.assessedAmount()) != 0 || !java.util.Objects.equals(asLong(old.get(0).get("BANK_ACCOUNT_ID")),request.bankAccountId()) || !java.util.Objects.equals(asLong(old.get(0).get("LOAN_FACILITY_ID")),request.loanFacilityId()) || asLong(old.get(0).get("PRODUCT_FEE_RULE_ID")).longValue()!=request.productFeeRuleId() || !com.moneybags.common.database.BusinessRepository.localDate(old.get(0).get("DUE_DATE")).equals(request.dueDate()))
                throw new ApiException(HttpStatus.CONFLICT, "FEE_KEY_CONFLICT", "Fee key was reused for another amount");
            return Map.of("feeAssessmentId", old.get(0).get("FEE_ASSESSMENT_ID"), "status", old.get(0).get("STATUS"));
        }
        Long version=db.queryForObject(request.bankAccountId()!=null?"SELECT PRODUCT_VERSION_ID FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?":"SELECT PRODUCT_VERSION_ID FROM M08_LOAN_FACILITY WHERE FACILITY_ID=?",Long.class,request.bankAccountId()!=null?request.bankAccountId():request.loanFacilityId());
        var rules=db.queryForList("SELECT CHARGE_BASIS,FIXED_AMOUNT,TAX_CODE FROM M03_PM_FEE_RULE WHERE FEE_RULE_ID=? AND PRODUCT_VERSION_ID=? AND (EFFECTIVE_FROM_AT IS NULL OR EFFECTIVE_FROM_AT<=SYSTIMESTAMP) AND (EFFECTIVE_TO_AT IS NULL OR EFFECTIVE_TO_AT>SYSTIMESTAMP)",request.productFeeRuleId(),version);
        if(rules.size()!=1)throw new ApiException(HttpStatus.CONFLICT,"FEE_RULE_MISSING","An effective fee rule for this account product is required");
        var rule=rules.get(0);
        if(!"FIXED".equals(rule.get("CHARGE_BASIS"))||rule.get("TAX_CODE")!=null||!(rule.get("FIXED_AMOUNT") instanceof BigDecimal fixed)||fixed.compareTo(request.assessedAmount())!=0)throw new ApiException(HttpStatus.CONFLICT,"FEE_RULE_UNSUPPORTED","Assessment requires the exact untaxed fixed product fee; percentage and tax adapters must be configured separately");
        long id = insertId("INSERT INTO M05_FEE_ASSESSMENT(FEE_KEY,BANK_ACCOUNT_ID,LOAN_FACILITY_ID,PRODUCT_FEE_RULE_ID,ASSESSED_AMOUNT,DUE_DATE) VALUES (?,?,?,?,?,?)", "FEE_ASSESSMENT_ID",
                request.feeKey(), request.bankAccountId(), request.loanFacilityId(), request.productFeeRuleId(), request.assessedAmount(), request.dueDate());
        return Map.of("feeAssessmentId", id, "status", "ASSESSED");
    }

    private static Long asLong(Object v){return v instanceof Number n?n.longValue():null;}
    /** Uses Oracle identity values for administration records. */
    private long insertId(String sql, String column, Object... values) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{column});
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i] instanceof LocalDate d ? java.sql.Date.valueOf(d) : values[i]);
            return ps;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("Oracle did not return " + column);
        return id.longValue();
    }
}
