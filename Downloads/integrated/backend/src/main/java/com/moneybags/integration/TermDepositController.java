package com.moneybags.integration;

import com.moneybags.common.api.BusinessException;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.product.service.ProductDefinitionsPort;
import com.moneybags.txn.api.Contracts.JournalLine;
import com.moneybags.txn.api.Contracts.JournalRequest;
import com.moneybags.txn.core.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fixed-rate deposit contracts funded from and repaid to a customer's active bank account. */
@RestController
@RequestMapping("/api/v1/term-deposits")
public class TermDepositController {
    private static final Logger log=LoggerFactory.getLogger(TermDepositController.class);
    private static final ZoneId ZONE=ZoneId.of("Asia/Kolkata");
    private final BusinessRepository db;
    private final BankingAccess access;
    private final ProductDefinitionsPort products;
    private final LedgerService ledger;
    private final DepositInterestController interest;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public TermDepositController(BusinessRepository db,BankingAccess access,ProductDefinitionsPort products,
                                 LedgerService ledger,DepositInterestController interest,TransactionTemplate tx,
                                 @Value("${moneybags.deposit-interest.enabled:false}") boolean enabled) {
        this.db=db;this.access=access;this.products=products;this.ledger=ledger;this.interest=interest;this.tx=tx;this.enabled=enabled;
    }

    public record OpenRequest(@NotNull Long productId,@NotNull Long productVersionId,
                              @NotNull Long fundingAccountId,@NotNull @DecimalMin("0.01") BigDecimal amount,
                              @NotNull Integer tenureMonths,@NotBlank @Size(max=80) String requestKey) { }

    @GetMapping("/my")
    @Operation(summary="List the signed-in customer's fixed deposits")
    public List<Map<String,Object>> mine() {
        var actor=CurrentActor.get();
        if(!"CUSTOMER".equals(actor.userType())) throw fail("Customer sign-in required");
        return db.rows("SELECT T.TERM_DEPOSIT_ID,T.PRIMARY_CIF_ID,T.FUNDING_ACCOUNT_ID,T.PRODUCT_ID,T.PRODUCT_VERSION_ID,"+
                "P.PRODUCT_NAME,T.PRINCIPAL,T.TENURE_MONTHS,T.ANNUAL_RATE_PCT,T.START_DATE,T.MATURITY_DATE,T.MATURITY_INTEREST,T.STATUS,"+
                "T.CREATED_AT,T.MATURED_AT FROM MBX_TERM_DEPOSIT T JOIN M03_PM_PRODUCT P ON P.PRODUCT_ID=T.PRODUCT_ID " +
                "JOIN M01_IAM_CUSTOMER_LINK L ON L.CIF_ID=T.PRIMARY_CIF_ID WHERE L.USER_ID=? AND L.STATUS='ACTIVE' " +
                "AND L.VALID_FROM<=SYSTIMESTAMP AND (L.VALID_TO IS NULL OR L.VALID_TO>SYSTIMESTAMP) " +
                "ORDER BY T.TERM_DEPOSIT_ID DESC FETCH FIRST 100 ROWS ONLY",actor.userId());
    }

    @GetMapping("/{id}")
    @Operation(summary="View a fixed deposit and its maturity posting references")
    public Map<String,Object> get(@PathVariable long id) {
        var row=db.one("SELECT * FROM MBX_TERM_DEPOSIT WHERE TERM_DEPOSIT_ID=?",id);
        var actor=CurrentActor.get();
        if("CUSTOMER".equals(actor.userType())) {
            if(!actor.cifIds().contains(row.get("PRIMARY_CIF_ID")))
                throw new BusinessException(HttpStatus.FORBIDDEN,"TERM_DEPOSIT_ACCESS","Fixed deposit is outside your access");
            access.account("ACCOUNT_READ",BusinessRepository.number(row,"FUNDING_ACCOUNT_ID"));
        } else access.global("GL_POST");
        return row;
    }

