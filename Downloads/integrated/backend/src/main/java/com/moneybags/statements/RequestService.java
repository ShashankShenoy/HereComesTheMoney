package com.moneybags.statements;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.statements.ApiModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns idempotent statement requests and their state transitions. */
@Service
public class RequestService {
    private static final Set<String> TYPES=Set.of("CURRENT","PERIODIC","AD_HOC","REISSUE","CORRECTED","STAFF_ASSISTED","LEGALLY_REQUIRED");
    private static final Set<String> FORMATS=Set.of("PDF","CSV","HTML");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuthorizationService auth;
    private final AuditService audits;
    private final OutboxService outbox;
    public RequestService(JdbcTemplate jdbc,ObjectMapper mapper,AuthorizationService auth,AuditService audits,OutboxService outbox) {
        this.jdbc=jdbc; this.mapper=mapper; this.auth=auth; this.audits=audits; this.outbox=outbox;
    }
    public record Row(String id,Long accountId,String cif,String type,LocalDate from,LocalDate through,
        String locale,String format,String requesterType,String requesterId,String channel,String purpose,
        String caseRef,String correlation,String status,String failure) {}
    /** Validates, authorizes, and inserts a request or returns an exact idempotent replay. */
    @Transactional
    public RequestView create(Actor actor,String key,CreateRequest body) {
        if (key==null || key.isBlank() || key.length()>160) throw bad("IDEMPOTENCY_REQUIRED");
        if (!TYPES.contains(body.statementType()) || !FORMATS.contains(body.format()) ||
            body.periodEnd().isBefore(body.periodStart()) || body.accountId()<=0 ||
            (actor.type().equals("STAFF") && (body.purposeCode()==null || body.purposeCode().isBlank())) ||
            (Set.of("STAFF_ASSISTED","LEGALLY_REQUIRED").contains(body.statementType()) &&
             (body.caseReference()==null || body.caseReference().isBlank()))) throw bad("INVALID_REQUEST");
        boolean revision=Set.of("REISSUE","CORRECTED").contains(body.statementType());
        if(revision != (body.priorStatementId()!=null && body.correctionReasonCode()!=null &&
                        !body.correctionReasonCode().isBlank()))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"REVISION_CONTEXT_REQUIRED",
                "Prior statement and reason are required only for reissues and corrections");
        if(revision && (body.priorStatementId().length()>36 || body.correctionReasonCode().length()>80))
            throw bad("INVALID_REVISION_CONTEXT");
        String hash;
        try { hash=Hashing.sha256(mapper.writeValueAsString(body)); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        List<Map<String,Object>> existing=jdbc.queryForList("""
            SELECT REQUEST_ID,REQUEST_PAYLOAD_SHA256 FROM M10_STATEMENT_REQUEST
            WHERE REQUESTER_TYPE=? AND REQUESTER_ID=? AND IDEMPOTENCY_KEY=?
            """,actor.type(),actor.id(),key);
        if (!existing.isEmpty() && !hash.equalsIgnoreCase((String)existing.get(0).get("REQUEST_PAYLOAD_SHA256"))) {
            audits.record("REQUEST",actor,body.accountId(),null,(String)existing.get(0).get("REQUEST_ID"),
                body.purposeCode(),body.caseReference(),null,"DENIED","IDEMPOTENCY_CONFLICT",body.correlationId());
            throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","Key already used with a different payload");
        }
        String priorId=existing.isEmpty()?null:(String)existing.get(0).get("REQUEST_ID");
        String ref=auth.require("REQUEST","REQUEST",actor,body.accountId(),body.customerCifId(),null,priorId,
            body.purposeCode(),body.caseReference(),body.correlationId(),actor.id(),actor.type());
        if (priorId!=null) return get(priorId);
        if(revision) {
            List<Map<String,Object>> scope=jdbc.queryForList("""
                SELECT ACCOUNT_ID,REVISION_NO FROM M10_STATEMENT_SNAPSHOT
                WHERE STATEMENT_ID=? AND STATE='ISSUED' AND PERIOD_START_DATE=? AND PERIOD_END_DATE=?
                """,body.priorStatementId(),body.periodStart(),body.periodEnd());
            if(scope.isEmpty() || ((Number)scope.get(0).get("ACCOUNT_ID")).longValue()!=body.accountId())
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_PRIOR_STATEMENT",
                    "Prior issued statement must cover the same account and period");
            Integer latest=jdbc.queryForObject("""
                SELECT NVL(MAX(REVISION_NO),0) FROM M10_STATEMENT_SNAPSHOT
                WHERE ACCOUNT_ID=? AND PERIOD_START_DATE=? AND PERIOD_END_DATE=? AND STATE='ISSUED'
                """,Integer.class,body.accountId(),body.periodStart(),body.periodEnd());
            if(((Number)scope.get(0).get("REVISION_NO")).intValue()!=latest)
                throw new ApiException(HttpStatus.CONFLICT,"PRIOR_NOT_LATEST","Use the latest issued revision");
        }
        if(!revision && !jdbc.queryForList("""
            SELECT STATEMENT_ID FROM M10_STATEMENT_SNAPSHOT
            WHERE ACCOUNT_ID=? AND STATEMENT_TYPE=? AND PERIOD_START_DATE=? AND PERIOD_END_DATE=?
              AND REVISION_NO=1 AND STATE='ISSUED'
            """,String.class,body.accountId(),body.statementType(),body.periodStart(),body.periodEnd()).isEmpty())
            throw new ApiException(HttpStatus.CONFLICT,"USE_REISSUE","A statement already exists; request a reissue");
        String id=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO M10_STATEMENT_REQUEST
             (REQUEST_ID,IDEMPOTENCY_KEY,REQUEST_PAYLOAD_SHA256,ACCOUNT_ID,CUSTOMER_CIF_ID,
              STATEMENT_TYPE,PERIOD_START_DATE,PERIOD_END_DATE,REQUESTED_LOCALE,REQUESTED_FORMAT,
              REQUESTER_TYPE,REQUESTER_ID,REQUEST_CHANNEL,PURPOSE_CODE,CASE_REFERENCE,
              AUTHORIZATION_REF,CORRELATION_ID,STATUS)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'AUTHORIZED')
            """,id,key,hash,body.accountId(),body.customerCifId(),body.statementType(),body.periodStart(),
            body.periodEnd(),body.locale(),body.format(),actor.type(),actor.id(),body.channel(),
            body.purposeCode(),body.caseReference(),ref,body.correlationId());
        if(revision) jdbc.update("""
            INSERT INTO M10_STATEMENT_REVISION_INTENT
              (REQUEST_ID,PRIOR_STATEMENT_ID,CORRECTION_REASON_CODE) VALUES (?,?,?)
            """,id,body.priorStatementId(),body.correctionReasonCode());
        outbox.emit("STATEMENT_REQUEST",id,"STATEMENT_REQUESTED","request:"+id,body.correlationId(),
            Map.of("requestId",id,"accountId",body.accountId()));
        return get(id);
    }
    /** Reads a request by identifier for internal use after caller authorization. */
    public RequestView get(String id) {
        Row r=row(id);
        List<String> statements=jdbc.queryForList("SELECT STATEMENT_ID FROM M10_STATEMENT_SNAPSHOT WHERE REQUEST_ID=? ORDER BY CREATED_AT DESC",String.class,id);
        return new RequestView(r.id(),r.accountId(),r.status(),r.type(),r.from(),r.through(),r.format(),r.failure(),
            statements.isEmpty()?null:statements.get(0));
    }
    /** Loads all fields needed for a fresh authorization or issuance decision. */
    public Row row(String id) {
        List<Row> rows=jdbc.query("""
            SELECT REQUEST_ID,ACCOUNT_ID,CUSTOMER_CIF_ID,STATEMENT_TYPE,PERIOD_START_DATE,
              PERIOD_END_DATE,REQUESTED_LOCALE,REQUESTED_FORMAT,REQUESTER_TYPE,REQUESTER_ID,
              REQUEST_CHANNEL,PURPOSE_CODE,CASE_REFERENCE,CORRELATION_ID,STATUS,FAILURE_CODE
            FROM M10_STATEMENT_REQUEST WHERE REQUEST_ID=?
            """,(rs,n)->map(rs),id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"REQUEST_NOT_FOUND","Request not found");
        return rows.get(0);
    }
    /** Lists one account's recent requests without exposing another account's records. */
    public List<RequestView> list(long accountId,String requesterId,int limit) {
        return jdbc.query("""
            SELECT REQUEST_ID,ACCOUNT_ID,STATUS,STATEMENT_TYPE,PERIOD_START_DATE,PERIOD_END_DATE,
                   REQUESTED_FORMAT,FAILURE_CODE
            FROM M10_STATEMENT_REQUEST WHERE ACCOUNT_ID=? AND REQUESTER_ID=? ORDER BY REQUESTED_AT DESC
            FETCH FIRST ? ROWS ONLY
            """,(rs,n)->new RequestView(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),
                rs.getDate(5).toLocalDate(),rs.getDate(6).toLocalDate(),rs.getString(7),rs.getString(8),null),
            accountId,requesterId,Math.min(Math.max(limit,1),100));
    }
    /** Cancels only a request that has not started processing. */
    @Transactional public RequestView cancel(String id) {
        int changed=jdbc.update("""
            UPDATE M10_STATEMENT_REQUEST SET STATUS='CANCELLED',VERSION_NO=VERSION_NO+1,
                   STARTED_AT=SYSTIMESTAMP,COMPLETED_AT=SYSTIMESTAMP
            WHERE REQUEST_ID=? AND STATUS IN ('RECEIVED','AUTHORIZED')
            """,id);
        if(changed==0) throw new ApiException(HttpStatus.CONFLICT,"NOT_CANCELLABLE","Request cannot be cancelled");
        outbox.emit("STATEMENT_REQUEST",id,"STATEMENT_CANCELLED","cancel:"+id,null,Map.of("requestId",id));
        return get(id);
    }
    /** Maps the request row without relying on mutable account projections. */
    private Row map(ResultSet rs) throws SQLException {
        return new Row(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),
            rs.getDate(5)==null?null:rs.getDate(5).toLocalDate(),
            rs.getDate(6)==null?null:rs.getDate(6).toLocalDate(),rs.getString(7),rs.getString(8),
            rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13),
            rs.getString(14),rs.getString(15),rs.getString(16));
    }
    /** Creates a safe validation response. */
    private ApiException bad(String code) { return new ApiException(HttpStatus.BAD_REQUEST,code,"Invalid request"); }
}
