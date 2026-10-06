package com.moneybags.integration;

import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.common.api.BusinessException;
import com.moneybags.iam.service.AccessDecisionService;
import com.moneybags.iam.dto.IamDtos.AuthorizationInput;
import com.moneybags.txn.core.LedgerService;
import com.moneybags.txn.api.Contracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

/** Loan servicing commands always create balanced M05 journals; M08 balances are derived projections. */
@RestController @RequestMapping("/api/v1/loans")
public class LoanServicingController {
 private final BusinessRepository db;private final AccessDecisionService access;private final BankingAccess banking;private final LedgerService ledger;private final CustomerHashService audit;
 public LoanServicingController(BusinessRepository db,AccessDecisionService access,BankingAccess banking,LedgerService ledger,CustomerHashService audit){this.db=db;this.access=access;this.banking=banking;this.ledger=ledger;this.audit=audit;}
 public record Disburse(@NotBlank @Size(max=100)String requestKey){}
 public record Approve(@NotBlank String authorityCode){}
 public record Repay(@NotBlank @Size(max=100)String requestKey,@NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2)BigDecimal amount){}
 @GetMapping("/facilities/{id}/disbursements") public List<Map<String,Object>> disbursements(@PathVariable long id){facility(id,"LOAN_READ");return db.rows("SELECT DISBURSEMENT_ID,STATUS,REQUESTED_AT,APPROVED_AT,TXN_ID FROM M08_LOAN_DISBURSEMENT WHERE FACILITY_ID=?",id);}
 @PostMapping("/facilities/{id}/disbursements") @Transactional public Map<String,Object> request(@PathVariable long id,@Valid @RequestBody Disburse r){
  var f=facility(id,"LOAN_SERVICE");var old=db.rows("SELECT DISBURSEMENT_ID,FACILITY_ID,REQUESTED_BY_USER_ID FROM M08_LOAN_DISBURSEMENT WHERE REQUEST_KEY=?",r.requestKey());
  if(!old.isEmpty()){if(((Number)old.get(0).get("FACILITY_ID")).longValue()!=id||!actor().equals(old.get(0).get("REQUESTED_BY_USER_ID")))throw fail("Idempotency key belongs to another disbursement");return Map.of("disbursementId",old.get(0).get("DISBURSEMENT_ID"));}
  supported(f);if(!"PENDING_DISBURSEMENT".equals(f.get("STATUS")))throw fail("Facility is not pending disbursement");
  long d=insert("DISBURSEMENT_ID","INSERT INTO M08_LOAN_DISBURSEMENT(FACILITY_ID,DISBURSEMENT_SEQ_NO,TARGET_ACCOUNT_ID,AMOUNT,VALUE_DATE,REQUEST_KEY,REQUESTED_BY_USER_ID) VALUES (?,1,?,?,?,?,?)",id,f.get("DISBURSEMENT_ACCOUNT_ID"),f.get("PRINCIPAL_AMOUNT"),today(),r.requestKey(),actor());
  audit.audit("LOAN_DISBURSEMENT_REQUESTED","FACILITY",Long.toString(id),"MAKER_SUBMISSION");return Map.of("disbursementId",d,"status","REQUESTED");
 }
 @PostMapping("/disbursements/{id}/approve") @Transactional public TransactionView approve(@PathVariable long id,@Valid @RequestBody Approve r){
  var d=db.one("SELECT * FROM M08_LOAN_DISBURSEMENT WHERE DISBURSEMENT_ID=? FOR UPDATE",id);long facilityId=num(d,"FACILITY_ID");var f=facility(facilityId,"LOAN_DISBURSE_APPROVE");
  access.require(CurrentActor.get(),new AuthorizationInput("LOAN_DISBURSE_APPROVE",(String)f.get("BRANCH_CODE"),null,(String)f.get("PRIMARY_CIF_ID"),"LOAN","INR",r.authorityCode(),money(d,"AMOUNT"),null,(String)d.get("REQUESTED_BY_USER_ID")));
  if("POSTED".equals(d.get("STATUS")))return ledger.transaction(num(d,"TXN_ID"));
  if(!"REQUESTED".equals(d.get("STATUS"))||!"PENDING_DISBURSEMENT".equals(f.get("STATUS")))throw fail("Disbursement is no longer pending");
  supported(f);long account=num(f,"DISBURSEMENT_ACCOUNT_ID");var a=db.one("SELECT PRODUCT_VERSION_ID,PRIMARY_CIF_ID,LIFECYCLE_STATUS FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?",account);
  if(!"ACTIVE".equals(a.get("LIFECYCLE_STATUS"))||!f.get("PRIMARY_CIF_ID").equals(a.get("PRIMARY_CIF_ID")))throw fail("Borrower account is not eligible");
  if(!today().equals(BusinessRepository.localDate(d.get("VALUE_DATE"))))throw fail("Request a current-business-date disbursement");
  var rows=LoanScheduleMath.calculate(money(f,"PRINCIPAL_AMOUNT"),money(f,"ANNUAL_RATE_PCT"),((Number)f.get("TENURE_MONTHS")).intValue(),today(),(String)f.get("DAY_COUNT_BASIS"));
  long txn=txn("loan-disburse:"+id,"LOAN_DISBURSEMENT",money(d,"AMOUNT"),null,account,num(f,"PRODUCT_VERSION_ID"));
  long journal=ledger.postJournal(new JournalRequest("loan-disburse:"+id,"LOAN_DISBURSEMENT",txn,null,null,facilityId,null,null,today(),"Loan disbursement",List.of(new JournalLine(gl(num(f,"PRODUCT_VERSION_ID"),"LOAN_PRINCIPAL"),null,facilityId,"PRINCIPAL","DR",money(d,"AMOUNT"),"Principal advance"),new JournalLine(gl(num(a,"PRODUCT_VERSION_ID"),"CUSTOMER_LIABILITY"),account,null,null,"CR",money(d,"AMOUNT"),"Loan proceeds"))),actor()).journalId();
  posted(txn);
  db.jdbc().update("UPDATE M08_LOAN_DISBURSEMENT SET STATUS='POSTED',APPROVED_BY_USER_ID=?,APPROVED_AT=SYSTIMESTAMP,APPROVAL_AUTHORITY_CODE=?,TXN_ID=?,GL_JOURNAL_ID=?,POSTED_AT=SYSTIMESTAMP WHERE DISBURSEMENT_ID=?",actor(),r.authorityCode(),txn,journal,id);
  BigDecimal interest=rows.stream().map(LoanScheduleMath.Installment::interest).reduce(BigDecimal.ZERO,BigDecimal::add);
  long schedule=insert("SCHEDULE_ID","INSERT INTO M08_LOAN_SCHEDULE(FACILITY_ID,SCHEDULE_SEQ_NO,GENERATION_REASON,STATUS,EFFECTIVE_FROM_DATE,INSTALLMENT_COUNT,TOTAL_PRINCIPAL,TOTAL_INTEREST,SCHEDULE_HASH,CREATED_BY_USER_ID,ACTIVATED_AT) VALUES (?,1,'ORIGINATION','ACTIVE',?,?,?,?,?,?,SYSTIMESTAMP)",facilityId,today(),rows.size(),money(f,"PRINCIPAL_AMOUNT"),interest,HexFormat.of().formatHex(CustomerHashService.digest(rows.toString())),actor());
  for(var row:rows)db.jdbc().update("INSERT INTO M08_LOAN_SCHEDULE_ITEM(SCHEDULE_ID,FACILITY_ID,INSTALLMENT_NO,DUE_DATE,OPENING_PRINCIPAL,PRINCIPAL_DUE,INTEREST_DUE) VALUES (?,?,?,?,?,?,?)",schedule,facilityId,row.number(),row.due(),row.opening(),row.principal(),row.interest());
  db.jdbc().update("INSERT INTO M08_LOAN_RATE_HISTORY(FACILITY_ID,EFFECTIVE_FROM_DATE,INTEREST_TYPE,ANNUAL_RATE_PCT,FIXED_RATE_PCT,RESET_REASON,RECORDED_BY_USER_ID) VALUES (?,?,'FIXED',?,?,'ORIGINATION',?)",facilityId,today(),f.get("ANNUAL_RATE_PCT"),f.get("ANNUAL_RATE_PCT"),actor());
  db.jdbc().update("UPDATE M08_LOAN_FACILITY SET STATUS='ACTIVE',TOTAL_DISBURSED_AMOUNT=?,START_DATE=?,MATURITY_DATE=?,UPDATED_AT=SYSTIMESTAMP WHERE FACILITY_ID=?",f.get("PRINCIPAL_AMOUNT"),today(),rows.get(rows.size()-1).due(),facilityId);
  project(facilityId);audit.audit("LOAN_DISBURSED","FACILITY",Long.toString(facilityId),"APPROVED_AND_POSTED");return ledger.transaction(txn);
 }
 @PostMapping("/facilities/{id}/accruals") @Transactional public List<TransactionView> accrue(@PathVariable long id){
  var f=facility(id,"LOAN_SERVICE");supported(f);if(!Set.of("ACTIVE","DELINQUENT").contains(f.get("STATUS")))throw fail("Facility must be active");
  var items=db.rows("SELECT I.* FROM M08_LOAN_SCHEDULE_ITEM I JOIN M08_LOAN_SCHEDULE S ON S.SCHEDULE_ID=I.SCHEDULE_ID WHERE I.FACILITY_ID=? AND S.STATUS='ACTIVE' AND I.DUE_DATE<=? AND I.INTEREST_DUE>0 AND NOT EXISTS(SELECT 1 FROM M08_LOAN_ACCRUAL A WHERE A.SCHEDULE_ITEM_ID=I.SCHEDULE_ITEM_ID AND A.STATUS='POSTED') ORDER BY I.INSTALLMENT_NO",id,today());
  List<TransactionView> posted=new ArrayList<>();
  for(var item:items){long itemId=num(item,"SCHEDULE_ITEM_ID");BigDecimal amount=money(item,"INTEREST_DUE");long txn=txn("loan-accrue:"+itemId,"LOAN_ACCRUAL",amount,null,null,num(f,"PRODUCT_VERSION_ID"));
   long journal=ledger.postJournal(new JournalRequest("loan-accrue:"+itemId,"LOAN_ACCRUAL",txn,null,null,id,null,null,today(),"Contractual installment interest",List.of(new JournalLine(gl(num(f,"PRODUCT_VERSION_ID"),"LOAN_INTEREST"),null,id,"INTEREST","DR",amount,"Interest receivable"),new JournalLine(gl(num(f,"PRODUCT_VERSION_ID"),"INTEREST_INCOME"),null,null,null,"CR",amount,"Interest income"))),actor()).journalId();posted(txn);
   db.jdbc().update("INSERT INTO M08_LOAN_ACCRUAL(FACILITY_ID,SCHEDULE_ITEM_ID,ACCRUAL_DATE,ACCRUAL_TYPE,PRINCIPAL_BASIS,RATE_PCT,ACCRUED_AMOUNT,STATUS,TXN_ID,GL_JOURNAL_ID,POSTED_AT) VALUES (?,?,?,'INTEREST',?,?,?,'POSTED',?,?,SYSTIMESTAMP)",id,itemId,BusinessRepository.localDate(item.get("DUE_DATE")),item.get("OPENING_PRINCIPAL"),f.get("ANNUAL_RATE_PCT"),amount,txn,journal);posted.add(ledger.transaction(txn));
  }
  project(id);return posted;
 }
 @PostMapping("/facilities/{id}/repayments") @Transactional public TransactionView repay(@PathVariable long id,@Valid @RequestBody Repay r){
  var f=facility(id,"LOAN_SERVICE");long account=num(f,"REPAYMENT_ACCOUNT_ID");var a=banking.account("TXN_POST",account);
  var old=db.rows("SELECT FACILITY_ID,AMOUNT,TXN_ID FROM M08_LOAN_PAYMENT WHERE REQUEST_KEY=?",HexFormat.of().formatHex(CustomerHashService.digest(actor()+":"+r.requestKey())));
  if(!old.isEmpty()){if(num(old.get(0),"FACILITY_ID")!=id||money(old.get(0),"AMOUNT").compareTo(r.amount())!=0)throw fail("Idempotency conflict");return ledger.transaction(num(old.get(0),"TXN_ID"));}
  var items=db.rows("SELECT I.* FROM M08_LOAN_SCHEDULE_ITEM I JOIN M08_LOAN_SCHEDULE S ON S.SCHEDULE_ID=I.SCHEDULE_ID WHERE I.FACILITY_ID=? AND S.STATUS='ACTIVE' AND I.DUE_DATE<=? AND I.STATUS IN ('PENDING','PART_PAID','OVERDUE') ORDER BY I.INSTALLMENT_NO",id,today());
  record Allocation(long item,String type,BigDecimal amount){} List<Allocation> allocations=new ArrayList<>();BigDecimal remaining=r.amount();
  for(var item:items){
   if(money(item,"FEE_DUE").signum()>0||money(item,"PENALTY_DUE").signum()>0)throw fail("A configured fee/penalty allocation adapter is required");
   for(String component:List.of("INTEREST","PRINCIPAL")){
    BigDecimal due=money(item,component+"_DUE").subtract(money(item,component+"_PAID")).subtract(money(item,component+"_WAIVED"));BigDecimal paid=remaining.min(due);if(paid.signum()<=0)continue;
    if(component.equals("INTEREST")&&db.count("SELECT COUNT(*) FROM M08_LOAN_ACCRUAL WHERE SCHEDULE_ITEM_ID=? AND STATUS='POSTED'",item.get("SCHEDULE_ITEM_ID"))==0)throw fail("Accrue the due installment before repayment");
    allocations.add(new Allocation(num(item,"SCHEDULE_ITEM_ID"),component,paid));remaining=remaining.subtract(paid);
   }
  }
  if(remaining.signum()>0)throw fail("Amount exceeds accrued due installments; early prepayment requires a reschedule workflow");
  long txn=txn("repay:"+r.requestKey(),"LOAN_REPAYMENT",r.amount(),account,null,num(f,"PRODUCT_VERSION_ID"));List<JournalLine> lines=new ArrayList<>();lines.add(new JournalLine(gl(num(a,"PRODUCT_VERSION_ID"),"CUSTOMER_LIABILITY"),account,null,null,"DR",r.amount(),"Loan repayment"));
  for(String component:List.of("INTEREST","PRINCIPAL")){BigDecimal amount=allocations.stream().filter(x->x.type().equals(component)).map(Allocation::amount).reduce(BigDecimal.ZERO,BigDecimal::add);if(amount.signum()>0)lines.add(new JournalLine(gl(num(f,"PRODUCT_VERSION_ID"),"LOAN_"+component),null,id,component,"CR",amount,"Loan repayment"));}
  long journal=ledger.postJournal(new JournalRequest("loan-repay:"+txn,"LOAN_REPAYMENT",txn,null,null,id,null,null,today(),"Loan repayment",lines),actor()).journalId();posted(txn);
  long payment=insert("LOAN_PAYMENT_ID","INSERT INTO M08_LOAN_PAYMENT(FACILITY_ID,PAYMENT_SOURCE_CODE,SOURCE_ACCOUNT_ID,TXN_ID,GL_JOURNAL_ID,REQUEST_KEY,AMOUNT,VALUE_DATE,BOOKED_AT,STATUS) VALUES (?,'ACCOUNT',?,?,?,?,?,?,SYSTIMESTAMP,'ALLOCATED')",id,account,txn,journal,HexFormat.of().formatHex(CustomerHashService.digest(actor()+":"+r.requestKey())),r.amount(),today());
  int seq=1;for(var allocation:allocations){db.jdbc().update("INSERT INTO M08_LOAN_PAYMENT_ALLOCATION(LOAN_PAYMENT_ID,FACILITY_ID,ALLOCATION_SEQ_NO,SCHEDULE_ITEM_ID,ALLOCATION_TYPE,AMOUNT,ALLOCATION_RULE_CODE) VALUES (?,?,?,?,?,?,'INTEREST_THEN_PRINCIPAL')",payment,id,seq++,allocation.item(),allocation.type(),allocation.amount());db.jdbc().update("UPDATE M08_LOAN_SCHEDULE_ITEM SET "+allocation.type()+"_PAID="+allocation.type()+"_PAID+? WHERE SCHEDULE_ITEM_ID=?",allocation.amount(),allocation.item());}
  db.jdbc().update("UPDATE M08_LOAN_SCHEDULE_ITEM SET STATUS=CASE WHEN PRINCIPAL_PAID=PRINCIPAL_DUE AND INTEREST_PAID=INTEREST_DUE THEN 'PAID' ELSE 'PART_PAID' END,SETTLED_AT=CASE WHEN PRINCIPAL_PAID=PRINCIPAL_DUE AND INTEREST_PAID=INTEREST_DUE THEN SYSTIMESTAMP ELSE NULL END WHERE FACILITY_ID=? AND PRINCIPAL_PAID+INTEREST_PAID>0",id);
  project(id);db.jdbc().update("UPDATE M08_LOAN_FACILITY SET STATUS='CLOSED',CLOSED_AT=SYSTIMESTAMP WHERE FACILITY_ID=? AND PRINCIPAL_OUTSTANDING=0 AND INTEREST_OUTSTANDING=0 AND FEE_OUTSTANDING=0 AND PENALTY_OUTSTANDING=0",id);audit.audit("LOAN_REPAID","FACILITY",Long.toString(id),"LEDGER_POSTED");return ledger.transaction(txn);
 }
 @GetMapping("/collections") public List<Map<String,Object>> collections(){banking.global("LOAN_SERVICE");return db.rows("SELECT F.FACILITY_ID,F.FACILITY_NUMBER,F.BRANCH_CODE,MIN(I.DUE_DATE) OLDEST_UNPAID_DATE,COUNT(*) OVERDUE_INSTALLMENTS FROM M08_LOAN_FACILITY F JOIN M08_LOAN_SCHEDULE S ON S.FACILITY_ID=F.FACILITY_ID JOIN M08_LOAN_SCHEDULE_ITEM I ON I.SCHEDULE_ID=S.SCHEDULE_ID WHERE S.STATUS='ACTIVE' AND I.DUE_DATE<SYSDATE AND I.STATUS IN ('PENDING','PART_PAID','OVERDUE') GROUP BY F.FACILITY_ID,F.FACILITY_NUMBER,F.BRANCH_CODE");}
 private Map<String,Object> facility(long id,String permission){var f=db.one("SELECT * FROM M08_LOAN_FACILITY WHERE FACILITY_ID=? FOR UPDATE",id);access.require(CurrentActor.get(),new AuthorizationInput(permission,(String)f.get("BRANCH_CODE"),null,(String)f.get("PRIMARY_CIF_ID"),"LOAN","INR",null,null,null,null));return f;}
 private void supported(Map<String,Object> f){if(!"FIXED".equals(f.get("INTEREST_TYPE"))||!"MONTHLY".equals(f.get("REPAYMENT_FREQUENCY"))||!"SINGLE".equals(f.get("DISBURSEMENT_MODE"))||!"REDUCING_BALANCE".equals(f.get("AMORTIZATION_METHOD")))throw fail("Servicing supports fixed-rate, single-disbursement, monthly reducing-balance loans");}
 private void project(long id){for(String component:List.of("PRINCIPAL","INTEREST","FEE","PENALTY"))db.jdbc().update("UPDATE M08_LOAN_FACILITY SET "+component+"_OUTSTANDING=COALESCE((SELECT RECEIVABLE_BALANCE FROM M05_V_LOAN_RECEIVABLE_POSITION WHERE LOAN_FACILITY_ID=? AND LOAN_COMPONENT_CODE=?),0),FINANCIAL_AS_OF=SYSTIMESTAMP WHERE FACILITY_ID=?",id,component,id);}
 private long gl(long version,String role){return num(db.one("SELECT GL_ACCOUNT_ID FROM M05_GL_PRODUCT_MAPPING WHERE PRODUCT_VERSION_ID=? AND POSTING_TYPE='LOAN' AND GL_ROLE_CODE=? AND STATUS='ACTIVE' AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>=SYSDATE)",version,role),"GL_ACCOUNT_ID");}
 private long txn(String key,String type,BigDecimal amount,Long source,Long target,long version){return insert("TXN_ID","INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID,PRODUCT_VERSION_ID,AMOUNT,VALUE_DATE) VALUES (?,'LOAN',?,?,?,?,'VALIDATED',?,?,?,?,?)",actor(),HexFormat.of().formatHex(CustomerHashService.digest(key)),CustomerHashService.digest(key+"|"+amount),UUID.randomUUID().toString(),type,source,target,version,amount,today());}
 private void posted(long txn){db.jdbc().update("UPDATE M05_TXN_TRANSACTION_LOG SET STATUS='POSTED' WHERE TXN_ID=?",txn);for(String[] transition:List.of(new String[]{null,"RECEIVED"},new String[]{"RECEIVED","VALIDATED"},new String[]{"VALIDATED","POSTED"}))db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,?,?,?,'LOAN_WORKFLOW')",txn,transition[0],transition[1],actor());}
 private long insert(String key,String sql,Object...args){var holder=new GeneratedKeyHolder();db.jdbc().update(c->{var ps=c.prepareStatement(sql,new String[]{key});for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;},holder);return Objects.requireNonNull(holder.getKey()).longValue();}
 private static long num(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}private static BigDecimal money(Map<String,Object> row,String key){return (BigDecimal)row.get(key);}private static LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Kolkata"));}private static String actor(){return CurrentActor.get().userId();}private BusinessException fail(String m){return new BusinessException(HttpStatus.CONFLICT,"LOAN_SERVICING_RULE",m);}
}
