package com.moneybags.account.integration;

import com.moneybags.account.api.ApiException;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.iam.dto.IamDtos.AuthorizationInput;
import com.moneybags.iam.service.AccessDecisionService;
import com.moneybags.integration.CurrentActor;
import com.moneybags.product.service.ProductDefinitionsPort;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Component
public class ModuleClients {
 private final BusinessRepository db;
 private final AccessDecisionService access;
 private final ProductDefinitionsPort products;
 public ModuleClients(BusinessRepository db,AccessDecisionService access,ProductDefinitionsPort products) {this.db=db;this.access=access;this.products=products;}
 public record CustomerDecision(String decisionRef,String status,String kycStatus,Boolean minor,String homeBranch) {}
 public record ProductDecision(String decisionRef,String productType,String currency,String versionState,String ruleSetHash,BigDecimal minimumOpeningBalance) {}
 public record IamDecision(String decisionRef,boolean allowed) {}
 public record Clearance(boolean cleared,String reference,String reason) {}
 public record Treatment(boolean allowed,Boolean consentRequired,String reference) {}
 public record PolicyDecision(boolean allowed,String reference) {}
 private String ref(){return UUID.randomUUID().toString();}
 public CustomerDecision customer(String cif,String branch){
  var c=db.one("SELECT C.STATUS,C.KYC_STATUS,C.HOME_BRANCH_REF,P.DATE_OF_BIRTH,P.PARTY_TYPE FROM M02_CIF_CUSTOMER C JOIN M02_CIF_PARTY P ON P.PARTY_ID=C.PARTY_ID WHERE C.CIF_ID=?",cif);
  LocalDate dob=BusinessRepository.localDate(c.get("DATE_OF_BIRTH"));
  Boolean minor="ORGANIZATION".equals(c.get("PARTY_TYPE"))?false:dob==null?null:dob.plusYears(18).isAfter(LocalDate.now(ZoneId.of("Asia/Kolkata")));
  return new CustomerDecision(ref(),(String)c.get("STATUS"),(String)c.get("KYC_STATUS"),minor,(String)c.get("HOME_BRANCH_REF"));
 }
 @SuppressWarnings("unchecked")
 public ProductDecision product(long product,long version,String branch,String cif){
  String segment=(String)db.one("SELECT SEGMENT_CODE FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",cif).get("SEGMENT_CODE");
  var definition=products.resolve(BigDecimal.valueOf(product),branch,segment,"BRANCH","INR",OffsetDateTime.now());
  var p=(Map<String,Object>)definition.get("product");var v=(Map<String,Object>)definition.get("version");
  if(((Number)v.get("PRODUCT_VERSION_ID")).longValue()!=version) throw new ApiException(HttpStatus.CONFLICT,"VERSION_MISMATCH","Selected version is not effective");
  var rule=db.one("SELECT MIN_OPENING_BALANCE FROM M03_PM_ACCOUNT_RULE WHERE PRODUCT_VERSION_ID=?",version);
  return new ProductDecision(ref(),(String)p.get("PRODUCT_TYPE"),(String)p.get("CURRENCY_CODE"),(String)v.get("VERSION_STATE"),(String)v.get("RULE_SET_HASH"),(BigDecimal)rule.get("MIN_OPENING_BALANCE"));
 }
 public IamDecision opening(String branch,String cif,String product){
  var result=access.evaluate(CurrentActor.get(),new AuthorizationInput("ACCOUNT_OPEN",branch,null,cif,product,"INR",null,null,null,null));
  return new IamDecision(ref(),result.allowed());
 }
 public void read(long id){new com.moneybags.integration.BankingAccess(db,access).account("ACCOUNT_READ",id);}
 public CustomerDecision party(String cif,String branch){return customer(cif,branch);}
 public IamDecision authorize(String actor,String action,Long id){
  var user=CurrentActor.get(); if(!user.userId().equals(actor))return new IamDecision(ref(),false);
  String permission=switch(action){case "ACCOUNT_READ"->"ACCOUNT_READ";case "ACCOUNT_OPEN","ACCOUNT_ACTIVATE","ACCOUNT_OPEN_CANCEL"->"ACCOUNT_OPEN";case "ACCOUNT_CLOSURE_APPROVE","ACCOUNT_CLOSE_APPROVE","ACCOUNT_CLOSE_REJECT","ACCOUNT_MAJORITY_DECIDE"->"ACCOUNT_APPROVE";default->"ACCOUNT_CONTROL";};
  Map<String,Object> a=id==null?Map.of():db.one("SELECT A.BRANCH_CODE,A.PRIMARY_CIF_ID,P.PRODUCT_TYPE,A.CURRENCY_CODE FROM M04_BANK_ACCOUNT A JOIN M03_PM_PRODUCT P ON P.PRODUCT_ID=A.PRODUCT_ID WHERE A.ACCOUNT_ID=?",id);
  var result=access.evaluate(user,new AuthorizationInput(permission,(String)a.get("BRANCH_CODE"),null,(String)a.get("PRIMARY_CIF_ID"),(String)a.get("PRODUCT_TYPE"),(String)a.get("CURRENCY_CODE"),null,null,null,null));
  return new IamDecision(ref(),result.allowed());
 }
 public PolicyDecision policy(long version,String family,long policy,BigDecimal value){
  var rows=db.rows("SELECT MIN_VALUE,MAX_VALUE,AUTHORITY_CODE FROM M03_PM_OVERRIDE_POLICY WHERE OVERRIDE_POLICY_ID=? AND PRODUCT_VERSION_ID=? AND RULE_FAMILY=? AND VALID_FROM<=SYSTIMESTAMP AND (VALID_TO IS NULL OR VALID_TO>SYSTIMESTAMP)",policy,version,family);
  if(rows.isEmpty())return new PolicyDecision(false,ref());
  var r=rows.get(0);boolean within=(r.get("MIN_VALUE")==null||value.compareTo((BigDecimal)r.get("MIN_VALUE"))>=0)&&(r.get("MAX_VALUE")==null||value.compareTo((BigDecimal)r.get("MAX_VALUE"))<=0);
  return new PolicyDecision(within&&CurrentActor.get().permissions().contains("ACCOUNT_OVERRIDE"),ref());
 }
 public PolicyDecision limit(long version,Long rule,Long override,String operation,String period,BigDecimal amount){
  if(override!=null)return policy(version,"LIMIT",override,amount);
  var rows=db.rows("SELECT MAX_AMOUNT FROM M03_PM_LIMIT_RULE WHERE PRODUCT_VERSION_ID=? AND LIMIT_RULE_ID=? AND OPERATION_CODE=? AND PERIOD_CODE=?",version,rule,operation,period);
  return new PolicyDecision(rows.size()==1&&amount.signum()>=0&&(rows.get(0).get("MAX_AMOUNT")==null||amount.compareTo((BigDecimal)rows.get(0).get("MAX_AMOUNT"))<=0),ref());
 }
 public Clearance openingFunding(long id,BigDecimal min){
  return clear(db.count("SELECT COUNT(*) FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=? AND POSTED_BALANCE>=?",id,min)>0);
 }
 public Treatment treatment(long from,long to,long id){
  var rows=db.rows("SELECT T.CONSENT_REQUIRED FROM M03_PM_VERSION_TREATMENT T JOIN M03_PM_APPROVAL A ON A.APPROVAL_ID=T.APPROVAL_ID WHERE T.VERSION_TREATMENT_ID=? AND T.SOURCE_VERSION_ID=? AND T.TARGET_VERSION_ID=? AND A.REQUEST_STATUS='APPROVED' AND (T.MIGRATION_FROM_AT IS NULL OR T.MIGRATION_FROM_AT<=SYSTIMESTAMP)",id,from,to);
  return new Treatment(!rows.isEmpty(),!rows.isEmpty()&&"Y".equals(rows.get(0).get("CONSENT_REQUIRED")),ref());
 }
 public Clearance transactionClearance(long id,long version){
  var rows=db.rows("SELECT P.POSTED_BALANCE,P.ACTIVE_HOLD_AMOUNT,P.ACTIVE_LIEN_AMOUNT,P.ACTIVE_BLOCK_AMOUNT,F.DEBIT_STATUS,F.CREDIT_STATUS,F.CONTROL_VERSION FROM M05_ACCOUNT_POSITION P JOIN M05_POSTING_FENCE F ON F.BANK_ACCOUNT_ID=P.BANK_ACCOUNT_ID WHERE P.BANK_ACCOUNT_ID=?",id);
  if(rows.isEmpty())return clear(version==0);
  var p=rows.get(0);boolean zero=List.of("POSTED_BALANCE","ACTIVE_HOLD_AMOUNT","ACTIVE_LIEN_AMOUNT","ACTIVE_BLOCK_AMOUNT").stream().allMatch(k->((BigDecimal)p.get(k)).signum()==0);
  return clear(zero&&"CLOSED".equals(p.get("DEBIT_STATUS"))&&"CLOSED".equals(p.get("CREDIT_STATUS"))&&((Number)p.get("CONTROL_VERSION")).longValue()>=version);
 }
 public Clearance paymentClearance(long id){return clear(db.count("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION WHERE (SOURCE_ACCOUNT_ID=? OR DESTINATION_ACCOUNT_ID=?) AND STATUS NOT IN ('SETTLED','REJECTED','CANCELLED','REFUNDED')",id,id)==0);}
 public Clearance loanClearance(long id){return clear(db.count("SELECT COUNT(*) FROM M08_LOAN_FACILITY WHERE (DISBURSEMENT_ACCOUNT_ID=? OR REPAYMENT_ACCOUNT_ID=?) AND STATUS NOT IN ('CLOSED','CANCELLED')",id,id)==0 && db.count("SELECT COUNT(*) FROM M11_CC_CARD WHERE REPAYMENT_ACCOUNT_ID=? AND STATUS<>'CLOSED'",id)==0 && db.count("SELECT COUNT(*) FROM M11_CC_APPLICATION WHERE REPAYMENT_ACCOUNT_ID=? AND STATUS='PENDING'",id)==0);}
 private Clearance clear(boolean yes){return new Clearance(yes,ref(),yes?null:"Outstanding balance, control or obligation");}
}

