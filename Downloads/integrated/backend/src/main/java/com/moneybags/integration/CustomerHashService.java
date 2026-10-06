package com.moneybags.integration;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.moneybags.common.api.BusinessException;
import org.springframework.http.HttpStatus;
import java.security.*;
import java.util.*;
@Service
public class CustomerHashService {
 private final JdbcTemplate db;private final SecureRandom random=new SecureRandom();
 public CustomerHashService(JdbcTemplate db){this.db=db;}
 public static byte[] digest(String value){
  try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
 @Transactional public String issue(String userId){
  byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  db.queryForList("SELECT USER_ID FROM M01_IAM_USER WHERE USER_ID=? FOR UPDATE",userId);
  if(db.update("UPDATE MBX_CUSTOMER_SECRET SET SECRET_DIGEST=?,ROTATED_AT=SYSTIMESTAMP WHERE USER_ID=?",digest(token),userId)==0)
   db.update("INSERT INTO MBX_CUSTOMER_SECRET(USER_ID,SECRET_DIGEST) VALUES (?,?)",userId,digest(token));
  return token;
 }
 public boolean permits(long accountId,String token){
  var u=CurrentActor.get();
  if("CUSTOMER".equals(u.userType())){
   Integer matches=db.queryForObject("SELECT COUNT(*) FROM M01_IAM_CUSTOMER_LINK L JOIN M04_ACCOUNT_PARTY P ON P.CIF_ID=L.CIF_ID WHERE L.USER_ID=? AND L.STATUS='ACTIVE' AND L.VALID_FROM<=SYSTIMESTAMP AND (L.VALID_TO IS NULL OR L.VALID_TO>SYSTIMESTAMP) AND P.ACCOUNT_ID=? AND P.IS_ACTIVE='Y' AND P.PARTY_ROLE IN ('PRIMARY_HOLDER','JOINT_HOLDER','AUTHORIZED_SIGNATORY') AND P.EFFECTIVE_FROM<=SYSDATE AND (P.EFFECTIVE_TO IS NULL OR P.EFFECTIVE_TO>SYSDATE)",Integer.class,u.userId(),accountId);
   return matches!=null&&matches>0;
  }
  if(token==null||token.length()!=43)return false;
  var rows=db.queryForList("SELECT S.SECRET_DIGEST,L.USER_ID FROM MBX_CUSTOMER_SECRET S JOIN M01_IAM_USER U ON U.USER_ID=S.USER_ID JOIN M01_IAM_CUSTOMER_LINK L ON L.USER_ID=S.USER_ID JOIN M04_ACCOUNT_PARTY P ON P.CIF_ID=L.CIF_ID WHERE U.STATUS='ACTIVE' AND U.USER_TYPE='CUSTOMER' AND P.PARTY_ROLE IN ('PRIMARY_HOLDER','JOINT_HOLDER','AUTHORIZED_SIGNATORY') AND P.ACCOUNT_ID=? AND P.IS_ACTIVE='Y' AND P.EFFECTIVE_FROM<=SYSDATE AND (P.EFFECTIVE_TO IS NULL OR P.EFFECTIVE_TO>SYSDATE) AND L.STATUS='ACTIVE' AND L.VALID_FROM<=SYSTIMESTAMP AND (L.VALID_TO IS NULL OR L.VALID_TO>SYSTIMESTAMP)",accountId);
  return rows.stream().anyMatch(r->MessageDigest.isEqual((byte[])r.get("SECRET_DIGEST"),digest(token)));
 }
 public void require(long accountId,String token){
  if(!permits(accountId,token))throw new BusinessException(HttpStatus.FORBIDDEN,"FINANCIAL_ACCESS_REQUIRED","An authorized customer session or account-holder access key is required to view financial details");
 }
 public String proofLabel(){return "CUSTOMER".equals(CurrentActor.get().userType())?"AUTHENTICATED_CUSTOMER":"CUSTOMER_HASH";}
 public void audit(String action,String type,String id,String reason){
  db.update("INSERT INTO MBX_AUDIT(EVENT_ID,ACTOR_ID,ACTION_CODE,RESOURCE_TYPE,RESOURCE_ID,REASON_CODE) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),CurrentActor.get().userId(),action,type,id,reason);
 }
}
