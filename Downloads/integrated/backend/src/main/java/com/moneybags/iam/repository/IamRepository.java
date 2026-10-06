package com.moneybags.iam.repository;

import com.moneybags.common.database.*;
import com.moneybags.iam.model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class IamRepository {
    private final JdbcTemplate jdbc;
    private final SchemaRepository schema;
    public IamRepository(JdbcTemplate jdbc,SchemaRepository schema) { this.jdbc=jdbc;this.schema=schema; }
    public Optional<M01IamUserRow> userByName(String username) {
        return jdbc.query("SELECT * FROM M01_IAM_USER WHERE UPPER(USERNAME)=UPPER(?)",new SchemaRowMapper<>(M01IamUserRow.class),username).stream().findFirst();
    }
    public Optional<M01IamUserRow> user(String id) { return schema.find(SchemaTable.M01_IAM_USER,M01IamUserRow.class,Map.of("USER_ID",id)).stream().findFirst(); }
    public Optional<M01IamCredentialRow> credential(String userId,boolean lock) {
        return jdbc.query("SELECT * FROM M01_IAM_CREDENTIAL WHERE USER_ID=?"+(lock?" FOR UPDATE":""),new SchemaRowMapper<>(M01IamCredentialRow.class),userId).stream().findFirst();
    }
    public Optional<M01IamSessionRow> session(String sessionId,boolean lock) {
        return jdbc.query("SELECT * FROM M01_IAM_SESSION WHERE SESSION_ID=?"+(lock?" FOR UPDATE":""),new SchemaRowMapper<>(M01IamSessionRow.class),sessionId).stream().findFirst();
    }
    public List<M01IamSessionRow> sessions(String userId) { return jdbc.query("SELECT * FROM M01_IAM_SESSION WHERE USER_ID=? ORDER BY CREATED_AT DESC",new SchemaRowMapper<>(M01IamSessionRow.class),userId); }
    public Optional<M01IamRefreshTokenRow> refresh(String hash,boolean lock) {
        return jdbc.query("SELECT * FROM M01_IAM_REFRESH_TOKEN WHERE TOKEN_HASH=?"+(lock?" FOR UPDATE":""),new SchemaRowMapper<>(M01IamRefreshTokenRow.class),hash).stream().findFirst();
    }
    public List<String> roles(String userId,OffsetDateTime now) { return jdbc.queryForList("""
        SELECT DISTINCT R.ROLE_CODE FROM M01_IAM_USER_ROLE A JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID
        WHERE A.USER_ID=? AND A.STATUS='ACTIVE' AND R.STATUS='ACTIVE' AND A.VALID_FROM<=?
        AND (A.VALID_TO IS NULL OR A.VALID_TO>?) ORDER BY R.ROLE_CODE
        """,String.class,userId,now,now); }
    public List<String> permissions(String userId,OffsetDateTime now) { return jdbc.queryForList("""
        SELECT DISTINCT P.PERMISSION_CODE FROM M01_IAM_USER_ROLE A JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID
        JOIN M01_IAM_ROLE_PERMISSION RP ON RP.ROLE_ID=R.ROLE_ID JOIN M01_IAM_PERMISSION P ON P.PERMISSION_ID=RP.PERMISSION_ID
        WHERE A.USER_ID=? AND A.STATUS='ACTIVE' AND R.STATUS='ACTIVE' AND A.VALID_FROM<=?
        AND (A.VALID_TO IS NULL OR A.VALID_TO>?) ORDER BY P.PERMISSION_CODE
        """,String.class,userId,now,now); }
    public List<String> cifIds(String userId,OffsetDateTime now) { return jdbc.queryForList("""
        SELECT DISTINCT CIF_ID FROM M01_IAM_CUSTOMER_LINK WHERE USER_ID=? AND STATUS='ACTIVE'
        AND VALID_FROM<=? AND (VALID_TO IS NULL OR VALID_TO>?) ORDER BY CIF_ID
        """,String.class,userId,now,now); }
    public void failedAttempt(String userId,int failures,OffsetDateTime lockedUntil) {
        jdbc.update("UPDATE M01_IAM_CREDENTIAL SET FAILED_ATTEMPTS=?,LOCKED_UNTIL=? WHERE USER_ID=?",failures,lockedUntil,userId);
    }
    public void createSession(String id,String userId,String clientId,String device,OffsetDateTime now,OffsetDateTime idle,OffsetDateTime absolute) {
        schema.insert(SchemaTable.M01_IAM_SESSION,map("SESSION_ID",id,"USER_ID",userId,"CLIENT_ID",clientId,"DEVICE_REF",device,"AUTH_LEVEL","PASSWORD","STATUS","ACTIVE","CREATED_AT",now,"LAST_ACTIVITY_AT",now,"IDLE_EXPIRES_AT",idle,"ABSOLUTE_EXPIRES_AT",absolute));
    }
    public void touch(String id,OffsetDateTime now,OffsetDateTime idle) { jdbc.update("UPDATE M01_IAM_SESSION SET LAST_ACTIVITY_AT=?,IDLE_EXPIRES_AT=? WHERE SESSION_ID=? AND STATUS='ACTIVE'",now,idle,id); }
    public void setAuthLevel(String id,String level) {jdbc.update("UPDATE M01_IAM_SESSION SET AUTH_LEVEL=? WHERE SESSION_ID=?",level,id);}
    public void endSession(String id,String status,OffsetDateTime now) { jdbc.update("UPDATE M01_IAM_SESSION SET STATUS=?,ENDED_AT=? WHERE SESSION_ID=?",status,now,id); }
    public String createRefresh(String session,String family,String hash,OffsetDateTime now,OffsetDateTime expires) {
        String id=UUID.randomUUID().toString();schema.insert(SchemaTable.M01_IAM_REFRESH_TOKEN,map("TOKEN_ID",id,"SESSION_ID",session,"FAMILY_ID",family,"TOKEN_HASH",hash,"STATUS","ACTIVE","ISSUED_AT",now,"EXPIRES_AT",expires));return id;
    }
    public void replaceRefresh(String oldId,String newId,OffsetDateTime now) { jdbc.update("UPDATE M01_IAM_REFRESH_TOKEN SET STATUS='USED',USED_AT=?,REPLACED_BY_TOKEN_ID=? WHERE TOKEN_ID=?",now,newId,oldId); }
    public void revokeRefresh(String session) { jdbc.update("UPDATE M01_IAM_REFRESH_TOKEN SET STATUS='REVOKED' WHERE SESSION_ID=? AND STATUS='ACTIVE'",session); }
    public void markRefreshReused(String id,OffsetDateTime now) { jdbc.update("UPDATE M01_IAM_REFRESH_TOKEN SET STATUS='REUSED',USED_AT=? WHERE TOKEN_ID=?",now,id); }
    public void audit(String type,String userId,String session,String resource,String result,String correlation,OffsetDateTime now) {
        schema.insert(SchemaTable.M01_IAM_AUDIT_EVENT,map("AUDIT_ID",UUID.randomUUID().toString(),"EVENT_TYPE",type,"ACTOR_USER_ID",userId,"SESSION_ID",session,"RESOURCE_TYPE","IAM_USER","RESOURCE_ID",resource,"RESULT",result,"CORRELATION_ID",correlation,"OCCURRED_AT",now));
    }
    public static Map<String,Object> map(Object... pairs) { Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put(pairs[i].toString(),pairs[i+1]);return m; }
}
