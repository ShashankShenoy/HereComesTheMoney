package com.moneybags.integration;
import org.springframework.web.bind.annotation.*;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.txn.core.LedgerService;
import org.springframework.http.HttpStatus;
import java.util.*;
@RestController @RequestMapping("/api/v1/banking")
public class BankingController {
 private final BusinessRepository db;private final BankingAccess access;private final CustomerHashService hashes;private final LedgerService ledger;
 public BankingController(BusinessRepository db,BankingAccess access,CustomerHashService hashes,LedgerService ledger){this.db=db;this.access=access;this.hashes=hashes;this.ledger=ledger;}
 @GetMapping("/branches") public List<Map<String,Object>> branches(){
  var actor=CurrentActor.get();
  if("CUSTOMER".equals(actor.userType()))return accounts().stream().map(a->Map.<String,Object>of("BRANCH_CODE",a.get("BRANCH_CODE"))).distinct().toList();
  var permissions=List.of("ACCOUNT_READ","LOAN_READ","TELLER_READ","TELLER_OPERATE","CIF_READ","PRODUCT_READ");
  if(permissions.stream().noneMatch(actor.permissions()::contains))throw new com.moneybags.common.api.BusinessException(HttpStatus.FORBIDDEN,"PERMISSION_REQUIRED","Branch access required");
  return db.rows("SELECT BRANCH_CODE FROM M04_BANK_ACCOUNT UNION SELECT HOME_BRANCH_REF AS BRANCH_CODE FROM M02_CIF_CUSTOMER UNION SELECT BRANCH_CODE FROM M08_LOAN_APPLICATION UNION SELECT BRANCH_CODE FROM MBX_BRANCH_VAULT ORDER BY BRANCH_CODE").stream()
   .filter(row->row.get("BRANCH_CODE")!=null&&permissions.stream().filter(actor.permissions()::contains).anyMatch(permission->{try{access.branch(permission,(String)row.get("BRANCH_CODE"));return true;}catch(com.moneybags.common.api.BusinessException denied){return false;}})).toList();
 }
 @GetMapping("/overview") public Map<String,Object> overview(){
  var u=CurrentActor.get();
  if("CUSTOMER".equals(u.userType()))return Map.of("mode","Customer banking","accounts",accounts().size(),"user",u.username());
  try{access.global("ACCOUNT_READ");}catch(com.moneybags.common.api.BusinessException e){return Map.of("mode","Role-scoped workspace","accounts",accounts().size(),"user",u.username());}
  return Map.of("mode","Bank operations","customers",db.count("SELECT COUNT(*) FROM M02_CIF_CUSTOMER"),"accounts",db.count("SELECT COUNT(*) FROM M04_BANK_ACCOUNT"),"transactions",db.count("SELECT COUNT(*) FROM M05_TXN_TRANSACTION_LOG WHERE STATUS='POSTED'"),"pendingPayments",db.count("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION WHERE STATUS NOT IN ('SETTLED','REJECTED','CANCELLED','REFUNDED')"),"pendingLoans",db.count("SELECT COUNT(*) FROM M08_LOAN_APPLICATION WHERE STATUS NOT IN ('CONVERTED','CANCELLED','REJECTED')"));
 }
 @GetMapping("/accounts") public List<Map<String,Object>> accounts(){
  var actor=CurrentActor.get();
  boolean customer="CUSTOMER".equals(actor.userType());
  String sql=customer
   ?"SELECT DISTINCT A.ACCOUNT_ID,A.ACCOUNT_NUMBER,A.PRIMARY_CIF_ID,A.BRANCH_CODE,A.PRODUCT_VERSION_ID,A.ACCOUNT_STATUS,A.ACCOUNT_OPERATION_MODE,A.CURRENCY_CODE,A.CREATED_BY_USER_ID,R.PRODUCT_TYPE,R.PRODUCT_NAME FROM M04_BANK_ACCOUNT A JOIN M03_PM_PRODUCT R ON R.PRODUCT_ID=A.PRODUCT_ID JOIN M04_ACCOUNT_PARTY P ON P.ACCOUNT_ID=A.ACCOUNT_ID JOIN M01_IAM_CUSTOMER_LINK L ON L.CIF_ID=P.CIF_ID WHERE L.USER_ID=? AND L.STATUS='ACTIVE' AND L.VALID_FROM<=SYSTIMESTAMP AND (L.VALID_TO IS NULL OR L.VALID_TO>SYSTIMESTAMP) AND P.IS_ACTIVE='Y' AND P.PARTY_ROLE IN ('PRIMARY_HOLDER','JOINT_HOLDER','AUTHORIZED_SIGNATORY','GUARDIAN') AND P.EFFECTIVE_FROM<=SYSDATE AND (P.EFFECTIVE_TO IS NULL OR P.EFFECTIVE_TO>SYSDATE) AND A.ACCOUNT_ID>? ORDER BY A.ACCOUNT_ID FETCH FIRST 200 ROWS ONLY"
   :"SELECT A.ACCOUNT_ID,A.ACCOUNT_NUMBER,A.PRIMARY_CIF_ID,A.BRANCH_CODE,A.PRODUCT_VERSION_ID,A.ACCOUNT_STATUS,A.CURRENCY_CODE,R.PRODUCT_TYPE,R.PRODUCT_NAME FROM M04_BANK_ACCOUNT A JOIN M03_PM_PRODUCT R ON R.PRODUCT_ID=A.PRODUCT_ID WHERE A.ACCOUNT_ID>? ORDER BY A.ACCOUNT_ID FETCH FIRST 200 ROWS ONLY";
  var authorized=new ArrayList<Map<String,Object>>();long afterId=0;
  while(authorized.size()<200){
   var rows=customer?db.rows(sql,actor.userId(),afterId):db.rows(sql,afterId);
   if(rows.isEmpty())break;
   for(var row:rows){
    long id=((Number)row.get("ACCOUNT_ID")).longValue();afterId=id;
    try{access.account("ACCOUNT_READ",id);authorized.add(row);}catch(com.moneybags.common.api.BusinessException denied){/* continue to the next scoped account */}
    if(authorized.size()==200)break;
   }
   if(rows.size()<200)break;
  }
  return authorized;
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
 /** Account-facing amount and other-party name come from posted entries and the verified name effective when the transfer was made. */
 private static final String ACCOUNT_TRANSACTION_SELECT="""
   SELECT T.TXN_ID,T.SOURCE_ACCOUNT_ID,T.TARGET_ACCOUNT_ID,T.TXN_TYPE,T.STATUS,T.AMOUNT,T.CURRENCY_CODE,
          T.VALUE_DATE,T.FAILURE_CODE,T.REVERSAL_OF_TXN_ID,T.CREATED_AT,T.UPDATED_AT,
          (SELECT SUM(CASE WHEN P.ENTRY_SIDE='CR' THEN P.AMOUNT ELSE -P.AMOUNT END)
             FROM M05_GL_JOURNAL J JOIN M05_GL_POSTING P ON P.JOURNAL_ID=J.JOURNAL_ID
            WHERE J.TXN_ID=T.TXN_ID AND P.BANK_ACCOUNT_ID=?) AS POSTED_DELTA,
          (SELECT MAX(N.FULL_NAME)
             FROM M04_BANK_ACCOUNT A JOIN M02_CIF_CUSTOMER C ON C.CIF_ID=A.PRIMARY_CIF_ID
             JOIN M02_CIF_NAME N ON N.PARTY_ID=C.PARTY_ID
            WHERE A.ACCOUNT_ID=CASE WHEN T.SOURCE_ACCOUNT_ID=? THEN T.TARGET_ACCOUNT_ID ELSE T.SOURCE_ACCOUNT_ID END
              AND T.TXN_TYPE IN ('INTERNAL_TRANSFER','REVERSAL')
              AND N.NAME_TYPE='LEGAL' AND N.VERIFICATION_STATUS='VERIFIED'
              AND N.VALID_FROM<=T.CREATED_AT AND (N.VALID_TO IS NULL OR N.VALID_TO>T.CREATED_AT)) AS OTHER_PARTY_NAME
     FROM M05_TXN_TRANSACTION_LOG T
   """;
 @GetMapping("/transactions") public List<Map<String,Object>> transactions(@RequestParam(required=false) Long accountId,@RequestParam(required=false) String accountNumber,@RequestHeader(value="X-Customer-Hash",required=false)String hash){
  CurrentActor.require("TXN_READ");
  accountId=accountId(accountId,accountNumber);
  access.account("TXN_READ",accountId);
  boolean canRead=transactionReadAllowed(accountId,hash);
  var rows=canRead
   ?db.rows(ACCOUNT_TRANSACTION_SELECT+" WHERE T.SOURCE_ACCOUNT_ID=? OR T.TARGET_ACCOUNT_ID=? ORDER BY T.TXN_ID DESC FETCH FIRST 100 ROWS ONLY",accountId,accountId,accountId,accountId)
   :db.rows("SELECT TXN_ID FROM M05_TXN_TRANSACTION_LOG WHERE SOURCE_ACCOUNT_ID=? OR TARGET_ACCOUNT_ID=? ORDER BY TXN_ID DESC FETCH FIRST 100 ROWS ONLY",accountId,accountId);
  var result=new ArrayList<Map<String,Object>>();
  for(var row:rows){long id=((Number)row.get("TXN_ID")).longValue();if(canRead){
   var item=new LinkedHashMap<String,Object>();
   item.put("transactionId",id);item.put("status",row.get("STATUS"));item.put("type",row.get("TXN_TYPE"));
   item.put("amount",row.get("AMOUNT"));item.put("currency",row.get("CURRENCY_CODE"));
   item.put("valueDate",row.get("VALUE_DATE"));item.put("createdAt",row.get("CREATED_AT"));
   item.put("accountRole",row.get("SOURCE_ACCOUNT_ID") instanceof Number source&&source.longValue()==accountId?"SOURCE":"TARGET");
   item.put("otherPartyName",row.get("OTHER_PARTY_NAME"));item.put("postedDelta",row.get("POSTED_DELTA"));
   result.add(item);
  }else result.add(Map.of("transactionId",id));}
  // Payments post against PAYMENT_ID rather than TXN_ID. Include their actual
  // customer ledger entries, including historical entries, without posting again.
  for(var row:db.rows("""
    SELECT J.JOURNAL_ID,J.PAYMENT_ID,J.JOURNAL_TYPE,J.VALUE_DATE,J.BOOKED_AT,
      I.RAIL_CODE,I.STATUS AS PAYMENT_STATUS,
      SUM(CASE WHEN P.ENTRY_SIDE='CR' THEN P.AMOUNT ELSE -P.AMOUNT END) AS NET_AMOUNT
    FROM M05_GL_JOURNAL J JOIN M05_GL_POSTING P ON P.JOURNAL_ID=J.JOURNAL_ID
    JOIN M06_PAYMENT_INSTRUCTION I ON I.PAYMENT_ID=J.PAYMENT_ID
    WHERE P.BANK_ACCOUNT_ID=? AND J.TXN_ID IS NULL
      AND J.JOURNAL_TYPE IN ('PAYMENT_PROVISIONAL','PAYMENT_REFUND','RETURN')
    GROUP BY J.JOURNAL_ID,J.PAYMENT_ID,J.JOURNAL_TYPE,J.VALUE_DATE,J.BOOKED_AT,I.RAIL_CODE,I.STATUS
    ORDER BY J.BOOKED_AT DESC,J.JOURNAL_ID DESC FETCH FIRST 100 ROWS ONLY
    """,accountId)){
   String reference="PAYMENT-"+row.get("JOURNAL_ID");
   if(!canRead){result.add(Map.of("transactionId",reference));continue;}
   var net=(java.math.BigDecimal)row.get("NET_AMOUNT");
   if(net.signum()==0)continue;
   var item=new LinkedHashMap<String,Object>();
   item.put("transactionId",reference);item.put("paymentId",row.get("PAYMENT_ID"));
   item.put("journalId",row.get("JOURNAL_ID"));item.put("status","POSTED");
   item.put("paymentStatus",row.get("PAYMENT_STATUS"));item.put("railCode",row.get("RAIL_CODE"));
   item.put("type",row.get("JOURNAL_TYPE").equals("PAYMENT_REFUND")?"PAYMENT_REFUND":net.signum()<0?"EXTERNAL_PAYMENT_SENT":"EXTERNAL_PAYMENT_RECEIVED");
   item.put("amount",net.abs());item.put("currency","INR");
   item.put("postedDelta",net);
   item.put("valueDate",row.get("VALUE_DATE"));item.put("createdAt",row.get("BOOKED_AT"));
   item.put("accountRole",net.signum()<0?"SOURCE":"TARGET");result.add(item);
  }
  if(canRead)result.sort(Comparator.comparing((Map<String,Object> item)->activityTime(item.get("createdAt"))).reversed());
  if(canRead)hashes.audit("TRANSACTION_LIST_VIEWED","ACCOUNT",accountId.toString(),access.bankAdminTransactionRead()?"BANK_ADMIN":hashes.proofLabel());
  return result.stream().limit(100).toList();
 }
 public static java.time.Instant activityTime(Object time){
  if(time instanceof java.time.OffsetDateTime t)return t.toInstant();
  if(time instanceof java.time.LocalDateTime t)return t.atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
  return java.time.Instant.MIN;
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
 @GetMapping("/transactions/{id}/details") public Map<String,Object> activityDetails(@PathVariable String id,@RequestParam(required=false) Long accountId,@RequestParam(required=false) String accountNumber,@RequestHeader(value="X-Customer-Hash",required=false)String hash){
  if(id.matches("PAYMENT-[0-9]{1,18}"))return paymentActivityDetails(Long.parseLong(id.substring(8)),accountId,accountNumber,hash);
  if(!id.matches("[0-9]{1,18}"))throw new com.moneybags.common.api.BusinessException(HttpStatus.BAD_REQUEST,"INVALID_TRANSACTION_REFERENCE","Select a transaction from the account activity list");
  return transactionDetails(Long.parseLong(id),accountId,accountNumber,hash);
 }
 private Map<String,Object> paymentActivityDetails(long journalId,Long accountId,String accountNumber,String hash){
  CurrentActor.require("TXN_READ");accountId=accountId(accountId,accountNumber);
  access.account("TXN_READ",accountId);if(!transactionReadAllowed(accountId,hash))hashes.require(accountId,hash);
  var row=db.one("""
    SELECT J.JOURNAL_ID,J.PAYMENT_ID,J.JOURNAL_TYPE,J.VALUE_DATE,J.BOOKED_AT,
      I.RAIL_CODE,I.STATUS AS PAYMENT_STATUS,
      SUM(CASE WHEN P.ENTRY_SIDE='CR' THEN P.AMOUNT ELSE -P.AMOUNT END) AS NET_AMOUNT
    FROM M05_GL_JOURNAL J JOIN M05_GL_POSTING P ON P.JOURNAL_ID=J.JOURNAL_ID
    JOIN M06_PAYMENT_INSTRUCTION I ON I.PAYMENT_ID=J.PAYMENT_ID
    WHERE J.JOURNAL_ID=? AND P.BANK_ACCOUNT_ID=? AND J.TXN_ID IS NULL
      AND J.JOURNAL_TYPE IN ('PAYMENT_PROVISIONAL','PAYMENT_REFUND','RETURN')
    GROUP BY J.JOURNAL_ID,J.PAYMENT_ID,J.JOURNAL_TYPE,J.VALUE_DATE,J.BOOKED_AT,I.RAIL_CODE,I.STATUS
    """,journalId,accountId);
  var net=(java.math.BigDecimal)row.get("NET_AMOUNT");var detail=new LinkedHashMap<String,Object>();
  detail.put("transactionId","PAYMENT-"+journalId);detail.put("accountId",accountId);
  detail.put("accountRole",net.signum()<0?"SOURCE":"TARGET");detail.put("STATUS","POSTED");
  detail.put("TXN_TYPE",row.get("JOURNAL_TYPE").equals("PAYMENT_REFUND")?"PAYMENT_REFUND":net.signum()<0?"EXTERNAL_PAYMENT_SENT":"EXTERNAL_PAYMENT_RECEIVED");
  detail.put("AMOUNT",net.abs());detail.put("CURRENCY_CODE","INR");
  detail.put("postedDelta",net);
  detail.put("VALUE_DATE",row.get("VALUE_DATE"));detail.put("CREATED_AT",row.get("BOOKED_AT"));
  detail.put("PAYMENT_ID",row.get("PAYMENT_ID"));detail.put("RAIL_CODE",row.get("RAIL_CODE"));
  detail.put("PAYMENT_STATUS",row.get("PAYMENT_STATUS"));
  detail.put("statusHistory",db.rows("SELECT FROM_STATUS,TO_STATUS,REASON_CODE,CHANGED_AT FROM M06_PAYMENT_STATUS_HISTORY WHERE PAYMENT_ID=? ORDER BY CHANGED_AT,PAYMENT_STATUS_HISTORY_ID",row.get("PAYMENT_ID")));
  var journal=new LinkedHashMap<String,Object>();journal.put("JOURNAL_ID",journalId);journal.put("JOURNAL_TYPE",row.get("JOURNAL_TYPE"));journal.put("BOOKED_AT",row.get("BOOKED_AT"));
  journal.put("control",db.one("SELECT LINE_COUNT,DEBIT_TOTAL,CREDIT_TOTAL,BALANCE_DIFFERENCE,IS_BALANCED FROM M05_V_GL_JOURNAL_CONTROL WHERE JOURNAL_ID=?",journalId));
  journal.put("accountPostings",db.rows("SELECT POSTING_ID,LINE_NO,GL_ACCOUNT_ID,ENTRY_SIDE,AMOUNT FROM M05_GL_POSTING WHERE JOURNAL_ID=? AND BANK_ACCOUNT_ID=? ORDER BY LINE_NO",journalId,accountId));
  detail.put("journals",List.of(journal));
  hashes.audit("PAYMENT_ACTIVITY_VIEWED","ACCOUNT",accountId.toString(),access.bankAdminTransactionRead()?"BANK_ADMIN":hashes.proofLabel());return detail;
 }
 public Map<String,Object> transactionDetails(long id,Long accountId,String accountNumber,String hash){
  CurrentActor.require("TXN_READ");
  Map<String,Object> t;
  if(accountId==null&&(accountNumber==null||accountNumber.isBlank())){
   access.global("GL_RECONCILE");
   t=db.one("SELECT TXN_ID,SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID,TXN_TYPE,STATUS,AMOUNT,CURRENCY_CODE,VALUE_DATE,FAILURE_CODE,REVERSAL_OF_TXN_ID,CREATED_AT,UPDATED_AT FROM M05_TXN_TRANSACTION_LOG T WHERE TXN_ID=? AND SOURCE_ACCOUNT_ID IS NULL AND TARGET_ACCOUNT_ID IS NULL AND LOAN_FACILITY_ID IS NULL AND NOT EXISTS (SELECT 1 FROM M05_GL_JOURNAL J JOIN M05_GL_POSTING P ON P.JOURNAL_ID=J.JOURNAL_ID WHERE J.TXN_ID=T.TXN_ID AND (P.BANK_ACCOUNT_ID IS NOT NULL OR P.LOAN_FACILITY_ID IS NOT NULL))",id);
  }else{
   accountId=accountId(accountId,accountNumber);
   access.account("TXN_READ",accountId);
   if(!transactionReadAllowed(accountId,hash))hashes.require(accountId,hash);
   t=db.one(ACCOUNT_TRANSACTION_SELECT+" WHERE T.TXN_ID=? AND (T.SOURCE_ACCOUNT_ID=? OR T.TARGET_ACCOUNT_ID=?)",accountId,accountId,id,accountId,accountId);
  }
  var detail=new LinkedHashMap<String,Object>();
  detail.put("transactionId",id);
  detail.put("accountId",accountId);
  detail.put("accountRole",accountId==null?"INTERNAL":t.get("SOURCE_ACCOUNT_ID") instanceof Number source&&source.longValue()==accountId?"SOURCE":"TARGET");
  detail.put("otherPartyName",t.get("OTHER_PARTY_NAME"));detail.put("postedDelta",t.get("POSTED_DELTA"));
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
 @GetMapping("/gl-accounts") public List<Map<String,Object>> gl(){try{access.global("GL_READ");}catch(com.moneybags.common.api.BusinessException denied){try{access.global("GL_RECONCILE");}catch(com.moneybags.common.api.BusinessException reconcileDenied){access.global("GL_ADMIN");}}return db.rows("SELECT GL_ACCOUNT_ID,GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE,CURRENCY_CODE,ACTIVE_FLAG FROM M05_GL_ACCOUNT ORDER BY GL_CODE");}
 private void requireGlLedgerAccess(){
  if(!"EMPLOYEE".equals(CurrentActor.get().userType()))throw new com.moneybags.common.api.BusinessException(HttpStatus.FORBIDDEN,"STAFF_ONLY","The GL register is for authorized bank staff");
  access.global("GL_RECONCILE");
 }
 /** Staff-only GL register. The running balance includes earlier postings even on later pages. */
 @GetMapping("/gl-accounts/{id}/ledger") public Map<String,Object> glLedger(@PathVariable long id,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset){
  requireGlLedgerAccess();
  if(limit<1||limit>100||offset<0)throw new com.moneybags.common.api.BusinessException(HttpStatus.BAD_REQUEST,"INVALID_PAGE","Use a page size from 1 to 100 and a nonnegative offset");
  var account=db.one("SELECT GL_ACCOUNT_ID,GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE,CURRENCY_CODE,ACTIVE_FLAG FROM M05_GL_ACCOUNT WHERE GL_ACCOUNT_ID=?",id);
  long total=db.count("SELECT COUNT(*) FROM M05_GL_POSTING WHERE GL_ACCOUNT_ID=?",id);
  var postings=db.rows("""
    SELECT * FROM (
      SELECT P.POSTING_ID,P.JOURNAL_ID,P.LINE_NO,P.ENTRY_SIDE,P.AMOUNT,P.NARRATIVE,
             J.BOOKED_AT,J.VALUE_DATE,J.JOURNAL_TYPE,J.DESCRIPTION AS JOURNAL_DESCRIPTION,
             J.TXN_ID,J.PAYMENT_ID,J.SETTLEMENT_CYCLE_ID,J.LOAN_FACILITY_ID,
             SUM(CASE WHEN P.ENTRY_SIDE=? THEN P.AMOUNT ELSE -P.AMOUNT END)
               OVER (ORDER BY J.BOOKED_AT,J.JOURNAL_ID,P.LINE_NO,P.POSTING_ID
                     ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS RUNNING_BALANCE
        FROM M05_GL_POSTING P JOIN M05_GL_JOURNAL J ON J.JOURNAL_ID=P.JOURNAL_ID
       WHERE P.GL_ACCOUNT_ID=?
    ) H ORDER BY H.BOOKED_AT DESC,H.JOURNAL_ID DESC,H.LINE_NO DESC,H.POSTING_ID DESC
    OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
    """,account.get("NORMAL_SIDE"),id,offset,limit);
  var balance=db.jdbc().queryForObject("SELECT COALESCE(SUM(CASE WHEN ENTRY_SIDE=? THEN AMOUNT ELSE -AMOUNT END),0) FROM M05_GL_POSTING WHERE GL_ACCOUNT_ID=?",java.math.BigDecimal.class,account.get("NORMAL_SIDE"),id);
  hashes.audit("GL_LEDGER_VIEWED","GL_ACCOUNT",Long.toString(id),"GL_RECONCILE");
  return Map.of("account",account,"balance",balance,"total",total,"limit",limit,"offset",offset,"postings",postings);
 }
 /** Journal references for staff reconciliation selectors. */
 @GetMapping("/journals") public List<Map<String,Object>> journals(){
  requireGlLedgerAccess();
  return db.rows("SELECT JOURNAL_ID,JOURNAL_TYPE,VALUE_DATE,DESCRIPTION FROM M05_GL_JOURNAL ORDER BY JOURNAL_ID DESC");
 }
 /** The paired lines and balance control for a journal selected from the GL register. */
 @GetMapping("/journals/{id}") public Map<String,Object> glJournal(@PathVariable long id){
  requireGlLedgerAccess();
  var journal=db.one("SELECT JOURNAL_ID,POSTING_KEY,TXN_ID,PAYMENT_ID,SETTLEMENT_CYCLE_ID,LOAN_FACILITY_ID,JOURNAL_TYPE,CURRENCY_CODE,BOOKED_AT,VALUE_DATE,REVERSAL_OF_JOURNAL_ID,DESCRIPTION,CREATED_BY FROM M05_GL_JOURNAL WHERE JOURNAL_ID=?",id);
  var control=db.one("SELECT LINE_COUNT,DEBIT_TOTAL,CREDIT_TOTAL,BALANCE_DIFFERENCE,IS_BALANCED FROM M05_V_GL_JOURNAL_CONTROL WHERE JOURNAL_ID=?",id);
  var lines=db.rows("SELECT P.POSTING_ID,P.LINE_NO,P.GL_ACCOUNT_ID,G.GL_CODE,G.GL_NAME,P.BANK_ACCOUNT_ID,P.LOAN_FACILITY_ID,P.LOAN_COMPONENT_CODE,P.ENTRY_SIDE,P.AMOUNT,P.NARRATIVE FROM M05_GL_POSTING P JOIN M05_GL_ACCOUNT G ON G.GL_ACCOUNT_ID=P.GL_ACCOUNT_ID WHERE P.JOURNAL_ID=? ORDER BY P.LINE_NO",id);
  hashes.audit("GL_JOURNAL_VIEWED","GL_JOURNAL",Long.toString(id),"GL_RECONCILE");
  return Map.of("journal",journal,"control",control,"lines",lines);
 }
 @GetMapping("/audit") public List<Map<String,Object>> audit(){access.global("IAM_AUDIT_READ");return db.rows("SELECT * FROM MBX_AUDIT ORDER BY OCCURRED_AT DESC FETCH FIRST 100 ROWS ONLY");}
 @GetMapping("/facilities") public List<Map<String,Object>> facilities(){
  var authorized=new ArrayList<Map<String,Object>>();long beforeId=Long.MAX_VALUE;
  while(authorized.size()<100){
   var rows=db.rows("SELECT FACILITY_ID,FACILITY_NUMBER,PRIMARY_CIF_ID,BRANCH_CODE,STATUS FROM M08_LOAN_FACILITY WHERE FACILITY_ID<? ORDER BY FACILITY_ID DESC FETCH FIRST 100 ROWS ONLY",beforeId);
   if(rows.isEmpty())break;
   for(var row:rows){
    long id=((Number)row.get("FACILITY_ID")).longValue();beforeId=id;
    try{access.facility("LOAN_READ",id);authorized.add(row);}catch(com.moneybags.common.api.BusinessException denied){/* continue to the next scoped facility */}
    if(authorized.size()==100)break;
   }
   if(rows.size()<100)break;
  }
  return authorized;
 }
}
