package com.moneybags.statements;
import org.springframework.stereotype.Component;
import com.moneybags.integration.CurrentActor;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.iam.service.AccessDecisionService;
import com.moneybags.iam.dto.IamDtos.AuthorizationInput;
import java.util.UUID;
@Component
public class DecisionClient {
 private final BusinessRepository db;private final AccessDecisionService access;private final com.moneybags.integration.CustomerHashService hashes;private final com.moneybags.integration.BankingAccess banking;
 public DecisionClient(BusinessRepository db,AccessDecisionService access,com.moneybags.integration.CustomerHashService hashes,com.moneybags.integration.BankingAccess banking){this.db=db;this.access=access;this.hashes=hashes;this.banking=banking;}
 public record DecisionQuery(String actorId,String actorType,String sessionId,String action,Long accountId,String customerCifId,String statementId,String purposeCode,String caseReference,String requesterId,String requesterType,String deliveryChannel,String recipientType,String recipientReferenceHash,String renderFormat) {
 public DecisionQuery(String actorId,String actorType,String sessionId,String action,Long accountId,String cif,String statementId,String purpose,String caseRef,String requesterId,String requesterType){this(actorId,actorType,sessionId,action,accountId,cif,statementId,purpose,caseRef,requesterId,requesterType,null,null,null,null);}
 }
 public record Decision(boolean allowed,String authorizationRef,String reasonCode){}
 public Decision decide(DecisionQuery q){
  var u=CurrentActor.get();if(!u.userId().equals(q.actorId()))return deny();
  String permission=q.action().contains("POLICY")||q.action().contains("NARRATION")||q.action().contains("MASK")?"STATEMENT_ADMIN":"STATEMENT_READ";
  if(q.accountId()==null)return new Decision(u.permissions().contains(permission),UUID.randomUUID().toString(),"PERMISSION_REQUIRED");
  var a=db.one("SELECT PRIMARY_CIF_ID,BRANCH_CODE,CURRENCY_CODE FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?",q.accountId());
  String cif=(String)a.get("PRIMARY_CIF_ID");
  boolean customer="CUSTOMER".equals(u.userType());
  if(customer){
   if(!u.userId().equals(q.requesterId())||!"CUSTOMER".equals(q.requesterType()))return deny();
   if(q.customerCifId()!=null&&(!u.cifIds().contains(q.customerCifId())||db.count("SELECT COUNT(*) FROM M04_ACCOUNT_PARTY WHERE ACCOUNT_ID=? AND CIF_ID=? AND IS_ACTIVE='Y' AND PARTY_ROLE IN ('PRIMARY_HOLDER','JOINT_HOLDER','AUTHORIZED_SIGNATORY') AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>SYSDATE)",q.accountId(),q.customerCifId())==0))return deny();
  }else if(q.customerCifId()!=null&&!cif.equals(q.customerCifId()))return deny();
  var input=new AuthorizationInput(permission,(String)a.get("BRANCH_CODE"),null,cif,null,(String)a.get("CURRENCY_CODE"),null,null,null,null);
  boolean allowed=customer?customerAccountAllowed(permission,q.accountId()):access.allowed(u,input);
  if("ISSUE".equals(q.action())&&!"CUSTOMER".equals(u.userType()))allowed&=u.permissions().contains("STATEMENT_ISSUE")||u.permissions().contains("STATEMENT_INTERNAL");
  if(!"SERVICE".equals(u.userType())){
   var attrs=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
   String hash=attrs instanceof org.springframework.web.context.request.ServletRequestAttributes request?request.getRequest().getHeader("X-Customer-Hash"):null;
   allowed&=hashes.permits(q.accountId(),hash);
  }
  // An external delivery address must be verified by an installed delivery adapter.
  if("DELIVERY".equals(q.action())&&!"DOWNLOAD".equals(q.deliveryChannel()))allowed=false;
  if(q.purposeCode()!=null)allowed&=db.count("SELECT COUNT(*) FROM M09_PRIVACY_PROCESSING_PURPOSE WHERE PURPOSE_CODE=? AND STATUS='ACTIVE'",q.purposeCode())>0;
  return new Decision(allowed,UUID.randomUUID().toString(),allowed?null:"STATEMENT_POLICY_DENIED");
 }
 private Decision deny(){return new Decision(false,UUID.randomUUID().toString(),"ACTOR_OR_OWNER_MISMATCH");}
 private boolean customerAccountAllowed(String permission,long accountId){
  try{banking.account(permission,accountId);return true;}
  catch(com.moneybags.common.api.BusinessException denied){return false;}
 }
}
