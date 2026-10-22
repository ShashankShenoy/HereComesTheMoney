package com.moneybags.integration;

import com.moneybags.common.api.BusinessException;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.iam.dto.IamDtos.AuthorizationInput;
import com.moneybags.iam.service.AccessDecisionService;
import com.moneybags.txn.api.Contracts.*;
import com.moneybags.txn.core.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.*;
import java.time.*;
import java.util.*;

/** Explicit early-payment commands; scheduled repayments keep their existing behavior. */
@RestController @RequestMapping("/api/v1/loans/facilities/{id}")
public class LoanPrepaymentController {
    public enum Mode { PARTIAL, FORECLOSURE }
    public record Prepay(@NotBlank @Size(max=100) String requestKey,
        @NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal amount,@NotNull Mode mode) {}
    public record Quote(long facilityId,LocalDate valueDate,BigDecimal principalOutstanding,
        BigDecimal scheduledPrincipalDue,BigDecimal interestDue,BigDecimal minimumPartialPayment,
        BigDecimal foreclosureAmount,boolean partialPrepaymentAllowed,boolean foreclosureAllowed) {}
    private record Context(Map<String,Object> facility,Map<String,Object> schedule,
        List<Map<String,Object>> due,List<Map<String,Object>> future,BigDecimal currentInterest,Quote quote) {}
    private record Allocation(long item,String component,BigDecimal amount) {}
    private final BusinessRepository db;
    private final AccessDecisionService access;
    private final BankingAccess banking;
    private final LedgerService ledger;
    private final CustomerHashService audit;
    public LoanPrepaymentController(BusinessRepository db,AccessDecisionService access,BankingAccess banking,
                                    LedgerService ledger,CustomerHashService audit) {
        this.db=db;this.access=access;this.banking=banking;this.ledger=ledger;this.audit=audit;
    }

    @GetMapping("/prepayment-quote") @Transactional(readOnly=true)
    @Operation(summary="Quote early repayment",description="Shows current interest, the minimum partial payment and exact foreclosure amount. No funds move.")
    public Quote quote(@PathVariable long id) { return context(facility(id,"LOAN_READ",false)).quote(); }

