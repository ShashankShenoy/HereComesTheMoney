package com.moneybags.integration;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.common.api.BusinessException;
import com.moneybags.iam.service.AccessDecisionService;
import com.moneybags.iam.dto.IamDtos.AuthorizationInput;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import java.util.*;
@Service
public class BankingAccess {
 private final BusinessRepository db;private final AccessDecisionService access;
 public BankingAccess(BusinessRepository db,AccessDecisionService access){this.db=db;this.access=access;}
 public Map<String,Object> account(String permission,long id){
  var u=CurrentActor.get();
  var a=db.one("SELECT A.*,P.PRODUCT_TYPE FROM M04_BANK_ACCOUNT A JOIN M03_PM_PRODUCT P ON P.PRODUCT_ID=A.PRODUCT_ID WHERE A.ACCOUNT_ID=?",id);
  String cif=(String)a.get("PRIMARY_CIF_ID");
  if("CUSTOMER".equals(u.userType())){
   var owned=db.rows("SELECT CIF_ID,OPERATING_INSTRUCTION FROM M04_ACCOUNT_PARTY WHERE ACCOUNT_ID=? AND IS_ACTIVE='Y' AND PARTY_ROLE IN ('PRIMARY_HOLDER','JOINT_HOLDER','AUTHORIZED_SIGNATORY','GUARDIAN') AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>SYSDATE)",id)
    .stream().filter(p->u.cifIds().contains(p.get("CIF_ID"))).toList();
   if(owned.isEmpty())deny();
   cif=(String)owned.get(0).get("CIF_ID");
   if(!Set.of("ACCOUNT_READ","TXN_READ","STATEMENT_READ").contains(permission)&&Set.of("JOINTLY","GUARDIAN_OPERATED").contains(a.get("ACCOUNT_OPERATION_MODE")))
     throw new BusinessException(HttpStatus.CONFLICT,"MULTI_PARTY_AUTHORITY","This account requires a joint or guardian authorization workflow");
  }
  access.require(u,new AuthorizationInput(permission,(String)a.get("BRANCH_CODE"),null,cif,(String)a.get("PRODUCT_TYPE"),(String)a.get("CURRENCY_CODE"),null,null,null,null));
  return a;
 }
 public Map<String,Object> facility(String permission,long id){var f=db.one("SELECT FACILITY_ID,PRIMARY_CIF_ID,BRANCH_CODE FROM M08_LOAN_FACILITY WHERE FACILITY_ID=?",id);access.require(CurrentActor.get(),new AuthorizationInput(permission,(String)f.get("BRANCH_CODE"),null,(String)f.get("PRIMARY_CIF_ID"),"LOAN","INR",null,null,null,null));return f;}
 public void global(String permission){new com.moneybags.iam.security.IamGuard(db.jdbc(),java.time.Clock.systemUTC()).require(CurrentActor.get(),permission);}
 public void branch(String permission,String branchCode){access.require(CurrentActor.get(),new AuthorizationInput(permission,branchCode,null,null,null,"INR",null,null,null,null));}
 /** Bank-wide transaction review belongs only to a current, unrestricted BANK_ADMIN assignment. */
 public boolean bankAdminTransactionRead(){
  var u=CurrentActor.get();
  if(!"EMPLOYEE".equals(u.userType())||!u.permissions().contains("TXN_READ"))return false;
  Long count=db.jdbc().queryForObject("""
    SELECT COUNT(*) FROM M01_IAM_USER_ROLE A
    JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID
    JOIN M01_IAM_USER U ON U.USER_ID=A.USER_ID
    JOIN M01_IAM_SESSION S ON S.USER_ID=U.USER_ID
    WHERE U.USER_ID=? AND U.STATUS='ACTIVE' AND R.ROLE_CODE='BANK_ADMIN' AND R.STATUS='ACTIVE'
      AND A.STATUS='ACTIVE' AND A.SCOPE_TYPE='GLOBAL' AND A.SCOPE_REF IS NULL
      AND A.VALID_FROM<=SYSTIMESTAMP AND (A.VALID_TO IS NULL OR A.VALID_TO>SYSTIMESTAMP)
      AND S.SESSION_ID=? AND S.STATUS='ACTIVE'
      AND S.IDLE_EXPIRES_AT>SYSTIMESTAMP AND S.ABSOLUTE_EXPIRES_AT>SYSTIMESTAMP
      AND NOT EXISTS (SELECT 1 FROM M01_IAM_USER_ROLE_SCOPE X WHERE X.ASSIGNMENT_ID=A.ASSIGNMENT_ID)
    """,Long.class,u.userId(),u.sessionId());
  return count!=null&&count>0;
 }
 public void transaction(String permission,long id){var t=db.one("SELECT SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID FROM M05_TXN_TRANSACTION_LOG WHERE TXN_ID=?",id);if(t.get("SOURCE_ACCOUNT_ID") instanceof Number n)account(permission,n.longValue());else if(t.get("TARGET_ACCOUNT_ID") instanceof Number n)account(permission,n.longValue());else global(permission);}
 private void deny(){throw new BusinessException(HttpStatus.FORBIDDEN,"NOT_ACCOUNT_HOLDER","This account is outside your access");}
}
