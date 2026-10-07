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
    item.put("recentTransactions",transactions(id,null,null).stream().limit(5).toList());
   }
   result.add(item);
  }
  hashes.audit("CUSTOMER_DASHBOARD_VIEWED","USER",actor.userId(),"AUTHENTICATED_CUSTOMER");
  return Map.of("accounts",result,"truncated",owned.size()>20);
 }
 private long accountId(Long accountId,String accountNumber){
  if((accountId==null)==(accountNumber==null||accountNumber.isBlank()))throw new com.moneybags.common.api.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"ACCOUNT_REQUIRED","Enter one account number");
  if(accountId!=null)return accountId;
  String number=accountNumber.trim();
  if(number.length()>20)throw new com.moneybags.common.api.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_ACCOUNT_NUMBER","Account number is too long");
  return ((Number)db.one("SELECT ACCOUNT_ID FROM M04_BANK_ACCOUNT WHERE ACCOUNT_NUMBER=?",number).get("ACCOUNT_ID")).longValue();
 }
 private boolean transactionReadAllowed(long accountId,String hash){return access.bankAdminTransactionRead()||hashes.permits(accountId,hash);}
 @GetMapping("/transactions") public List<Map<String,Object>> transactions(@RequestParam(required=false) Long accountId,@RequestParam(required=false) String accountNumber,@RequestHeader(value="X-Customer-Hash",required=false)String hash){
  CurrentActor.require("TXN_READ");
  accountId=accountId(accountId,accountNumber);
  access.account("TXN_READ",accountId);
  boolean canRead=transactionReadAllowed(accountId,hash);
  var rows=db.rows("SELECT TXN_ID,SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID,TXN_TYPE,STATUS,AMOUNT,CURRENCY_CODE,VALUE_DATE,CREATED_AT FROM M05_TXN_TRANSACTION_LOG WHERE SOURCE_ACCOUNT_ID=? OR TARGET_ACCOUNT_ID=? ORDER BY TXN_ID DESC FETCH FIRST 100 ROWS ONLY",accountId,accountId);
  var result=new ArrayList<Map<String,Object>>();
  for(var row:rows){long id=((Number)row.get("TXN_ID")).longValue();if(canRead){
   var item=new LinkedHashMap<String,Object>();
   item.put("transactionId",id);item.put("status",row.get("STATUS"));item.put("type",row.get("TXN_TYPE"));
   item.put("amount",row.get("AMOUNT"));item.put("currency",row.get("CURRENCY_CODE"));
   item.put("valueDate",row.get("VALUE_DATE"));item.put("createdAt",row.get("CREATED_AT"));
   item.put("accountRole",row.get("SOURCE_ACCOUNT_ID") instanceof Number source&&source.longValue()==accountId?"SOURCE":"TARGET");
   result.add(item);
  }else result.add(Map.of("transactionId",id));}
  if(canRead)hashes.audit("TRANSACTION_LIST_VIEWED","ACCOUNT",accountId.toString(),access.bankAdminTransactionRead()?"BANK_ADMIN":hashes.proofLabel());
  return result;
 }
 /** Identifies active holders who can generate a new key; never returns a key or its digest. */
 @GetMapping("/accounts/{id}/key-holders") public List<Map<String,Object>> keyHolders(@PathVariable long id){
  if("CUSTOMER".equals(CurrentActor.get().userType()))throw new com.moneybags.common.api.BusinessException(org.springframework.http.HttpStatus.FORBIDDEN,"STAFF_ONLY","This lookup is for authorized bank staff");
  CurrentActor.require("CIF_READ");
  access.account("ACCOUNT_READ",id);
  var holders=db.rows("""
    SELECT P.CIF_ID, P.PARTY_ROLE,
      (SELECT MAX(N.FULL_NAME) FROM M02_CIF_CUSTOMER C JOIN M02_CIF_NAME N ON N.PARTY_ID=C.PARTY_ID
        WHERE C.CIF_ID=P.CIF_ID AND N.NAME_TYPE='LEGAL' AND N.VALID_FROM<=SYSTIMESTAMP
          AND (N.VALID_TO IS NULL OR N.VALID_TO>SYSTIMESTAMP)) AS HOLDER_NAME,
      CASE WHEN EXISTS (
        SELECT 1 FROM M01_IAM_CUSTOMER_LINK L JOIN M01_IAM_USER U ON U.USER_ID=L.USER_ID
        WHERE L.CIF_ID=P.CIF_ID AND L.STATUS='ACTIVE' AND U.STATUS='ACTIVE' AND U.USER_TYPE='CUSTOMER'
          AND L.VALID_FROM<=SYSTIMESTAMP AND (L.VALID_TO IS NULL OR L.VALID_TO>SYSTIMESTAMP)
      ) THEN 'Y' ELSE 'N' END AS CAN_SIGN_IN
    FROM M04_ACCOUNT_PARTY P
    WHERE P.ACCOUNT_ID=? AND P.IS_ACTIVE='Y'
      AND P.PARTY_ROLE IN ('PRIMARY_HOLDER','JOINT_HOLDER','AUTHORIZED_SIGNATORY')
      AND P.EFFECTIVE_FROM<=SYSDATE AND (P.EFFECTIVE_TO IS NULL OR P.EFFECTIVE_TO>SYSDATE)
    ORDER BY P.PARTY_ROLE, P.CIF_ID
    """,id);
  hashes.audit("ACCOUNT_KEY_HOLDERS_VIEWED","ACCOUNT",Long.toString(id),"ACCOUNT_READ_AND_CIF_READ");
  return holders;
 }
 @GetMapping("/accounts/by-number/{number}/key-holders") public List<Map<String,Object>> keyHoldersByNumber(@PathVariable String number){
  return keyHolders(accountId(null,number));
 }
 /** Account-scoped financial detail; the account-holder proof is checked before any detail is returned. */
 @GetMapping("/transactions/{id}/details") public Map<String,Object> transactionDetails(@PathVariable long id,@RequestParam(required=false) Long accountId,@RequestParam(required=false) String accountNumber,@RequestHeader(value="X-Customer-Hash",required=false)String hash){
  CurrentActor.require("TXN_READ");
  Map<String,Object> t;
  if(accountId==null&&(accountNumber==null||accountNumber.isBlank())){
   access.global("GL_RECONCILE");
   t=db.one("SELECT TXN_ID,SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID,TXN_TYPE,STATUS,AMOUNT,CURRENCY_CODE,VALUE_DATE,FAILURE_CODE,REVERSAL_OF_TXN_ID,CREATED_AT,UPDATED_AT FROM M05_TXN_TRANSACTION_LOG T WHERE TXN_ID=? AND SOURCE_ACCOUNT_ID IS NULL AND TARGET_ACCOUNT_ID IS NULL AND LOAN_FACILITY_ID IS NULL AND NOT EXISTS (SELECT 1 FROM M05_GL_JOURNAL J JOIN M05_GL_POSTING P ON P.JOURNAL_ID=J.JOURNAL_ID WHERE J.TXN_ID=T.TXN_ID AND (P.BANK_ACCOUNT_ID IS NOT NULL OR P.LOAN_FACILITY_ID IS NOT NULL))",id);
  }else{
   accountId=accountId(accountId,accountNumber);
   access.account("TXN_READ",accountId);
   if(!transactionReadAllowed(accountId,hash))hashes.require(accountId,hash);
   t=db.one("SELECT TXN_ID,SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID,TXN_TYPE,STATUS,AMOUNT,CURRENCY_CODE,VALUE_DATE,FAILURE_CODE,REVERSAL_OF_TXN_ID,CREATED_AT,UPDATED_AT FROM M05_TXN_TRANSACTION_LOG WHERE TXN_ID=? AND (SOURCE_ACCOUNT_ID=? OR TARGET_ACCOUNT_ID=?)",id,accountId,accountId);
  }
  var detail=new LinkedHashMap<String,Object>();
  detail.put("transactionId",id);
  detail.put("accountId",accountId);
  detail.put("accountRole",accountId==null?"INTERNAL":t.get("SOURCE_ACCOUNT_ID") instanceof Number source&&source.longValue()==accountId?"SOURCE":"TARGET");
  for(String key:List.of("TXN_TYPE","STATUS","AMOUNT","CURRENCY_CODE","VALUE_DATE","FAILURE_CODE","REVERSAL_OF_TXN_ID","CREATED_AT","UPDATED_AT"))detail.put(key,t.get(key));
  detail.put("statusHistory",db.rows("SELECT FROM_STATUS,TO_STATUS,REASON_CODE,CHANGED_AT FROM M05_TXN_STATUS_HISTORY WHERE TXN_ID=? ORDER BY CHANGED_AT,TXN_STATUS_HISTORY_ID",id));
  var journals=new ArrayList<Map<String,Object>>();
  for(var journal:db.rows("SELECT JOURNAL_ID,JOURNAL_TYPE,BOOKED_AT,VALUE_DATE,REVERSAL_OF_JOURNAL_ID FROM M05_GL_JOURNAL WHERE TXN_ID=? ORDER BY JOURNAL_ID",id)){
   long journalId=((Number)journal.get("JOURNAL_ID")).longValue();
   var item=new LinkedHashMap<String,Object>(journal);
   item.put("control",db.one("SELECT LINE_COUNT,DEBIT_TOTAL,CREDIT_TOTAL,BALANCE_DIFFERENCE,IS_BALANCED FROM M05_V_GL_JOURNAL_CONTROL WHERE JOURNAL_ID=?",journalId));
   item.put("accountPostings",accountId==null?db.rows("SELECT POSTING_ID,LINE_NO,GL_ACCOUNT_ID,ENTRY_SIDE,AMOUNT FROM M05_GL_POSTING WHERE JOURNAL_ID=? ORDER BY LINE_NO",journalId):db.rows("SELECT POSTING_ID,LINE_NO,GL_ACCOUNT_ID,ENTRY_SIDE,AMOUNT FROM M05_GL_POSTING WHERE JOURNAL_ID=? AND BANK_ACCOUNT_ID=? ORDER BY LINE_NO",journalId,accountId));
   journals.add(item);
  }
  detail.put("journals",journals);
  hashes.audit("TRANSACTION_DETAIL_VIEWED","TRANSACTION",Long.toString(id),accountId==null?"GL_RECONCILE":access.bankAdminTransactionRead()?"BANK_ADMIN":hashes.proofLabel());
  return detail;
 }
 @GetMapping("/gl-accounts") public List<Map<String,Object>> gl(){try{access.global("GL_READ");}catch(com.moneybags.common.api.BusinessException denied){access.global("GL_ADMIN");}return db.rows("SELECT GL_ACCOUNT_ID,GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE FROM M05_GL_ACCOUNT ORDER BY GL_CODE");}
 @GetMapping("/audit") public List<Map<String,Object>> audit(){access.global("IAM_AUDIT_READ");return db.rows("SELECT * FROM MBX_AUDIT ORDER BY OCCURRED_AT DESC FETCH FIRST 100 ROWS ONLY");}
 @GetMapping("/facilities") public List<Map<String,Object>> facilities(){return db.rows("SELECT FACILITY_ID,FACILITY_NUMBER,PRIMARY_CIF_ID,BRANCH_CODE,STATUS FROM M08_LOAN_FACILITY ORDER BY FACILITY_ID DESC FETCH FIRST 100 ROWS ONLY").stream().filter(f->{try{access.facility("LOAN_READ",((Number)f.get("FACILITY_ID")).longValue());return true;}catch(com.moneybags.common.api.BusinessException e){return false;}}).toList();}
}
