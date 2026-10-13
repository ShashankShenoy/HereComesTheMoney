package com.moneybags.integration;

import com.moneybags.common.api.BusinessException;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.txn.api.Contracts.JournalLine;
import com.moneybags.txn.api.Contracts.JournalRequest;
import com.moneybags.txn.core.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Monthly savings interest from daily posted balances, with one journal per account and month. */
@RestController
@RequestMapping("/api/v1/deposit-interest")
public class DepositInterestController {
    private static final Logger log = LoggerFactory.getLogger(DepositInterestController.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private final BusinessRepository db;
    private final BankingAccess access;
    private final CustomerHashService hashes;
    private final LedgerService ledger;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public DepositInterestController(BusinessRepository db, BankingAccess access, CustomerHashService hashes,
                                     LedgerService ledger, TransactionTemplate tx,
                                     @Value("${moneybags.deposit-interest.enabled:false}") boolean enabled) {
        this.db=db; this.access=access; this.hashes=hashes; this.ledger=ledger; this.tx=tx; this.enabled=enabled;
    }

    @GetMapping("/accounts/{id}")
    @Operation(summary="List credited savings interest for an account")
    public List<Map<String,Object>> accountHistory(@PathVariable long id,
            @RequestHeader(value="X-Customer-Hash",required=false) String hash) {
        access.account("TXN_READ",id);
        hashes.require(id,hash);
        return db.rows("SELECT PERIOD_MONTH,INTEREST_AMOUNT,TXN_ID,JOURNAL_ID,POSTED_AT " +
                "FROM MBX_SAVINGS_INTEREST_PERIOD WHERE ACCOUNT_ID=? ORDER BY PERIOD_MONTH DESC FETCH FIRST 60 ROWS ONLY",id);
    }

    @GetMapping("/accounts/{id}/accrual")
    @Operation(summary="Estimate unposted savings interest through the previous day")
    public Map<String,Object> accrualPreview(@PathVariable long id,
            @RequestHeader(value="X-Customer-Hash",required=false) String hash) {
        access.account("TXN_READ",id);
        hashes.require(id,hash);
        if(!enabled)return Map.of("status","NOT_AVAILABLE");
        var rows=db.rows("SELECT A.*,P.PRODUCT_TYPE FROM M04_BANK_ACCOUNT A JOIN M03_PM_PRODUCT P ON P.PRODUCT_ID=A.PRODUCT_ID WHERE A.ACCOUNT_ID=?",id);
        if(rows.size()!=1 || !"SAVINGS".equals(rows.get(0).get("PRODUCT_TYPE")))throw fail("This is not a savings account");
        var account=rows.get(0);
        if(!"ACTIVE".equals(account.get("LIFECYCLE_STATUS")))return Map.of("status","NOT_ACTIVE");
        LocalDate through=LocalDate.now(ZONE).minusDays(1);
        LocalDate first=YearMonth.from(LocalDate.now(ZONE)).atDay(1);
        LocalDate rollout=BusinessRepository.localDate(db.one("SELECT START_DATE FROM MBX_INTEREST_ROLLOUT WHERE CONFIG_ID=1").get("START_DATE"));
        LocalDate activated=BusinessRepository.localDate(account.get("ACTIVATED_AT"));
        if(first.isBefore(rollout))first=rollout;
        if(activated!=null && first.isBefore(activated))first=activated;
        if(through.isBefore(first))return Map.of("status","NOT_STARTED","asOf",through.toString(),"accrued",BigDecimal.ZERO);
        Accrual result=accrue(account,id,first,through);
        if(result.rounding()==null)return Map.of("status","NOT_CONFIGURED","asOf",through.toString(),"accrued",BigDecimal.ZERO);
        return Map.of("status","ESTIMATE","asOf",through.toString(),"accrued",
                result.value().setScale(2,DepositInterestMath.rounding(result.rounding())));
    }

    @PostMapping("/runs")
    @Operation(summary="Post a completed month's savings interest; safe to retry")
    public List<Map<String,Object>> run(@RequestParam String period) {
        access.global("GL_POST");
        if (!enabled) throw fail("Deposit interest is not enabled until migration 011 and GL mappings are installed");
        YearMonth month;
        try { month=YearMonth.parse(period); }
        catch (Exception ex) { throw new BusinessException(HttpStatus.BAD_REQUEST,"INTEREST_PERIOD","Use YYYY-MM"); }
        if (!month.isBefore(YearMonth.from(LocalDate.now(ZONE)))) throw fail("Only completed months can be posted");
        return runMonth(month);
    }

    @Scheduled(cron="0 10 1 1 * *",zone="Asia/Kolkata")
    public void scheduled() {
        if (!enabled) return;
        try {
            var result=runMonth(YearMonth.from(LocalDate.now(ZONE).minusMonths(1)));
            result.stream().filter(r->"ERROR".equals(r.get("status"))).forEach(r->log.error("Savings interest: {}",r));
        } catch (Exception ex) { log.error("Monthly savings interest run failed",ex); }
    }

    private List<Map<String,Object>> runMonth(YearMonth month) {
        LocalDate start=BusinessRepository.localDate(db.one("SELECT START_DATE FROM MBX_INTEREST_ROLLOUT WHERE CONFIG_ID=1").get("START_DATE"));
        if (month.atEndOfMonth().isBefore(start)) throw fail("The requested month predates deposit-interest rollout");
        var accounts=db.rows("SELECT A.ACCOUNT_ID FROM M04_BANK_ACCOUNT A JOIN M03_PM_PRODUCT P ON P.PRODUCT_ID=A.PRODUCT_ID " +
                "WHERE P.PRODUCT_TYPE='SAVINGS' AND A.CURRENCY_CODE='INR' AND A.LIFECYCLE_STATUS='ACTIVE' " +
                "ORDER BY A.ACCOUNT_ID");
        List<Map<String,Object>> result=new ArrayList<>();
        for(var account:accounts) {
            long id=BusinessRepository.number(account,"ACCOUNT_ID");
            try { result.add(Objects.requireNonNull(tx.execute(status->postMonth(id,month,start)))); }
            catch (Exception ex) { result.add(Map.of("accountId",id,"status","ERROR","message",ex.getMessage()==null?"Posting failed":ex.getMessage())); }
        }
        return result;
    }

    private Map<String,Object> postMonth(long id, YearMonth month, LocalDate rollout) {
        var account=db.one("SELECT A.* FROM M04_BANK_ACCOUNT A WHERE A.ACCOUNT_ID=? FOR UPDATE",id);
        var existing=db.rows("SELECT INTEREST_AMOUNT,TXN_ID,JOURNAL_ID FROM MBX_SAVINGS_INTEREST_PERIOD " +
                "WHERE ACCOUNT_ID=? AND PERIOD_MONTH=?",id,month.atDay(1));
        if (!existing.isEmpty()) return Map.of("accountId",id,"status","ALREADY_POSTED","amount",existing.get(0).get("INTEREST_AMOUNT"));
        if (!"ACTIVE".equals(account.get("LIFECYCLE_STATUS"))) throw fail("Savings account is not active");
        LocalDate activated=BusinessRepository.localDate(account.get("ACTIVATED_AT"));
        LocalDate first=month.atDay(1);
        if (first.isBefore(rollout)) first=rollout;
        if (activated!=null && first.isBefore(activated)) first=activated;
        Accrual calculation=accrue(account,id,first,month.atEndOfMonth());
        BigDecimal accrued=calculation.value();
        String rounding=calculation.rounding();
        if(rounding==null) return Map.of("accountId",id,"status","NOT_CONFIGURED",
                "message","No approved savings interest rule applies to this month");
        BigDecimal amount=accrued.setScale(2,DepositInterestMath.rounding(rounding));
        Long txn=null,journal=null;
        if(amount.signum()>0) {
            long version=BusinessRepository.number(account,"PRODUCT_VERSION_ID");
            long liability=gl(version,"INTEREST","CUSTOMER_LIABILITY","LIABILITY");
            long expense=gl(version,"INTEREST","INTEREST_EXPENSE","EXPENSE");
            String key="savings-interest:"+id+":"+month;
            String actor="DEPOSIT_INTEREST_WORKER";
            LocalDate valueDate=month.atEndOfMonth().plusDays(1);
            txn=transaction(key,"INTEREST",id,version,amount,valueDate,actor);
            journal=ledger.postJournal(new JournalRequest(key,"INTEREST",txn,null,null,null,null,null,valueDate,
                    "Savings interest for "+month,List.of(
                    new JournalLine(expense,null,null,null,"DR",amount,"Savings interest expense"),
                    new JournalLine(liability,id,null,null,"CR",amount,"Savings interest "+month))),actor).journalId();
            posted(txn,actor);
        }
        db.jdbc().update("INSERT INTO MBX_SAVINGS_INTEREST_PERIOD(ACCOUNT_ID,PERIOD_MONTH,INTEREST_AMOUNT,TXN_ID,JOURNAL_ID) VALUES (?,?,?,?,?)",
                id,month.atDay(1),amount,txn,journal);
        db.jdbc().update("UPDATE M04_BANK_ACCOUNT SET INTEREST_ACCRUED=0,LAST_INTEREST_CALCULATION_AT=SYSTIMESTAMP," +
                "LAST_INTEREST_POSTING_AT=CASE WHEN ? IS NOT NULL THEN SYSTIMESTAMP ELSE LAST_INTEREST_POSTING_AT END WHERE ACCOUNT_ID=?",journal,id);
        return Map.of("accountId",id,"status","POSTED","amount",amount);
    }

    private record Accrual(BigDecimal value,String rounding) { }
    private Accrual accrue(Map<String,Object> account,long id,LocalDate first,LocalDate last) {
        BigDecimal accrued=BigDecimal.ZERO;
        String rounding=null;
        for(LocalDate day=first;!day.isAfter(last);day=day.plusDays(1)) {
            long version=versionOn(account,day);
            var rules=db.rows("SELECT INTEREST_TYPE,INTEREST_METHOD,FIXED_RATE_PCT,DAY_COUNT_BASIS,COMPOUND_FREQUENCY,PAYOUT_FREQUENCY,ROUNDING_MODE " +
                    "FROM M03_PM_INTEREST_RULE WHERE PRODUCT_VERSION_ID=?",version);
            // Existing savings accounts may predate the first approved interest rule.
            // Interest begins on the first configured day; a later gap is an error.
            if(rules.isEmpty() && rounding==null) continue;
            if(rules.size()!=1) throw fail("Savings version needs exactly one interest rule");
            var rule=rules.get(0);
            if(!"FIXED".equals(rule.get("INTEREST_TYPE")) || !"SIMPLE".equals(rule.get("INTEREST_METHOD")) ||
                    !"MONTHLY".equals(rule.get("PAYOUT_FREQUENCY")) || !"NONE".equals(rule.get("COMPOUND_FREQUENCY")))
                throw fail("Savings payout supports fixed simple interest, monthly payout and no interim compounding");
            String mode=(String)rule.get("ROUNDING_MODE");
            if(rounding!=null && !rounding.equals(mode)) throw fail("Rounding rule changed during the interest month");
            rounding=mode;
            BigDecimal rate=(BigDecimal)rule.get("FIXED_RATE_PCT");
            var overrides=db.rows("SELECT RATE_PCT FROM M04_ACCOUNT_INTEREST_OVERRIDE WHERE ACCOUNT_ID=? " +
                    "AND PRODUCT_VERSION_ID=? AND EFFECTIVE_FROM_AT<? AND (EFFECTIVE_TO_AT IS NULL OR EFFECTIVE_TO_AT>=?)",
                    id,version,day.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime(),day.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
            if(overrides.size()>1) throw fail("Overlapping account interest overrides");
            if(!overrides.isEmpty()) rate=(BigDecimal)overrides.get(0).get("RATE_PCT");
            BigDecimal balance=Objects.requireNonNull(db.jdbc().queryForObject(
                    "SELECT NVL(SUM(CASE WHEN P.ENTRY_SIDE='CR' THEN P.AMOUNT ELSE -P.AMOUNT END),0) " +
                    "FROM M05_GL_POSTING P JOIN M05_GL_JOURNAL J ON J.JOURNAL_ID=P.JOURNAL_ID " +
                    "WHERE P.BANK_ACCOUNT_ID=? AND J.VALUE_DATE<=?",BigDecimal.class,id,day));
            accrued=accrued.add(DepositInterestMath.daily(balance,rate,day,(String)rule.get("DAY_COUNT_BASIS")));
        }
        return new Accrual(accrued,rounding);
    }

    private long versionOn(Map<String,Object> account,LocalDate day) {
        var future=db.rows("SELECT FROM_PRODUCT_VERSION_ID FROM M04_ACCOUNT_PRODUCT_VERSION_HISTORY " +
                "WHERE ACCOUNT_ID=? AND ADOPTED_AT>=? ORDER BY ADOPTED_AT FETCH FIRST 1 ROW ONLY",
                account.get("ACCOUNT_ID"),day.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        return future.isEmpty()?BusinessRepository.number(account,"PRODUCT_VERSION_ID"):
                BusinessRepository.number(future.get(0),"FROM_PRODUCT_VERSION_ID");
    }

    long gl(long version,String type,String role,String expectedClass) {
        var rows=db.rows("SELECT G.GL_ACCOUNT_ID FROM M05_GL_PRODUCT_MAPPING M JOIN M05_GL_ACCOUNT G ON G.GL_ACCOUNT_ID=M.GL_ACCOUNT_ID " +
                "WHERE M.PRODUCT_VERSION_ID=? AND M.POSTING_TYPE=? AND M.GL_ROLE_CODE=? AND M.STATUS='ACTIVE' " +
                "AND M.EFFECTIVE_FROM<=SYSDATE AND (M.EFFECTIVE_TO IS NULL OR M.EFFECTIVE_TO>=SYSDATE) " +
                "AND G.ACCOUNT_CLASS=? AND G.ACTIVE_FLAG='Y'",version,type,role,expectedClass);
        if(rows.size()!=1) throw fail("Missing or ambiguous "+type+" GL mapping: "+role);
        return BusinessRepository.number(rows.get(0),"GL_ACCOUNT_ID");
    }

    long transaction(String key,String type,long account,long version,BigDecimal amount,LocalDate date,String actor) {
        var holder=new GeneratedKeyHolder();
        db.jdbc().update(connection->{var statement=connection.prepareStatement(
                "INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID," +
                "TXN_TYPE,STATUS,TARGET_ACCOUNT_ID,PRODUCT_VERSION_ID,AMOUNT,VALUE_DATE) " +
                "VALUES (?,'INTEREST',?,?,?,?,'VALIDATED',?,?,?,?)",new String[]{"TXN_ID"});
            Object[] values={actor,key,CustomerHashService.digest(key),UUID.randomUUID().toString(),type,account,version,amount,date};
            for(int n=0;n<values.length;n++)statement.setObject(n+1,values[n]);
            return statement;},holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    void posted(long txn,String actor) {
        db.jdbc().update("UPDATE M05_TXN_TRANSACTION_LOG SET STATUS='POSTED' WHERE TXN_ID=?",txn);
        for(String[] transition:List.of(new String[]{null,"RECEIVED"},new String[]{"RECEIVED","VALIDATED"},new String[]{"VALIDATED","POSTED"}))
            db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) " +
                    "VALUES (?,?,?,?, 'DEPOSIT_INTEREST')",txn,transition[0],transition[1],actor);
    }

    static BusinessException fail(String message) {
        return new BusinessException(HttpStatus.CONFLICT,"DEPOSIT_INTEREST_RULE",message);
    }
}
