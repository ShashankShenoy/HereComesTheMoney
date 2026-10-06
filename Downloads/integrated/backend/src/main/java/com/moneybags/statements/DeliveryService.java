package com.moneybags.statements;

import com.moneybags.statements.ApiModels.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tracks delivery attempts without changing issued statement evidence. */
@Service
public class DeliveryService {
    private final JdbcTemplate jdbc;
    private final OutboxService outbox;
    public DeliveryService(JdbcTemplate jdbc,OutboxService outbox) { this.jdbc=jdbc;this.outbox=outbox; }
    public record RecipientContext(String channel,String type,String hash,String format) {}
    /** Loads stored recipient facts for a fresh dispatch decision. */
    public RecipientContext context(String id) {
        List<RecipientContext> rows=jdbc.query("""
            SELECT DELIVERY_CHANNEL,RECIPIENT_TYPE,RECIPIENT_REFERENCE_HASH,RENDER_FORMAT
            FROM M10_STATEMENT_DELIVERY_EVIDENCE WHERE DELIVERY_ID=?
            """,(rs,n)->new RecipientContext(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"DELIVERY_NOT_FOUND","Delivery not found");
        return rows.get(0);
    }
    /** Requests one idempotent delivery for an issued statement. */
    @Transactional public DeliveryView request(String statementId,String key,DeliveryRequest body,
        String authorizationRef,Actor actor) {
        if(key==null || key.isBlank() || key.length()>160 ||
            !Set.of("DOWNLOAD","EMAIL","POST","BRANCH","API").contains(body.channel()) ||
            !Set.of("CUSTOMER","STAFF","REGULATOR","SYSTEM").contains(body.recipientType()) ||
            !Set.of("PDF","CSV","HTML").contains(body.format()) ||
            !body.recipientReferenceHash().matches("[a-fA-F0-9]{64}"))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_DELIVERY","Invalid delivery request");
        List<Map<String,Object>> existing=jdbc.queryForList("""
            SELECT DELIVERY_ID,DELIVERY_CHANNEL,RECIPIENT_TYPE,RECIPIENT_REFERENCE_HASH,RENDER_FORMAT
            FROM M10_STATEMENT_DELIVERY_EVIDENCE
            WHERE STATEMENT_ID=? AND DELIVERY_IDEMPOTENCY_KEY=?
            """,statementId,key);
        if(!existing.isEmpty()) {
            Map<String,Object> row=existing.get(0);
            if(!row.get("DELIVERY_CHANNEL").equals(body.channel()) ||
               !row.get("RECIPIENT_TYPE").equals(body.recipientType()) ||
               !((String)row.get("RECIPIENT_REFERENCE_HASH")).equalsIgnoreCase(body.recipientReferenceHash()) ||
               !row.get("RENDER_FORMAT").equals(body.format()))
                throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","Delivery key reused");
            return get((String)row.get("DELIVERY_ID"));
        }
        String id=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO M10_STATEMENT_DELIVERY_EVIDENCE
             (DELIVERY_ID,STATEMENT_ID,DELIVERY_IDEMPOTENCY_KEY,DELIVERY_CHANNEL,
              RECIPIENT_TYPE,RECIPIENT_REFERENCE_HASH,RENDER_FORMAT,AUTHORIZATION_REF,
              CORRELATION_ID,CREATED_BY)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,id,statementId,key,body.channel(),body.recipientType(),body.recipientReferenceHash(),
            body.format(),authorizationRef,body.correlationId(),actor.id());
        outbox.emit("STATEMENT_DELIVERY",id,"DELIVERY_REQUESTED","delivery:"+id,body.correlationId(),
            Map.of("deliveryId",id,"statementId",statementId,"format",body.format()));
        return get(id);
    }
    /** Records a renderer or channel outcome with a strict transition check. */
    @Transactional public DeliveryView complete(String id,DeliveryResult result) {
        if(!Set.of("DISPATCHED","DELIVERED","FAILED","CANCELLED").contains(result.status()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_DELIVERY_STATE","Invalid delivery state");
        if(result.status().equals("DELIVERED") && (result.renderSha256()==null ||
            !result.renderSha256().matches("[a-fA-F0-9]{64}") ||
            result.renderUri()==null || result.renderUri().isBlank()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"RENDER_EVIDENCE_REQUIRED","Render URI and hash required");
        if(result.status().equals("FAILED") && (result.failureCode()==null || result.failureCode().isBlank()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"FAILURE_CODE_REQUIRED","Failure code required");
        String from=result.status().equals("DISPATCHED")?"REQUESTED":"DISPATCHED";
        int count=jdbc.update("""
            UPDATE M10_STATEMENT_DELIVERY_EVIDENCE
            SET DELIVERY_STATUS=?,DISPATCHED_AT=CASE WHEN ?='DISPATCHED' THEN SYSTIMESTAMP ELSE DISPATCHED_AT END,
                COMPLETED_AT=CASE WHEN ? IN ('DELIVERED','FAILED','CANCELLED') THEN SYSTIMESTAMP ELSE NULL END,
                RENDER_URI=?,RENDER_SHA256=?,FAILURE_CODE=?
            WHERE DELIVERY_ID=? AND DELIVERY_STATUS=?
            """,result.status(),result.status(),result.status(),result.renderUri(),result.renderSha256(),
            result.failureCode(),id,from);
        if(count==0) throw new ApiException(HttpStatus.CONFLICT,"DELIVERY_TRANSITION","Invalid delivery transition");
        DeliveryView view=get(id);
        outbox.emit("STATEMENT_DELIVERY",id,"DELIVERY_"+result.status(),
            "delivery:"+id+":"+result.status(),null,Map.of("deliveryId",id,"statementId",view.statementId()));
        return view;
    }
    /** Loads one delivery evidence row. */
    public DeliveryView get(String id) {
        List<DeliveryView> rows=jdbc.query("""
            SELECT DELIVERY_ID,STATEMENT_ID,DELIVERY_STATUS,DELIVERY_CHANNEL,RENDER_FORMAT,
              RENDER_URI,FAILURE_CODE FROM M10_STATEMENT_DELIVERY_EVIDENCE WHERE DELIVERY_ID=?
            """,(rs,n)->new DeliveryView(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)),id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"DELIVERY_NOT_FOUND","Delivery not found");
        return rows.get(0);
    }
    /** Lists evidence associated with a statement for an authorized viewer. */
    public List<DeliveryView> list(String statementId) {
        return jdbc.query("""
            SELECT DELIVERY_ID,STATEMENT_ID,DELIVERY_STATUS,DELIVERY_CHANNEL,RENDER_FORMAT,
              RENDER_URI,FAILURE_CODE FROM M10_STATEMENT_DELIVERY_EVIDENCE
            WHERE STATEMENT_ID=? ORDER BY REQUESTED_AT DESC
            """,(rs,n)->new DeliveryView(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)),statementId);
    }
}