    @PostMapping
    @Transactional
    @Operation(summary="Open a fixed deposit from an owned active INR account")
    public Map<String,Object> open(@Valid @RequestBody OpenRequest request) {
        if(!enabled) throw fail("Fixed deposits are unavailable until migration 011 is installed");
        var actor=CurrentActor.get();
        if(!"CUSTOMER".equals(actor.userType())) throw fail("Customer sign-in required");
        if(request.amount().scale()>2 || request.tenureMonths()<1) throw fail("Enter a valid amount and tenure");
        var prior=db.rows("SELECT * FROM MBX_TERM_DEPOSIT WHERE REQUEST_KEY=?",request.requestKey());
        if(!prior.isEmpty()) {
            var row=prior.get(0);
            if(BusinessRepository.number(row,"PRODUCT_ID")!=request.productId() ||
               BusinessRepository.number(row,"PRODUCT_VERSION_ID")!=request.productVersionId() ||
               BusinessRepository.number(row,"FUNDING_ACCOUNT_ID")!=request.fundingAccountId() ||
               BusinessRepository.number(row,"TENURE_MONTHS")!=request.tenureMonths() ||
               ((BigDecimal)row.get("PRINCIPAL")).compareTo(request.amount())!=0 ||
               !actor.cifIds().contains(row.get("PRIMARY_CIF_ID"))) throw fail("Request reference belongs to different deposit details");
            return row;
        }
        var authorizedAccount=access.account("TXN_POST",request.fundingAccountId());
        var account=db.one("SELECT * FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=? FOR UPDATE",request.fundingAccountId());
        String cif=(String)account.get("PRIMARY_CIF_ID");
        if(!actor.cifIds().contains(cif) || !"ACTIVE".equals(account.get("LIFECYCLE_STATUS")) ||
           !"INR".equals(account.get("CURRENCY_CODE")) ||
           !List.of("SAVINGS","CURRENT").contains(authorizedAccount.get("PRODUCT_TYPE")))
            throw fail("Choose your own active INR savings or current account");
        var customer=db.one("SELECT SEGMENT_CODE FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",cif);
        var definition=products.resolve(BigDecimal.valueOf(request.productId()),(String)account.get("BRANCH_CODE"),
                (String)customer.get("SEGMENT_CODE"),"WEB","INR",OffsetDateTime.now());
        @SuppressWarnings("unchecked") var product=(Map<String,Object>)definition.get("product");
        @SuppressWarnings("unchecked") var version=(Map<String,Object>)definition.get("version");
        if(!"DEPOSIT".equals(product.get("PRODUCT_TYPE")) ||
           BusinessRepository.number(version,"PRODUCT_VERSION_ID")!=request.productVersionId())
            throw fail("Choose an active fixed deposit product and version");
        var term=db.one("SELECT * FROM M03_PM_TERM_DEPOSIT_RULE WHERE PRODUCT_VERSION_ID=?",request.productVersionId());
        if(request.amount().compareTo((BigDecimal)term.get("MIN_DEPOSIT_AMOUNT"))<0 ||
           request.amount().compareTo((BigDecimal)term.get("MAX_DEPOSIT_AMOUNT"))>0 ||
           request.tenureMonths()<BusinessRepository.number(term,"MIN_TENURE_MONTHS") ||
           request.tenureMonths()>BusinessRepository.number(term,"MAX_TENURE_MONTHS"))
            throw fail("Deposit amount or term is outside the approved product limits");
        var rules=db.rows("SELECT * FROM M03_PM_INTEREST_RULE WHERE PRODUCT_VERSION_ID=?",request.productVersionId());
        if(rules.size()!=1) throw fail("Fixed deposit requires exactly one approved interest rule");
        var rule=rules.get(0);
        if(!"FIXED".equals(rule.get("INTEREST_TYPE")) || !"SIMPLE".equals(rule.get("INTEREST_METHOD")) ||
           !"AT_MATURITY".equals(rule.get("PAYOUT_FREQUENCY")))
            throw fail("Fixed deposit needs a fixed simple rate paid at maturity");
        LocalDate start=LocalDate.now(ZONE), maturity=start.plusMonths(request.tenureMonths());
        BigDecimal rate=(BigDecimal)rule.get("FIXED_RATE_PCT");
        BigDecimal earned=DepositInterestMath.fixedTerm(request.amount(),rate,start,maturity,
                (String)rule.get("DAY_COUNT_BASIS"),(String)rule.get("ROUNDING_MODE"));
        long sourceGl=interest.gl(BusinessRepository.number(account,"PRODUCT_VERSION_ID"),"DEPOSIT","CUSTOMER_LIABILITY","LIABILITY");
        long termGl=interest.gl(request.productVersionId(),"TERM_DEPOSIT","TERM_LIABILITY","LIABILITY");
        interest.gl(request.productVersionId(),"TERM_DEPOSIT","INTEREST_EXPENSE","EXPENSE");
        String key="term-fund:"+request.requestKey();
        long txn=transaction(key,"INTERNAL_TRANSFER",request.fundingAccountId(),null,
                BusinessRepository.number(account,"PRODUCT_VERSION_ID"),request.amount(),start,actor.userId());
        long journal=ledger.postJournal(new JournalRequest(key,"TRANSFER",txn,null,null,null,null,null,start,
                "Fixed deposit funding",List.of(
                new JournalLine(sourceGl,request.fundingAccountId(),null,null,"DR",request.amount(),"Fixed deposit placement"),
                new JournalLine(termGl,null,null,null,"CR",request.amount(),"Fixed deposit principal liability"))),actor.userId()).journalId();
        interest.posted(txn,actor.userId());
        var keyHolder=new GeneratedKeyHolder();
        db.jdbc().update(connection->{var statement=connection.prepareStatement(
                "INSERT INTO MBX_TERM_DEPOSIT(REQUEST_KEY,PRIMARY_CIF_ID,FUNDING_ACCOUNT_ID,PRODUCT_ID,PRODUCT_VERSION_ID,"+
                "PRINCIPAL,TENURE_MONTHS,ANNUAL_RATE_PCT,DAY_COUNT_BASIS,ROUNDING_MODE,START_DATE,MATURITY_DATE,MATURITY_INTEREST,FUNDING_JOURNAL_ID) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",new String[]{"TERM_DEPOSIT_ID"});
            Object[] values={request.requestKey(),cif,request.fundingAccountId(),request.productId(),request.productVersionId(),
                    request.amount(),request.tenureMonths(),rate,rule.get("DAY_COUNT_BASIS"),rule.get("ROUNDING_MODE"),start,maturity,earned,journal};
            for(int n=0;n<values.length;n++)statement.setObject(n+1,values[n]);
            return statement;},keyHolder);
        return get(Objects.requireNonNull(keyHolder.getKey()).longValue());
    }

