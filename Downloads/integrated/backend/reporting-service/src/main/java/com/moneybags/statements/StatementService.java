package com.moneybags.statements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.statements.ApiModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Builds immutable statements from Module 5 postings and reads issued revisions. */
@Service
public class StatementService {
    private final JdbcTemplate jdbc;
    private final LedgerReader ledger;
    private final RequestService requests;
    private final AuthorizationService auth;
    private final OutboxService outbox;
    private final ZoneId zone;
    private final int retentionDays;
    private final ObjectMapper mapper;
    public StatementService(JdbcTemplate jdbc,LedgerReader ledger,RequestService requests,
        AuthorizationService auth,OutboxService outbox,ObjectMapper mapper,@Value("${moneybags.business-zone}") String zone,
        @Value("${moneybags.retention-days}") int retentionDays) {
        this.jdbc=jdbc;this.ledger=ledger;this.requests=requests;this.auth=auth;this.outbox=outbox;
        this.mapper=mapper;this.zone=ZoneId.of(zone);this.retentionDays=retentionDays;
    }
    private record Policy(String id,int version) {}
    private record Narration(String id,String code,int version,String rendered) {}
    private record Snapshot(String id,String number,long accountId,String cif,String state,int revision,
        LocalDate from,LocalDate through,BigDecimal opening,BigDecimal closing,String currency,String locale,
        String requestId,String type,String priorId,String audience,String rulesJson) {}
    /** Locks a request, rechecks authorization, and commits cut, lines, snapshot and event atomically. */
    @Transactional(isolation=Isolation.SERIALIZABLE)
    public RequestView process(String id,Actor worker) {
        // Authorization below checks the current requester or the explicit issue permission.
        jdbc.queryForList("SELECT REQUEST_ID FROM M10_STATEMENT_REQUEST WHERE REQUEST_ID=? FOR UPDATE",id);
        RequestService.Row r=requests.row(id);
        if(r.status().equals("COMPLETED")) return requests.get(id);
        if(!r.status().equals("AUTHORIZED")) throw new ApiException(HttpStatus.CONFLICT,"NOT_PROCESSABLE","Request is not authorized");
        if(r.from()==null || r.through()==null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"PERIOD_REQUIRED","Statement period required");
        auth.require("ISSUE","ISSUE",worker,r.accountId(),r.cif(),null,id,r.purpose(),r.caseRef(),
            r.correlation(),r.requesterId(),r.requesterType());
        String audience=r.requesterType().equals("STAFF")?"STAFF":r.requesterType().equals("REGULATOR")?"REGULATOR":"CUSTOMER";
        Policy policy=policy(r.channel(),audience);
        String locale=r.locale()==null?"en-IN":r.locale();
        OffsetDateTime now=OffsetDateTime.now(zone);
        LedgerReader.Cut cut=ledger.cut(r.accountId(),r.from(),r.through(),now);
        if(!controlMatches(r.accountId(),r.through(),cut)) {
            quarantine(r,cut,worker,"SOURCE_MISMATCH");
            return requests.get(id);
        }
        List<Narration> narrations=new ArrayList<>();
        for(LedgerReader.Posting p:cut.postings()) narrations.add(narration(p,locale,audience,now));
        String cutId=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO M10_STATEMENT_SOURCE_CUT
             (SOURCE_CUT_ID,ACCOUNT_ID,CURRENCY_CODE,PERIOD_START_DATE,PERIOD_END_DATE,AS_OF_AT,
              COMPLETED_THROUGH_MARKER,MAX_JOURNAL_ID,MAX_POSTING_ID,SOURCE_ORDERING,
              ELIGIBILITY_POLICY_VERSION,ELIGIBILITY_RULE_SHA256,OPENING_POSTED_BALANCE,
              CLOSING_POSTED_BALANCE,SOURCE_CONTROL_TOTAL,SOURCE_TRANSACTION_COUNT,
              SOURCE_CONTROL_SHA256,CUT_STATUS,COMPLETED_AT,CREATED_BY)
            VALUES (?,?,'INR',?,?,?,?,?,?,'BOOKED_AT,JOURNAL_ID,POSTING_ID',?,?,?,?,?,?,?,'COMPLETED',?,?)
            """,cutId,r.accountId(),r.from(),r.through(),cut.asOf(),cut.marker(),cut.maxJournal(),cut.maxPosting(),
            "posted-v1",Hashing.sha256("txn-null-or-posted|id-fence|value-date"),cut.opening(),cut.closing(),
            cut.total(),cut.postings().size(),cut.controlHash(),now,worker.id());
        String statementId=UUID.randomUUID().toString();
        List<Map<String,Object>> intents=jdbc.queryForList("""
            SELECT PRIOR_STATEMENT_ID,CORRECTION_REASON_CODE FROM M10_STATEMENT_REVISION_INTENT
            WHERE REQUEST_ID=?
            """,id);
        String priorId=intents.isEmpty()?null:(String)intents.get(0).get("PRIOR_STATEMENT_ID");
        String reason=intents.isEmpty()?null:(String)intents.get(0).get("CORRECTION_REASON_CODE");
        if(Set.of("REISSUE","CORRECTED").contains(r.type()) != (priorId!=null))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"REVISION_CONTEXT_REQUIRED","Invalid revision context");
        Snapshot prior=priorId==null?null:snapshot(priorId);
        if(prior!=null && (!prior.state().equals("ISSUED") || prior.accountId()!=r.accountId() ||
            !prior.from().equals(r.from()) || !prior.through().equals(r.through())))
            throw new ApiException(HttpStatus.CONFLICT,"PRIOR_CHANGED","Prior statement is no longer eligible");
        if(prior!=null) {
            Integer latest=jdbc.queryForObject("""
                SELECT NVL(MAX(REVISION_NO),0) FROM M10_STATEMENT_SNAPSHOT
                WHERE ACCOUNT_ID=? AND PERIOD_START_DATE=? AND PERIOD_END_DATE=? AND STATE='ISSUED'
                """,Integer.class,r.accountId(),r.from(),r.through());
            if(prior.revision()!=latest)
                throw new ApiException(HttpStatus.CONFLICT,"PRIOR_NOT_LATEST","Use the latest issued revision");
        }
        int revision=prior==null?1:prior.revision()+1;
        String number="MB-"+statementId.substring(0,8).toUpperCase()+"-"+revision;
        StringBuilder canonical=new StringBuilder(cut.controlHash()+"|"+policy.id()+"|"+locale+"|"+priorId+"|"+reason);
        for(int i=0;i<cut.postings().size();i++) canonical.append('|').append(i+1).append(':').append(narrations.get(i).rendered());
        String snapshotHash=Hashing.sha256(canonical.toString());
        jdbc.update("""
            INSERT INTO M10_STATEMENT_SNAPSHOT
             (STATEMENT_ID,STATEMENT_NUMBER,REQUEST_ID,ACCOUNT_ID,CUSTOMER_CIF_ID,STATEMENT_TYPE,
              PERIOD_START_DATE,PERIOD_END_DATE,REVISION_NO,PRIOR_STATEMENT_ID,CORRECTION_REASON_CODE,
              SOURCE_CUT_ID,BUSINESS_TIMEZONE,
              CURRENCY_CODE,OPENING_POSTED_BALANCE,CLOSING_POSTED_BALANCE,SOURCE_CONTROL_TOTAL,
              SOURCE_TRANSACTION_COUNT,MASKING_PROFILE_ID,MASKING_PROFILE_VERSION,RESOLVED_LOCALE,
              CALCULATION_POLICY_VERSION,SNAPSHOT_HASH_SHA256,STATE,ISSUED_AT,IMMUTABLE_AT,
              RETENTION_UNTIL,CREATED_BY)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'INR',?,?,?,?,?,?,?,'posted-v1',?,'ISSUED',?,?,?,?)
            """,statementId,number,id,r.accountId(),r.cif(),r.type(),r.from(),r.through(),revision,priorId,reason,cutId,zone.getId(),
            cut.opening(),cut.closing(),cut.total(),cut.postings().size(),policy.id(),policy.version(),locale,
            snapshotHash,now,now,now.plusDays(retentionDays),worker.id());
        BigDecimal balance=cut.opening();
        for(int i=0;i<cut.postings().size();i++) {
            LedgerReader.Posting p=cut.postings().get(i); Narration n=narrations.get(i);
            balance=balance.add(p.signed());
            String renderedHash=Hashing.sha256((i+1)+"|"+p.journalId()+"|"+p.postingId()+"|"+p.signed()+"|"+balance+"|"+n.rendered());
            jdbc.update("""
                INSERT INTO M10_STATEMENT_LINE_ITEM
                 (STATEMENT_LINE_ID,STATEMENT_ID,LINE_NO,SOURCE_JOURNAL_ID,SOURCE_POSTING_ID,
                  SOURCE_TXN_ID,SOURCE_PAYMENT_ID,SOURCE_LOAN_FACILITY_ID,SOURCE_BOOKED_AT,
                  SOURCE_VALUE_DATE,SOURCE_ORDERING_MARKER,ENTRY_CLASS,AMOUNT_SIGNED,
                  POSTED_BALANCE_AFTER,CURRENCY_CODE,NARRATION_CATALOG_ID,NARRATION_CODE,
                  NARRATION_VERSION_NO,RENDERED_NARRATION,RENDERED_LINE_SHA256)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'INR',?,?,?,?,?)
                """,UUID.randomUUID().toString(),statementId,i+1,p.journalId(),p.postingId(),p.txnId(),
                p.paymentId(),p.loanId(),p.bookedAt(),p.valueDate(),
                p.bookedAt().toInstant()+":"+p.journalId()+":"+p.postingId(),p.entryClass(),p.signed(),balance,
                n.id(),n.code(),n.version(),n.rendered(),renderedHash);
        }
        jdbc.update("""
            UPDATE M10_STATEMENT_REQUEST SET STATUS='COMPLETED',STARTED_AT=SYSTIMESTAMP,
                   COMPLETED_AT=SYSTIMESTAMP,VERSION_NO=VERSION_NO+1
            WHERE REQUEST_ID=? AND STATUS='AUTHORIZED'
            """,id);
        outbox.emit("STATEMENT",statementId,"STATEMENT_ISSUED","issued:"+statementId,r.correlation(),
            Map.of("statementId",statementId,"requestId",id,"accountId",r.accountId(),"hash",snapshotHash));
        return requests.get(id);
    }
    /** Lists issued statement headers for an already-authorized account. */
    public List<StatementView> list(long accountId,String audience,int limit) {
        return jdbc.query("""
            SELECT s.STATEMENT_ID,s.STATEMENT_NUMBER,s.ACCOUNT_ID,s.STATE,s.REVISION_NO,
              PERIOD_START_DATE,PERIOD_END_DATE,OPENING_POSTED_BALANCE,CLOSING_POSTED_BALANCE,
              CURRENCY_CODE,RESOLVED_LOCALE,m.RULES_JSON
            FROM M10_STATEMENT_SNAPSHOT s JOIN M10_MASKING_PROFILE m
              ON m.MASKING_PROFILE_ID=s.MASKING_PROFILE_ID
            WHERE s.ACCOUNT_ID=? AND s.STATE='ISSUED' AND m.AUDIENCE_CODE=?
            ORDER BY s.ISSUED_AT DESC FETCH FIRST ? ROWS ONLY
            """,(rs,n)->new StatementView(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),
                rs.getInt(5),rs.getDate(6).toLocalDate(),rs.getDate(7).toLocalDate(),
                flag(rs.getString(12),"showBalances")?rs.getBigDecimal(8):null,
                flag(rs.getString(12),"showBalances")?rs.getBigDecimal(9):null,
                rs.getString(10),rs.getString(11),List.of()),
            accountId,audience,Math.min(Math.max(limit,1),100));
    }
    /** Reads the immutable rendered values already stored in a statement revision. */
    public StatementView get(String id) {
        Snapshot s=snapshot(id);
        if(!s.state().equals("ISSUED")) throw new ApiException(HttpStatus.CONFLICT,"NOT_ISSUED","Statement is not available");
        List<LineView> lines=jdbc.query("""
            SELECT LINE_NO,SOURCE_JOURNAL_ID,SOURCE_POSTING_ID,SOURCE_BOOKED_AT,SOURCE_VALUE_DATE,
              ENTRY_CLASS,AMOUNT_SIGNED,POSTED_BALANCE_AFTER,RENDERED_NARRATION
            FROM M10_STATEMENT_LINE_ITEM WHERE STATEMENT_ID=? ORDER BY LINE_NO
            """,(rs,n)->new LineView(rs.getInt(1),rs.getLong(2),rs.getLong(3),
                rs.getObject(4,OffsetDateTime.class),rs.getDate(5).toLocalDate(),rs.getString(6),
                rs.getBigDecimal(7),rs.getBigDecimal(8),rs.getString(9)),id);
        boolean balances=flag(s.rulesJson(),"showBalances");
        boolean refs=flag(s.rulesJson(),"showSourceReferences");
        List<LineView> masked=lines.stream().map(line->new LineView(line.lineNo(),
            refs?line.journalId():null,refs?line.postingId():null,line.bookedAt(),line.valueDate(),
            line.entryClass(),line.amount(),balances?line.balanceAfter():null,line.narration())).toList();
        return new StatementView(s.id(),s.number(),s.accountId(),s.state(),s.revision(),s.from(),s.through(),
            balances?s.opening():null,balances?s.closing():null,s.currency(),s.locale(),masked);
    }
    /** Loads a snapshot header for policy checks and delivery operations. */
    public SnapshotInfo info(String id) {
        Snapshot s=snapshot(id);
        return new SnapshotInfo(s.id(),s.accountId(),s.cif(),s.state(),s.requestId(),s.audience());
    }
    public record SnapshotInfo(String id,long accountId,String cif,String state,String requestId,String audience) {}
    /** Reads only approved boolean display flags and defaults to hiding sensitive fields. */
    private boolean flag(String json,String name) {
        try { JsonNode node=mapper.readTree(json);return node.path(name).asBoolean(false); }
        catch(Exception e) { return false; }
    }
    /** Selects an approved audience/channel policy version. */
    private Policy policy(String channel,String audience) {
        List<Policy> rows=jdbc.query("""
            SELECT MASKING_PROFILE_ID,VERSION_NO FROM M10_MASKING_PROFILE
            WHERE CHANNEL_CODE=? AND AUDIENCE_CODE=? AND STATUS='APPROVED'
            ORDER BY VERSION_NO DESC FETCH FIRST 1 ROW ONLY
            """,(rs,n)->new Policy(rs.getString(1),rs.getInt(2)),channel,audience);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"MASKING_POLICY_MISSING","Approved masking policy required");
        return rows.get(0);
    }
    /** Resolves a versioned approved narration and avoids exposing free-text ledger descriptions. */
    private Narration narration(LedgerReader.Posting p,String locale,String audience,OffsetDateTime at) {
        List<Narration> rows=jdbc.query("""
            SELECT NARRATION_CATALOG_ID,NARRATION_CODE,VERSION_NO,RENDERED_TEXT
            FROM M10_NARRATION_CATALOG WHERE NARRATION_CODE=? AND LOCALE_CODE=? AND AUDIENCE_CODE=?
              AND APPROVAL_STATUS='APPROVED' AND EFFECTIVE_FROM<=?
              AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>?)
            ORDER BY VERSION_NO DESC FETCH FIRST 1 ROW ONLY
            """,(rs,n)->new Narration(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getString(4)),
            p.entryClass(),locale,audience,at,at);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"NARRATION_MISSING","Approved narration required");
        return rows.get(0);
    }
    /** Compares current owner position only when its journal fence matches the cut. */
    private boolean controlMatches(long accountId,LocalDate through,LedgerReader.Cut cut) {
        if(through.isBefore(LocalDate.now(zone))) return true;
        List<Boolean> match=jdbc.query("""
            SELECT POSTED_BALANCE,LAST_JOURNAL_ID FROM M05_ACCOUNT_POSITION
            WHERE BANK_ACCOUNT_ID=? AND CURRENCY_CODE='INR'
            """,(rs,n)-> rs.getLong(2)!=cut.maxJournal() || rs.getBigDecimal(1).compareTo(cut.closing())==0,
            accountId);
        return !match.isEmpty() && match.get(0);
    }
    /** Keeps failed reconciliation evidence and emits a mismatch event. */
    private void quarantine(RequestService.Row r,LedgerReader.Cut cut,Actor actor,String reason) {
        String cutId=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO M10_STATEMENT_SOURCE_CUT
             (SOURCE_CUT_ID,ACCOUNT_ID,CURRENCY_CODE,PERIOD_START_DATE,PERIOD_END_DATE,AS_OF_AT,
              COMPLETED_THROUGH_MARKER,MAX_JOURNAL_ID,MAX_POSTING_ID,SOURCE_ORDERING,
              ELIGIBILITY_POLICY_VERSION,ELIGIBILITY_RULE_SHA256,OPENING_POSTED_BALANCE,
              CLOSING_POSTED_BALANCE,SOURCE_CONTROL_TOTAL,SOURCE_TRANSACTION_COUNT,
              SOURCE_CONTROL_SHA256,CUT_STATUS,MISMATCH_REASON_CODE,COMPLETED_AT,CREATED_BY)
            VALUES (?,?,'INR',?,?,?,?,?,?,'BOOKED_AT,JOURNAL_ID,POSTING_ID',?,?,?,?,?,?,?,'QUARANTINED',?,?,?)
            """,cutId,r.accountId(),r.from(),r.through(),cut.asOf(),cut.marker(),cut.maxJournal(),cut.maxPosting(),
            "posted-v1",Hashing.sha256("txn-null-or-posted|id-fence|value-date"),cut.opening(),cut.closing(),
            cut.total(),cut.postings().size(),cut.controlHash(),reason,OffsetDateTime.now(zone),actor.id());
        jdbc.update("""
            UPDATE M10_STATEMENT_REQUEST SET STATUS='QUARANTINED',FAILURE_CODE=?,
                   STARTED_AT=SYSTIMESTAMP,COMPLETED_AT=SYSTIMESTAMP,VERSION_NO=VERSION_NO+1
            WHERE REQUEST_ID=?
            """,reason,r.id());
        outbox.emit("STATEMENT_REQUEST",r.id(),"SOURCE_MISMATCH","mismatch:"+r.id(),r.correlation(),
            Map.of("requestId",r.id(),"accountId",r.accountId(),"sourceCutId",cutId));
    }
    /** Finds the immutable snapshot header or returns a generic not-found error. */
    private Snapshot snapshot(String id) {
        List<Snapshot> rows=jdbc.query("""
            SELECT STATEMENT_ID,STATEMENT_NUMBER,ACCOUNT_ID,CUSTOMER_CIF_ID,STATE,REVISION_NO,
              PERIOD_START_DATE,PERIOD_END_DATE,OPENING_POSTED_BALANCE,CLOSING_POSTED_BALANCE,
              s.CURRENCY_CODE,s.RESOLVED_LOCALE,s.REQUEST_ID,s.STATEMENT_TYPE,s.PRIOR_STATEMENT_ID,
              m.AUDIENCE_CODE,m.RULES_JSON
            FROM M10_STATEMENT_SNAPSHOT s JOIN M10_MASKING_PROFILE m
              ON m.MASKING_PROFILE_ID=s.MASKING_PROFILE_ID WHERE s.STATEMENT_ID=?
            """,(rs,n)->new Snapshot(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),
                rs.getString(5),rs.getInt(6),rs.getDate(7).toLocalDate(),rs.getDate(8).toLocalDate(),
                rs.getBigDecimal(9),rs.getBigDecimal(10),rs.getString(11),rs.getString(12),
                rs.getString(13),rs.getString(14),rs.getString(15),rs.getString(16),rs.getString(17)),id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"STATEMENT_NOT_FOUND","Statement not found");
        return rows.get(0);
    }
}
