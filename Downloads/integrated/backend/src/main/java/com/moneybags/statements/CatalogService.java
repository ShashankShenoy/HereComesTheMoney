package com.moneybags.statements;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.statements.ApiModels.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manages versioned masking and narration policy artifacts used at issue time. */
@Service
public class CatalogService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DecisionClient decisions;
    private final OutboxService outbox;
    public CatalogService(JdbcTemplate jdbc,ObjectMapper mapper,DecisionClient decisions,OutboxService outbox) {
        this.jdbc=jdbc;this.mapper=mapper;this.decisions=decisions;this.outbox=outbox;
    }
    /** Creates a draft masking policy with immutable content digest. */
    @Transactional public String maskDraft(Actor actor,PolicyDraft body) {
        authorize(actor,"MASKING_ADMIN");
        try { mapper.readTree(body.rulesJson()); }
        catch(Exception e) { throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_RULES_JSON","Rules must be valid JSON"); }
        String id=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO M10_MASKING_PROFILE
             (MASKING_PROFILE_ID,PROFILE_CODE,VERSION_NO,CHANNEL_CODE,AUDIENCE_CODE,
              RULES_JSON,RULES_SHA256,CREATED_BY)
            VALUES (?,?,?,?,?,?,?,?)
            """,id,body.code(),body.version(),body.channel(),body.audience(),body.rulesJson(),
            Hashing.sha256(body.rulesJson()),actor.id());
        return id;
    }
    /** Approves one draft; the DB trigger makes the approved artifact immutable. */
    @Transactional public void approveMask(Actor actor,String id) {
        authorize(actor,"MASKING_APPROVE");
        int count=jdbc.update("""
            UPDATE M10_MASKING_PROFILE SET STATUS='APPROVED',APPROVED_BY=?,APPROVED_AT=SYSTIMESTAMP
            WHERE MASKING_PROFILE_ID=? AND STATUS='DRAFT' AND CREATED_BY<>?
            """,actor.id(),id,actor.id());
        if(count==0) throw conflict("MASK_APPROVAL_CONFLICT");
        outbox.emit("MASKING_PROFILE",id,"MASKING_APPROVED","mask:"+id,null,Map.of("profileId",id));
    }
    /** Retires an approved version without editing its rules. */
    @Transactional public void retireMask(Actor actor,String id) {
        authorize(actor,"MASKING_APPROVE");
        if(jdbc.update("UPDATE M10_MASKING_PROFILE SET STATUS='RETIRED' WHERE MASKING_PROFILE_ID=? AND STATUS='APPROVED'",id)==0)
            throw conflict("MASK_RETIRE_CONFLICT");
    }
    /** Creates a versioned draft for a rendered narration class. */
    @Transactional public String narrationDraft(Actor actor,NarrationDraft body) {
        authorize(actor,"NARRATION_ADMIN");
        if(body.effectiveTo()!=null && !body.effectiveTo().isAfter(body.effectiveFrom()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_EFFECTIVE_RANGE","Invalid effective range");
        String id=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO M10_NARRATION_CATALOG
             (NARRATION_CATALOG_ID,NARRATION_CODE,LOCALE_CODE,AUDIENCE_CODE,VERSION_NO,
              RENDERED_TEXT,EFFECTIVE_FROM,EFFECTIVE_TO,CHECKSUM_SHA256,CREATED_BY)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,id,body.code(),body.locale(),body.audience(),body.version(),body.text(),
            body.effectiveFrom(),body.effectiveTo(),Hashing.sha256(body.text()),actor.id());
        return id;
    }
    /** Approves an independent narration version. */
    @Transactional public void approveNarration(Actor actor,String id) {
        authorize(actor,"NARRATION_APPROVE");
        int count=jdbc.update("""
            UPDATE M10_NARRATION_CATALOG SET APPROVAL_STATUS='APPROVED',
              APPROVED_BY=?,APPROVED_AT=SYSTIMESTAMP
            WHERE NARRATION_CATALOG_ID=? AND APPROVAL_STATUS='DRAFT' AND CREATED_BY<>?
            """,actor.id(),id,actor.id());
        if(count==0) throw conflict("NARRATION_APPROVAL_CONFLICT");
        outbox.emit("NARRATION",id,"NARRATION_APPROVED","narration:"+id,null,Map.of("narrationId",id));
    }
    /** Retires an approved version while leaving issued snapshots untouched. */
    @Transactional public void retireNarration(Actor actor,String id) {
        authorize(actor,"NARRATION_APPROVE");
        if(jdbc.update("UPDATE M10_NARRATION_CATALOG SET APPROVAL_STATUS='RETIRED' WHERE NARRATION_CATALOG_ID=? AND APPROVAL_STATUS='APPROVED'",id)==0)
            throw conflict("NARRATION_RETIRE_CONFLICT");
    }
    /** Lists approved policy metadata for integration and operations. */
    public List<Map<String,Object>> masks(Actor actor) {
        authorize(actor,"MASKING_READ");
        return jdbc.queryForList("""
            SELECT MASKING_PROFILE_ID,PROFILE_CODE,VERSION_NO,CHANNEL_CODE,AUDIENCE_CODE,STATUS
            FROM M10_MASKING_PROFILE ORDER BY PROFILE_CODE,VERSION_NO DESC
            """);
    }
    /** Lists narration metadata without exposing draft text to unapproved callers. */
    public List<Map<String,Object>> narrations(Actor actor) {
        authorize(actor,"NARRATION_READ");
        return jdbc.queryForList("""
            SELECT NARRATION_CATALOG_ID,NARRATION_CODE,LOCALE_CODE,AUDIENCE_CODE,VERSION_NO,APPROVAL_STATUS
            FROM M10_NARRATION_CATALOG ORDER BY NARRATION_CODE,VERSION_NO DESC
            """);
    }
    /** Requires a fresh administrative decision; policy mutations fail closed. */
    private void authorize(Actor actor,String action) {
        DecisionClient.Decision d=decisions.decide(new DecisionClient.DecisionQuery(actor.id(),actor.type(),
            actor.sessionId(),action,null,null,null,null,null,null,null));
        if(!d.allowed()) throw new ApiException(HttpStatus.FORBIDDEN,"ACCESS_DENIED","Policy access denied");
    }
    /** Produces one stable conflict for invalid state transitions. */
    private ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Invalid policy transition"); }
}
