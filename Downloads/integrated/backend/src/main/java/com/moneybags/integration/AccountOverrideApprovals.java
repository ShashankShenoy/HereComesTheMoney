package com.moneybags.integration;

import com.moneybags.account.api.Models.*;
import com.moneybags.common.api.BusinessException;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.iam.dto.IamDtos.AuthorizationInput;
import com.moneybags.iam.service.AccessDecisionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

/** An approved name in a payload is never sufficient approval evidence. */
@RestController @RequestMapping("/api/v1/accounts")
public class AccountOverrideApprovals {
 private final BusinessRepository db;private final BankingAccess access;private final AccessDecisionService iam;private final ObjectMapper json;private final CustomerHashService audit;
 public AccountOverrideApprovals(BusinessRepository db,BankingAccess access,AccessDecisionService iam,ObjectMapper json,CustomerHashService audit){this.db=db;this.access=access;this.iam=iam;this.json=json;this.audit=audit;}
 public record Proposal(@Valid LimitCommand limit,@Valid InterestCommand interest){}
 public record Decision(@NotBlank @Pattern(regexp="APPROVED|REJECTED")String decision,@NotBlank @Size(max=300)String reason){}
 @GetMapping("/{id}/override-approvals") @Operation(summary="Review account override proposals")
 public List<Map<String,Object>> list(@PathVariable long id){access.account("ACCOUNT_CONTROL",id);return db.rows("SELECT REQUEST_ID,ACTION_CODE,PROPOSED_VALUE,MAKER_ID,CHECKER_ID,STATUS,EXPIRES_AT,REASON_TEXT FROM MBX_OVERRIDE_APPROVAL WHERE ACCOUNT_ID=? ORDER BY CREATED_AT DESC",id);}
 @PostMapping("/{id}/override-approvals") @Transactional @Operation(summary="Propose an account limit or interest override")
 public Map<String,Object> propose(@PathVariable long id,@Valid @RequestBody Proposal p){
  var a=access.account("ACCOUNT_OVERRIDE",id);if((p.limit()==null)==(p.interest()==null))throw fail("Supply exactly one limit or interest command");
  String action=p.limit()!=null?"LIMIT":"INTEREST",request=p.limit()!=null?p.limit().requestId():p.interest().requestId(),checker=p.limit()!=null?p.limit().approvedByUserId():p.interest().approvedByUserId();
  Long policy=p.limit()!=null?p.limit().overridePolicyId():p.interest().overridePolicyId();BigDecimal value=p.limit()!=null?p.limit().amount():p.interest().ratePct();
  Object command=p.limit()!=null?p.limit():p.interest();String encoded=encode(command);String maker=CurrentActor.get().userId();
  if(policy==null||checker==null||checker.equals(maker))throw fail("Choose a product override policy and a distinct checker");
  var old=db.rows("SELECT * FROM MBX_OVERRIDE_APPROVAL WHERE REQUEST_ID=?",request);
  if(!old.isEmpty()){var r=old.get(0);if(!maker.equals(r.get("MAKER_ID"))||((Number)r.get("ACCOUNT_ID")).longValue()!=id||!MessageDigest.isEqual((byte[])r.get("COMMAND_HASH"),CustomerHashService.digest(encoded)))throw fail("Request ID belongs to another proposal");return Map.of("requestId",request,"status",r.get("STATUS"));}
  var rule=policy(policy,a.get("PRODUCT_VERSION_ID"),action);bounds(rule,value);
  if(db.count("SELECT COUNT(*) FROM M01_IAM_USER WHERE USER_ID=? AND USER_TYPE='EMPLOYEE' AND STATUS='ACTIVE'",checker)!=1)throw fail("Checker must be an active employee");
  db.jdbc().update("INSERT INTO MBX_OVERRIDE_APPROVAL(REQUEST_ID,ACCOUNT_ID,PRODUCT_VERSION_ID,ACTION_CODE,COMMAND_HASH,COMMAND_JSON,POLICY_ID,PROPOSED_VALUE,MAKER_ID,CHECKER_ID,EXPIRES_AT) VALUES (?,?,?,?,?,?,?,?,?,?,?)",request,id,a.get("PRODUCT_VERSION_ID"),action,CustomerHashService.digest(encoded),encoded,policy,value,maker,checker,OffsetDateTime.now().plusHours(24));
  audit.audit("ACCOUNT_OVERRIDE_PROPOSED","ACCOUNT",Long.toString(id),action);return Map.of("requestId",request,"status","PENDING");
 }
 @PostMapping("/{id}/override-approvals/{request}/decision") @Transactional @Operation(summary="Independently decide an account override")
 public Map<String,Object> decide(@PathVariable long id,@PathVariable String request,@Valid @RequestBody Decision d){
  var a=access.account("ACCOUNT_APPROVE",id);var p=db.one("SELECT * FROM MBX_OVERRIDE_APPROVAL WHERE REQUEST_ID=? AND ACCOUNT_ID=? FOR UPDATE",request,id);
  if(!CurrentActor.get().userId().equals(p.get("CHECKER_ID")))throw new BusinessException(HttpStatus.FORBIDDEN,"CHECKER_MISMATCH","Only the nominated independent checker can decide");
  if(!"PENDING".equals(p.get("STATUS"))||!fresh(request))throw fail("Proposal is expired or already decided");
  var rule=policy(p.get("POLICY_ID"),a.get("PRODUCT_VERSION_ID"),(String)p.get("ACTION_CODE"));BigDecimal value=(BigDecimal)p.get("PROPOSED_VALUE");bounds(rule,value);
  if(!a.get("PRODUCT_VERSION_ID").equals(p.get("PRODUCT_VERSION_ID")))throw fail("Account product version changed");
  iam.require(CurrentActor.get(),new AuthorizationInput("ACCOUNT_APPROVE",(String)a.get("BRANCH_CODE"),null,(String)a.get("PRIMARY_CIF_ID"),(String)a.get("PRODUCT_TYPE"),"INR",(String)rule.get("AUTHORITY_CODE"),"LIMIT".equals(p.get("ACTION_CODE"))?value:null,"INTEREST".equals(p.get("ACTION_CODE"))?value:null,(String)p.get("MAKER_ID")));
  db.jdbc().update("UPDATE MBX_OVERRIDE_APPROVAL SET STATUS=?,DECIDED_AT=SYSTIMESTAMP,REASON_TEXT=? WHERE REQUEST_ID=?",d.decision(),d.reason(),request);audit.audit("ACCOUNT_OVERRIDE_DECIDED","ACCOUNT",Long.toString(id),d.decision());return Map.of("requestId",request,"status",d.decision());
 }
 /** Invoked within the account mutation transaction so an unsuccessful apply preserves approval. */
 public void consume(long id,String action,String request,Object command){
  var p=db.one("SELECT * FROM MBX_OVERRIDE_APPROVAL WHERE REQUEST_ID=? AND ACCOUNT_ID=? FOR UPDATE",request,id);
  if(!"APPROVED".equals(p.get("STATUS"))||!fresh(request)||!action.equals(p.get("ACTION_CODE"))||!CurrentActor.get().userId().equals(p.get("MAKER_ID"))||!MessageDigest.isEqual((byte[])p.get("COMMAND_HASH"),CustomerHashService.digest(encode(command))))throw fail("A current approval for this exact command and maker is required");
  var a=access.account("ACCOUNT_OVERRIDE",id);if(!a.get("PRODUCT_VERSION_ID").equals(p.get("PRODUCT_VERSION_ID")))throw fail("Account product version changed");
  db.jdbc().update("UPDATE MBX_OVERRIDE_APPROVAL SET STATUS='APPLIED' WHERE REQUEST_ID=?",request);audit.audit("ACCOUNT_OVERRIDE_APPLIED","ACCOUNT",Long.toString(id),action);
 }
 private Map<String,Object> policy(Object id,Object version,String family){return db.one("SELECT MIN_VALUE,MAX_VALUE,AUTHORITY_CODE FROM M03_PM_OVERRIDE_POLICY WHERE OVERRIDE_POLICY_ID=? AND PRODUCT_VERSION_ID=? AND RULE_FAMILY=? AND VALID_FROM<=SYSTIMESTAMP AND (VALID_TO IS NULL OR VALID_TO>SYSTIMESTAMP)",id,version,family);}
 private void bounds(Map<String,Object> p,BigDecimal v){if((p.get("MIN_VALUE") instanceof BigDecimal min&&v.compareTo(min)<0)||(p.get("MAX_VALUE") instanceof BigDecimal max&&v.compareTo(max)>0))throw fail("Value is outside the product override policy");}
 private boolean fresh(String id){return db.count("SELECT COUNT(*) FROM MBX_OVERRIDE_APPROVAL WHERE REQUEST_ID=? AND EXPIRES_AT>SYSTIMESTAMP",id)==1;}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw fail("Invalid override command");}}
 private BusinessException fail(String message){return new BusinessException(HttpStatus.CONFLICT,"OVERRIDE_APPROVAL",message);}
}
