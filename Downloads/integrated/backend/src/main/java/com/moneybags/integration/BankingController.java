package com.moneybags.integration;
import org.springframework.web.bind.annotation.*;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.txn.core.LedgerService;
import java.util.*;
@RestController @RequestMapping("/api/v1/banking")
public class BankingController {
 private final BusinessRepository db;private final BankingAccess access;private final CustomerHashService hashes;private final LedgerService ledger;
 public BankingController(BusinessRepository db,BankingAccess access,CustomerHashService hashes,LedgerService ledger){this.db=db;this.access=access;this.hashes=hashes;this.ledger=ledger;}
 @GetMapping("/overview") public Map<String,Object> overview(){
  var u=CurrentActor.get();
  if("CUSTOMER".equals(u.userType()))return Map.of("mode","Customer banking","accounts",accounts().size(),"user",u.username());
  try{access.global("ACCOUNT_READ");}catch(com.moneybags.common.api.BusinessException e){return Map.of("mode","Role-scoped workspace","accounts",accounts().size(),"user",u.username());}
  return Map.of("mode","Bank operations","customers",db.count("SELECT COUNT(*) FROM M02_CIF_CUSTOMER"),"accounts",db.count("SELECT COUNT(*) FROM M04_BANK_ACCOUNT"),"transactions",db.count("SELECT COUNT(*) FROM M05_TXN_TRANSACTION_LOG WHERE STATUS='POSTED'"),"pendingPayments",db.count("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION WHERE STATUS NOT IN ('SETTLED','REJECTED','CANCELLED','REFUNDED')"),"pendingLoans",db.count("SELECT COUNT(*) FROM M08_LOAN_APPLICATION WHERE STATUS NOT IN ('CONVERTED','CANCELLED','REJECTED')"));
 }
 @GetMapping("/accounts") public List<Map<String,Object>> accounts(){
  var actor=CurrentActor.get();
  var rows="CUSTOMER".equals(actor.userType())
   ?db.rows("SELECT DISTINCT A.ACCOUNT_ID,A.ACCOUNT_NUMBER,A.PRIMARY_CIF_ID,A.BRANCH_CODE,A.PRODUCT_VERSION_ID,A.ACCOUNT_STATUS,A.CURRENCY_CODE,A.CREATED_BY_USER_ID FROM M04_BANK_ACCOUNT A JOIN M04_ACCOUNT_PARTY P ON P.ACCOUNT_ID=A.ACCOUNT_ID JOIN M01_IAM_CUSTOMER_LINK L ON L.CIF_ID=P.CIF_ID WHERE L.USER_ID=? AND L.STATUS='ACTIVE' AND L.VALID_FROM<=SYSTIMESTAMP AND (L.VALID_TO IS NULL OR L.VALID_TO>SYSTIMESTAMP) AND P.IS_ACTIVE='Y' AND P.PARTY_ROLE IN ('PRIMARY_HOLDER','JOINT_HOLDER','AUTHORIZED_SIGNATORY') AND P.EFFECTIVE_FROM<=SYSDATE AND (P.EFFECTIVE_TO IS NULL OR P.EFFECTIVE_TO>SYSDATE) ORDER BY A.ACCOUNT_ID FETCH FIRST 200 ROWS ONLY",actor.userId())
   :db.rows("SELECT ACCOUNT_ID,ACCOUNT_NUMBER,PRIMARY_CIF_ID,BRANCH_CODE,PRODUCT_VERSION_ID,ACCOUNT_STATUS,CURRENCY_CODE FROM M04_BANK_ACCOUNT ORDER BY ACCOUNT_ID FETCH FIRST 200 ROWS ONLY");
  return rows.stream().filter(r->{try{access.account("ACCOUNT_READ",((Number)r.get("ACCOUNT_ID")).longValue());return true;}catch(com.moneybags.common.api.BusinessException e){return false;}}).toList();
 }
 @GetMapping("/my-dashboard") public Map<String,Object> myDashboard(){
  var actor=CurrentActor.get();
  if(!"CUSTOMER".equals(actor.userType()))throw new com.moneybags.common.api.BusinessException(org.springframework.http.HttpStatus.FORBIDDEN,"CUSTOMER_ONLY","This view belongs to a signed-in customer");
  var owned=accounts();var result=new ArrayList<Map<String,Object>>();
  for(var a:owned.stream().limit(20).toList()){
   long id=((Number)a.get("ACCOUNT_ID")).longValue();String number=String.valueOf(a.get("ACCOUNT_NUMBER"));
   var item=new LinkedHashMap<String,Object>();
   item.put("accountId",id);item.put("accountEnding",number.substring(Math.max(0,number.length()-4)));
   item.put("status",a.get("ACCOUNT_STATUS"));item.put("currency",a.get("CURRENCY_CODE"));
   if(actor.permissions().contains("TXN_READ")){
    access.account("TXN_READ",id);var position=ledger.position(id);
    item.put("posted",position.posted());item.put("spendable",position.spendable());
    item.put("recentTransactions",transactions(id,null).stream().limit(5).toList());
   }
   result.add(item);
  }
  hashes.audit("CUSTOMER_DASHBOARD_VIEWED","USER",actor.userId(),"AUTHENTICATED_CUSTOMER");
  return Map.of("accounts",result,"truncated",owned.size()>20);
 }
 @GetMapping("/transactions") public List<Map<String,Object>> transactions(@RequestParam(required=false) Long accountId,@RequestHeader(value="X-Customer-Hash",required=false)String hash){
  CurrentActor.require("TXN_READ");
  if(accountId==null)throw new com.moneybags.common.api.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"ACCOUNT_REQUIRED","Choose an account for this transaction list");
  access.account("TXN_READ",accountId);
  var rows=db.rows("SELECT TXN_ID,SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID,VALUE_DATE,CREATED_AT FROM M05_TXN_TRANSACTION_LOG WHERE SOURCE_ACCOUNT_ID=? OR TARGET_ACCOUNT_ID=? ORDER BY TXN_ID DESC FETCH FIRST 100 ROWS ONLY",accountId,accountId);
  var result=new ArrayList<Map<String,Object>>();
  for(var row:rows){long id=((Number)row.get("TXN_ID")).longValue();if(hashes.permits(accountId,hash)){
   var t=ledger.transaction(id);var item=new LinkedHashMap<String,Object>();
   item.put("transactionId",id);item.put("status",t.status());item.put("type",t.type());
   item.put("amount",t.amount());item.put("currency",t.currency());
   item.put("valueDate",row.get("VALUE_DATE"));item.put("createdAt",row.get("CREATED_AT"));
   item.put("accountRole",row.get("SOURCE_ACCOUNT_ID") instanceof Number source&&source.longValue()==accountId?"SOURCE":"TARGET");
   result.add(item);
  }else result.add(Map.of("transactionId",id));}
  if(hashes.permits(accountId,hash))hashes.audit("TRANSACTION_LIST_VIEWED","ACCOUNT",accountId.toString(),hashes.proofLabel());
  return result;
 }
 @GetMapping("/gl-accounts") public List<Map<String,Object>> gl(){access.global("GL_ADMIN");return db.rows("SELECT GL_ACCOUNT_ID,GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE FROM M05_GL_ACCOUNT ORDER BY GL_CODE");}
 @GetMapping("/audit") public List<Map<String,Object>> audit(){access.global("IAM_AUDIT_READ");return db.rows("SELECT * FROM MBX_AUDIT ORDER BY OCCURRED_AT DESC FETCH FIRST 100 ROWS ONLY");}
 @GetMapping("/facilities") public List<Map<String,Object>> facilities(){return db.rows("SELECT FACILITY_ID,FACILITY_NUMBER,PRIMARY_CIF_ID,BRANCH_CODE,STATUS FROM M08_LOAN_FACILITY ORDER BY FACILITY_ID DESC FETCH FIRST 100 ROWS ONLY").stream().filter(f->{try{access.facility("LOAN_READ",((Number)f.get("FACILITY_ID")).longValue());return true;}catch(com.moneybags.common.api.BusinessException e){return false;}}).toList();}
}