    @PostMapping("/{id}/mature")
    @Operation(summary="Pay a due fixed deposit's principal and earned interest; safe to retry")
    public Map<String,Object> mature(@PathVariable long id) {
        access.global("GL_POST");
        if(!enabled) throw fail("Fixed deposit maturity is not enabled");
        return Objects.requireNonNull(tx.execute(status->matureLocked(id)));
    }

    @Scheduled(cron="0 30 1 * * *",zone="Asia/Kolkata")
    public void scheduledMaturities() {
        if(!enabled) return;
        try {
            var due=db.rows("SELECT TERM_DEPOSIT_ID FROM MBX_TERM_DEPOSIT WHERE STATUS='ACTIVE' " +
                    "AND MATURITY_DATE<=? ORDER BY TERM_DEPOSIT_ID FETCH FIRST 200 ROWS ONLY",LocalDate.now(ZONE));
            for(var item:due) {
                long id=BusinessRepository.number(item,"TERM_DEPOSIT_ID");
                try { tx.execute(status->matureLocked(id)); }
                catch(Exception ex) { log.error("Fixed deposit {} maturity failed",id,ex); }
            }
        } catch(Exception ex) { log.error("Fixed deposit maturity scan failed",ex); }
    }

    private Map<String,Object> matureLocked(long id) {
        var term=db.one("SELECT * FROM MBX_TERM_DEPOSIT WHERE TERM_DEPOSIT_ID=? FOR UPDATE",id);
        if("MATURED".equals(term.get("STATUS"))) return term;
        LocalDate maturity=BusinessRepository.localDate(term.get("MATURITY_DATE"));
        LocalDate today=LocalDate.now(ZONE);
        if(today.isBefore(maturity)) throw fail("Fixed deposit has not reached maturity");
        long accountId=BusinessRepository.number(term,"FUNDING_ACCOUNT_ID");
        var account=db.one("SELECT A.*,P.PRODUCT_TYPE FROM M04_BANK_ACCOUNT A JOIN M03_PM_PRODUCT P ON P.PRODUCT_ID=A.PRODUCT_ID WHERE A.ACCOUNT_ID=?",accountId);
        if(!"ACTIVE".equals(account.get("LIFECYCLE_STATUS"))) throw fail("Funding account must be active for maturity payout");
        long sourceGl=interest.gl(BusinessRepository.number(account,"PRODUCT_VERSION_ID"),"DEPOSIT","CUSTOMER_LIABILITY","LIABILITY");
        long termVersion=BusinessRepository.number(term,"PRODUCT_VERSION_ID");
        long termGl=interest.gl(termVersion,"TERM_DEPOSIT","TERM_LIABILITY","LIABILITY");
        long expenseGl=interest.gl(termVersion,"TERM_DEPOSIT","INTEREST_EXPENSE","EXPENSE");
        BigDecimal principal=(BigDecimal)term.get("PRINCIPAL"), earned=(BigDecimal)term.get("MATURITY_INTEREST");
        String actor="TERM_DEPOSIT_WORKER",key="term-mature:"+id;
        long principalTxn=transaction(key+":principal","INTERNAL_TRANSFER",null,accountId,
                BusinessRepository.number(account,"PRODUCT_VERSION_ID"),principal,today,actor);
        long principalJournal=ledger.postJournal(new JournalRequest(key+":principal","TRANSFER",principalTxn,
                null,null,null,null,null,today,"Fixed deposit principal maturity",List.of(
                new JournalLine(termGl,null,null,null,"DR",principal,"Release fixed deposit principal"),
                new JournalLine(sourceGl,accountId,null,null,"CR",principal,"Fixed deposit principal returned"))),actor).journalId();
        interest.posted(principalTxn,actor);
        Long interestJournal=null;
        if(earned.signum()>0) {
            long interestTxn=transaction(key+":interest","INTEREST",null,accountId,
                    BusinessRepository.number(account,"PRODUCT_VERSION_ID"),earned,today,actor);
            interestJournal=ledger.postJournal(new JournalRequest(key+":interest","INTEREST",interestTxn,
                    null,null,null,null,null,today,"Fixed deposit interest at maturity",List.of(
                    new JournalLine(expenseGl,null,null,null,"DR",earned,"Fixed deposit interest expense"),
                    new JournalLine(sourceGl,accountId,null,null,"CR",earned,"Fixed deposit interest credited"))),actor).journalId();
            interest.posted(interestTxn,actor);
        }
        db.jdbc().update("UPDATE MBX_TERM_DEPOSIT SET STATUS='MATURED',PRINCIPAL_JOURNAL_ID=?,"+
                "INTEREST_JOURNAL_ID=?,MATURED_AT=SYSTIMESTAMP WHERE TERM_DEPOSIT_ID=?",
                principalJournal,interestJournal,id);
        return db.one("SELECT * FROM MBX_TERM_DEPOSIT WHERE TERM_DEPOSIT_ID=?",id);
    }

    private long transaction(String key,String type,Long source,Long target,long version,BigDecimal amount,
                             LocalDate date,String actor) {
        var holder=new GeneratedKeyHolder();
        db.jdbc().update(connection->{var statement=connection.prepareStatement(
                "INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,"+
                "TXN_TYPE,STATUS,SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID,PRODUCT_VERSION_ID,AMOUNT,VALUE_DATE) " +
                "VALUES (?,'TERM_DEPOSIT',?,?,?,?,'VALIDATED',?,?,?,?,?)",new String[]{"TXN_ID"});
            Object[] values={actor,key,CustomerHashService.digest(key),UUID.randomUUID().toString(),type,
                    source,target,version,amount,date};
            for(int n=0;n<values.length;n++)statement.setObject(n+1,values[n]);
            return statement;},holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    private BusinessException fail(String message) {
        return new BusinessException(HttpStatus.CONFLICT,"TERM_DEPOSIT_RULE",message);
    }
}