    @PostMapping("/prepayments") @Transactional
    @Operation(summary="Make an early loan repayment",description="PARTIAL reduces future installments without changing their due dates. FORECLOSURE requires the exact current quote. Amount is the total account debit, including interest through today.")
    public TransactionView prepay(@PathVariable long id,@Valid @RequestBody Prepay input) {
        var f=facility(id,"LOAN_SERVICE",true);
        String key=digest(actor()+":prepay:"+input.requestKey()),rule="PREPAYMENT_"+input.mode().name();
        var old=db.rows("""
            SELECT P.FACILITY_ID,P.AMOUNT,P.TXN_ID,
              (SELECT MIN(A.ALLOCATION_RULE_CODE) FROM M08_LOAN_PAYMENT_ALLOCATION A WHERE A.LOAN_PAYMENT_ID=P.LOAN_PAYMENT_ID) RULE_CODE
            FROM M08_LOAN_PAYMENT P WHERE P.REQUEST_KEY=?
            """,key);
        if(!old.isEmpty()) {
            var previous=old.get(0);
            if(number(previous,"FACILITY_ID")!=id || money(previous,"AMOUNT").compareTo(input.amount())!=0
                || !rule.equals(previous.get("RULE_CODE")))throw fail("Idempotency key belongs to different payment terms");
            return ledger.transaction(number(previous,"TXN_ID"));
        }
        Context c=context(f);Quote q=c.quote();
        if(input.mode()==Mode.PARTIAL) {
            if(!q.partialPrepaymentAllowed())throw fail("Partial prepayment is not enabled for this product");
            if(input.amount().compareTo(q.minimumPartialPayment())<0 || input.amount().compareTo(q.foreclosureAmount())>=0)
                throw fail("Partial payment must cover interest and scheduled dues, and leave principal outstanding; use the current quote");
        } else if(!q.foreclosureAllowed() || input.amount().compareTo(q.foreclosureAmount())!=0)
            throw fail("Foreclosure must be enabled and the payment must equal the current quote");
        long account=number(f,"REPAYMENT_ACCOUNT_ID");var a=banking.account("TXN_POST",account);
        if(!"ACTIVE".equals(a.get("LIFECYCLE_STATUS")) || !"ACTIVE".equals(a.get("ACCOUNT_STATUS"))
            || !f.get("PRIMARY_CIF_ID").equals(a.get("PRIMARY_CIF_ID")))
            throw fail("Repayment account must be active and owned by the borrower");
        List<Allocation> allocations=new ArrayList<>();
        for(var item:c.due()) {
            BigDecimal interest=unpaid(item,"INTEREST");
            if(money(item,"INTEREST_DUE").signum()>0 && db.count("SELECT COUNT(*) FROM M08_LOAN_ACCRUAL WHERE SCHEDULE_ITEM_ID=? AND STATUS='POSTED'",item.get("SCHEDULE_ITEM_ID"))==0)
                accrue(f,item,money(item,"INTEREST_DUE"),BusinessRepository.localDate(item.get("DUE_DATE")),"due");
            add(allocations,item,"INTEREST",interest);add(allocations,item,"PRINCIPAL",unpaid(item,"PRINCIPAL"));
        }
        if(c.currentInterest().signum()>0) {
            var first=c.future().get(0);accrue(f,first,c.currentInterest(),today(),"early");
            add(allocations,first,"INTEREST",c.currentInterest());
        }
        BigDecimal extra=input.amount().subtract(q.interestDue()).subtract(q.scheduledPrincipalDue());
        for(var item:c.future()) {
            BigDecimal paid=extra.min(unpaid(item,"PRINCIPAL"));add(allocations,item,"PRINCIPAL",paid);extra=extra.subtract(paid);
        }
        if(extra.signum()!=0)throw fail("Payment exceeds the scheduled principal");
        project(id);
        if(money(db.one("SELECT INTEREST_OUTSTANDING FROM M08_LOAN_FACILITY WHERE FACILITY_ID=?",id),"INTEREST_OUTSTANDING").compareTo(q.interestDue())!=0)
            throw fail("Loan interest must be reconciled before early repayment");
        long txn=transaction("prepay:"+input.requestKey(),"LOAN_REPAYMENT",input.amount(),account,number(f,"PRODUCT_VERSION_ID"));
        List<JournalLine> lines=new ArrayList<>();lines.add(new JournalLine(LoanAccountLiabilityGl.resolve(db,number(a,"PRODUCT_VERSION_ID")),account,null,null,"DR",input.amount(),"Early loan repayment"));
        for(String component:List.of("INTEREST","PRINCIPAL")) {
            BigDecimal amount=allocations.stream().filter(x->x.component().equals(component)).map(Allocation::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(amount.signum()>0)lines.add(new JournalLine(gl(number(f,"PRODUCT_VERSION_ID"),"LOAN_"+component),null,id,component,"CR",amount,"Early loan repayment"));
        }
        long journal=ledger.postJournal(new JournalRequest("loan-prepay:"+txn,"LOAN_REPAYMENT",txn,null,null,id,null,null,today(),"Early loan repayment",lines),actor()).journalId();posted(txn);
        long payment=insert("LOAN_PAYMENT_ID","""
            INSERT INTO M08_LOAN_PAYMENT(FACILITY_ID,PAYMENT_SOURCE_CODE,SOURCE_ACCOUNT_ID,TXN_ID,GL_JOURNAL_ID,REQUEST_KEY,AMOUNT,VALUE_DATE,BOOKED_AT,STATUS)
            VALUES (?,'ACCOUNT',?,?,?,?,?,?,SYSTIMESTAMP,'ALLOCATED')
            """,id,account,txn,journal,key,input.amount(),today());
        int sequence=1;
        for(var allocation:allocations) {
            db.jdbc().update("INSERT INTO M08_LOAN_PAYMENT_ALLOCATION(LOAN_PAYMENT_ID,FACILITY_ID,ALLOCATION_SEQ_NO,SCHEDULE_ITEM_ID,ALLOCATION_TYPE,AMOUNT,ALLOCATION_RULE_CODE) VALUES (?,?,?,?,?,?,?)",payment,id,sequence++,allocation.item(),allocation.component(),allocation.amount(),rule);
            db.jdbc().update("UPDATE M08_LOAN_SCHEDULE_ITEM SET "+allocation.component()+"_PAID="+allocation.component()+"_PAID+? WHERE SCHEDULE_ITEM_ID=?",allocation.amount(),allocation.item());
        }
        db.jdbc().update("""
            UPDATE M08_LOAN_SCHEDULE_ITEM SET STATUS='PAID',SETTLED_AT=SYSTIMESTAMP
            WHERE SCHEDULE_ID=? AND DUE_DATE<=? AND PRINCIPAL_DUE=PRINCIPAL_PAID+PRINCIPAL_WAIVED
              AND INTEREST_DUE=INTEREST_PAID+INTEREST_WAIVED
            """,c.schedule().get("SCHEDULE_ID"),today());
        project(id);
        BigDecimal remaining=money(db.one("SELECT PRINCIPAL_OUTSTANDING FROM M08_LOAN_FACILITY WHERE FACILITY_ID=?",id),"PRINCIPAL_OUTSTANDING");
        replaceSchedule(c,remaining);
        db.jdbc().update("UPDATE M08_LOAN_FACILITY SET STATUS=?,CLOSED_AT=?,OVERDUE_AMOUNT=0,UPDATED_AT=SYSTIMESTAMP WHERE FACILITY_ID=?",remaining.signum()==0?"CLOSED":"ACTIVE",remaining.signum()==0?OffsetDateTime.now():null,id);
        audit.audit("LOAN_PREPAID","FACILITY",Long.toString(id),input.mode().name());return ledger.transaction(txn);
    }

    private Context context(Map<String,Object> f) {
        if(!Set.of("ACTIVE","DELINQUENT").contains(f.get("STATUS")) || !"FIXED".equals(f.get("INTEREST_TYPE"))
            || !"MONTHLY".equals(f.get("REPAYMENT_FREQUENCY")) || !"SINGLE".equals(f.get("DISBURSEMENT_MODE"))
            || !"REDUCING_BALANCE".equals(f.get("AMORTIZATION_METHOD")))throw fail("Early repayment requires an active fixed-rate monthly loan");
        if(money(f,"FEE_OUTSTANDING").signum()>0 || money(f,"PENALTY_OUTSTANDING").signum()>0)
            throw fail("Fee or penalty settlement needs its configured allocation workflow");
        long id=number(f,"FACILITY_ID");var schedule=db.one("SELECT * FROM M08_LOAN_SCHEDULE WHERE FACILITY_ID=? AND STATUS='ACTIVE'",id);
        var items=db.rows("SELECT * FROM M08_LOAN_SCHEDULE_ITEM WHERE SCHEDULE_ID=? ORDER BY INSTALLMENT_NO",schedule.get("SCHEDULE_ID"));
        List<Map<String,Object>> due=new ArrayList<>(),future=new ArrayList<>();
        BigDecimal principal=BigDecimal.ZERO,interest=BigDecimal.ZERO,scheduled=BigDecimal.ZERO;
        for(var item:items) {
            if(money(item,"FEE_DUE").compareTo(money(item,"FEE_PAID").add(money(item,"FEE_WAIVED")))!=0
                ||money(item,"PENALTY_DUE").compareTo(money(item,"PENALTY_PAID").add(money(item,"PENALTY_WAIVED")))!=0)
                throw fail("Schedule has fee or penalty amounts that require settlement");
            principal=principal.add(unpaid(item,"PRINCIPAL"));
            if(!BusinessRepository.localDate(item.get("DUE_DATE")).isAfter(today())) {
                due.add(item);scheduled=scheduled.add(unpaid(item,"PRINCIPAL"));interest=interest.add(unpaid(item,"INTEREST"));
            } else future.add(item);
        }
        if(principal.compareTo(money(f,"PRINCIPAL_OUTSTANDING"))!=0)throw fail("Loan principal must be reconciled before early repayment");
        BigDecimal current=BigDecimal.ZERO;
        if(!future.isEmpty()) {
            var first=future.get(0);LocalDate date=BusinessRepository.localDate(first.get("DUE_DATE"));
            LocalDate contractFrom=LoanPrepaymentMath.periodStart(BusinessRepository.localDate(f.get("START_DATE")),date);
            LocalDate from=BusinessRepository.localDate(schedule.get("EFFECTIVE_FROM_DATE"));if(from.isBefore(contractFrom))from=contractFrom;
            current=LoanPrepaymentMath.interest(money(first,"OPENING_PRINCIPAL"),money(f,"ANNUAL_RATE_PCT"),from,today(),(String)f.get("DAY_COUNT_BASIS"),contractFrom,date);
        }
        interest=interest.add(current);
        var product=db.one("SELECT PREPAYMENT_ALLOWED,PARTIAL_PREPAYMENT_ALLOWED,FORECLOSURE_ALLOWED FROM M03_PM_LOAN_RULE WHERE PRODUCT_VERSION_ID=?",f.get("PRODUCT_VERSION_ID"));
        boolean enabled="Y".equals(product.get("PREPAYMENT_ALLOWED"));
        Quote quote=new Quote(id,today(),principal,scheduled,interest,scheduled.add(interest).add(new BigDecimal("0.01")),principal.add(interest),enabled&&!future.isEmpty()&&"Y".equals(product.get("PARTIAL_PREPAYMENT_ALLOWED")),enabled&&"Y".equals(product.get("FORECLOSURE_ALLOWED")));
        return new Context(f,schedule,due,future,current,quote);
    }

    private void replaceSchedule(Context c,BigDecimal principal) {
        long id=number(c.facility(),"FACILITY_ID"),old=number(c.schedule(),"SCHEDULE_ID");
        db.jdbc().update("UPDATE M08_LOAN_SCHEDULE SET STATUS='SUPERSEDED',SUPERSEDED_AT=SYSTIMESTAMP WHERE SCHEDULE_ID=?",old);
        db.jdbc().update("UPDATE M08_LOAN_SCHEDULE_ITEM SET STATUS='CANCELLED',SETTLED_AT=SYSTIMESTAMP WHERE SCHEDULE_ID=? AND DUE_DATE>?",old,today());
        if(principal.signum()==0)return;
        var dates=c.future().stream().map(i->BusinessRepository.localDate(i.get("DUE_DATE"))).toList();
        var rows=LoanPrepaymentMath.reschedule(principal,money(c.facility(),"ANNUAL_RATE_PCT"),dates,today(),BusinessRepository.localDate(c.facility().get("START_DATE")),(String)c.facility().get("DAY_COUNT_BASIS"));
        BigDecimal interest=rows.stream().map(LoanScheduleMath.Installment::interest).reduce(BigDecimal.ZERO,BigDecimal::add);
        long schedule=insert("SCHEDULE_ID","""
            INSERT INTO M08_LOAN_SCHEDULE(FACILITY_ID,SCHEDULE_SEQ_NO,GENERATION_REASON,STATUS,EFFECTIVE_FROM_DATE,INSTALLMENT_COUNT,TOTAL_PRINCIPAL,TOTAL_INTEREST,SCHEDULE_HASH,CREATED_BY_USER_ID,ACTIVATED_AT)
            VALUES (?,?,'PREPAYMENT','ACTIVE',?,?,?,?,?,?,SYSTIMESTAMP)
            """,id,number(c.schedule(),"SCHEDULE_SEQ_NO")+1,today(),rows.size(),principal,interest,digest(rows.toString()),actor());
        for(var row:rows)db.jdbc().update("INSERT INTO M08_LOAN_SCHEDULE_ITEM(SCHEDULE_ID,FACILITY_ID,INSTALLMENT_NO,DUE_DATE,OPENING_PRINCIPAL,PRINCIPAL_DUE,INTEREST_DUE) VALUES (?,?,?,?,?,?,?)",schedule,id,row.number(),row.due(),row.opening(),row.principal(),row.interest());
    }

    private void accrue(Map<String,Object> f,Map<String,Object> item,BigDecimal amount,LocalDate date,String kind) {
        long id=number(f,"FACILITY_ID"),itemId=number(item,"SCHEDULE_ITEM_ID"),version=number(f,"PRODUCT_VERSION_ID");
        long txn=transaction("prepay-interest:"+kind+":"+itemId,"LOAN_ACCRUAL",amount,null,version);
        long journal=ledger.postJournal(new JournalRequest("loan-prepay-accrue:"+txn,"LOAN_ACCRUAL",txn,null,null,id,null,null,today(),"Interest through early repayment date",List.of(new JournalLine(gl(version,"LOAN_INTEREST"),null,id,"INTEREST","DR",amount,"Interest receivable"),new JournalLine(gl(version,"INTEREST_INCOME"),null,null,null,"CR",amount,"Interest income"))),actor()).journalId();posted(txn);
        db.jdbc().update("INSERT INTO M08_LOAN_ACCRUAL(FACILITY_ID,SCHEDULE_ITEM_ID,ACCRUAL_DATE,ACCRUAL_TYPE,PRINCIPAL_BASIS,RATE_PCT,ACCRUED_AMOUNT,STATUS,TXN_ID,GL_JOURNAL_ID,POSTED_AT) VALUES (?,?,?,'INTEREST',?,?,?,'POSTED',?,?,SYSTIMESTAMP)",id,itemId,date,item.get("OPENING_PRINCIPAL"),f.get("ANNUAL_RATE_PCT"),amount,txn,journal);
    }
    private Map<String,Object> facility(long id,String permission,boolean lock) {
        var f=db.one("SELECT * FROM M08_LOAN_FACILITY WHERE FACILITY_ID=?"+(lock?" FOR UPDATE":""),id);
        access.require(CurrentActor.get(),new AuthorizationInput(permission,(String)f.get("BRANCH_CODE"),null,(String)f.get("PRIMARY_CIF_ID"),"LOAN","INR",null,null,null,null));return f;
    }
    private long transaction(String key,String type,BigDecimal amount,Long account,long version) {
        return insert("TXN_ID","INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,SOURCE_ACCOUNT_ID,PRODUCT_VERSION_ID,AMOUNT,VALUE_DATE) VALUES (?,'LOAN',?,?,?,?,'VALIDATED',?,?,?,?)",actor(),digest(key),CustomerHashService.digest(key+"|"+amount),UUID.randomUUID().toString(),type,account,version,amount,today());
    }
    private void posted(long txn) {
        db.jdbc().update("UPDATE M05_TXN_TRANSACTION_LOG SET STATUS='POSTED' WHERE TXN_ID=?",txn);
        for(String[] state:List.of(new String[]{null,"RECEIVED"},new String[]{"RECEIVED","VALIDATED"},new String[]{"VALIDATED","POSTED"}))
            db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,?,?,?,'LOAN_PREPAYMENT')",txn,state[0],state[1],actor());
    }
    private void project(long id) {
        for(String component:List.of("PRINCIPAL","INTEREST","FEE","PENALTY"))db.jdbc().update("UPDATE M08_LOAN_FACILITY SET "+component+"_OUTSTANDING=COALESCE((SELECT RECEIVABLE_BALANCE FROM M05_V_LOAN_RECEIVABLE_POSITION WHERE LOAN_FACILITY_ID=? AND LOAN_COMPONENT_CODE=?),0),FINANCIAL_AS_OF=SYSTIMESTAMP WHERE FACILITY_ID=?",id,component,id);
    }
    private long gl(long version,String role) {
        return number(db.one("SELECT GL_ACCOUNT_ID FROM M05_GL_PRODUCT_MAPPING WHERE PRODUCT_VERSION_ID=? AND POSTING_TYPE='LOAN' AND GL_ROLE_CODE=? AND STATUS='ACTIVE' AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>=SYSDATE)",version,role),"GL_ACCOUNT_ID");
    }
    private long insert(String key,String sql,Object...args) {
        var holder=new GeneratedKeyHolder();db.jdbc().update(c->{var ps=c.prepareStatement(sql,new String[]{key});for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;},holder);return Objects.requireNonNull(holder.getKey()).longValue();
    }
    private static void add(List<Allocation> rows,Map<String,Object> item,String component,BigDecimal amount) {if(amount.signum()>0)rows.add(new Allocation(number(item,"SCHEDULE_ITEM_ID"),component,amount));}
    private static BigDecimal unpaid(Map<String,Object> item,String component) {return money(item,component+"_DUE").subtract(money(item,component+"_PAID")).subtract(money(item,component+"_WAIVED"));}
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private static BigDecimal money(Map<String,Object> row,String key) {return (BigDecimal)row.get(key);}
    private static LocalDate today() {return LocalDate.now(ZoneId.of("Asia/Kolkata"));}
    private static String actor() {return CurrentActor.get().userId();}
    private static String digest(String value) {return HexFormat.of().formatHex(CustomerHashService.digest(value));}
    private static BusinessException fail(String message) {return new BusinessException(HttpStatus.CONFLICT,"LOAN_PREPAYMENT_RULE",message);}
}
