import com.fasterxml.jackson.databind.*;
import com.moneybags.account.api.Models.ProjectionEvent;
import com.moneybags.account.db.*;
import com.moneybags.account.domain.ProjectionPolicy;
import com.moneybags.integration.CustomerHashService;
import com.moneybags.txn.api.Contracts.*;
import com.moneybags.txn.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

/** User-confirmed synthetic opening funding. Default rehearsal rolls back; final spendable balance stays unchanged. */
public class OracleAccount6DemoFunding {
    static final String KEY="demo-opening:account-6:20261015",ACTOR="demo-funding-reconciliation";
    static final BigDecimal AMOUNT=new BigDecimal("500000");
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public static void main(String[] args)throws Exception{
        if(args.length<1||args.length>2||(args.length==2&&!args[1].equals("--apply")))throw new IllegalArgumentException("Usage: report.json [--apply]; default rolls back");
        boolean apply=args.length==2;
        var source=new DriverManagerDataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
        var props=new Properties();props.setProperty("oracle.net.CONNECT_TIMEOUT","5000");props.setProperty("oracle.jdbc.ReadTimeout","15000");source.setConnectionProperties(props);
        var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(30);
        var ledger=new LedgerService(jdbc,JSON,new AccountCatalog(jdbc),new PaymentCatalog(jdbc),Clock.systemUTC());
        var accounts=new AccountStore(JdbcClient.create(jdbc));var events=new EventStore(JdbcClient.create(jdbc),JSON);
        var report=new LinkedHashMap<String,Object>();report.put("accountId",6);report.put("fundingSource","USER_CONFIRMED_SYNTHETIC_DEMO_OPENING_FUNDS");
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
            try{
                var account=one(jdbc,"SELECT ACCOUNT_NUMBER,ACCOUNT_STATUS,PRODUCT_VERSION_ID FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=6 FOR UPDATE");
                require("MBMUS0000000000026".equals(account.get("ACCOUNT_NUMBER"))&&"ACTIVE".equals(account.get("ACCOUNT_STATUS")),"Account identity/status changed");
                var before=one(jdbc,"SELECT * FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=6 AND CURRENCY_CODE='INR' FOR UPDATE");
                var prior=rows(jdbc,"SELECT JOURNAL_ID FROM M05_GL_JOURNAL WHERE POSTING_KEY='"+KEY+"'");
                if(!prior.isEmpty()){
                    require("Y".equals(one(jdbc,"SELECT IS_MATCHED FROM M05_V_ACCOUNT_POSITION_RECON WHERE BANK_ACCOUNT_ID=6").get("IS_MATCHED")),"Prior opening journal no longer reconciles");
                    report.put("status","ALREADY_APPLIED");report.put("journalId",prior.get(0).get("JOURNAL_ID"));report.put("position",before);status.setRollbackOnly();return;
                }
                require(decimal(before,"POSTED_BALANCE").compareTo(AMOUNT)==0&&"6".equals(before.get("POSITION_VERSION"))&&"18".equals(before.get("LAST_JOURNAL_ID")),"Reviewed pre-repair position changed");
                for(String field:List.of("ACTIVE_HOLD_AMOUNT","ACTIVE_LIEN_AMOUNT","ACTIVE_BLOCK_AMOUNT","OVERDRAFT_LIMIT"))require(decimal(before,field).signum()==0,"Account controls changed");
                require(count(jdbc,"SELECT COUNT(*) FROM M05_FUNDS_HOLD WHERE BANK_ACCOUNT_ID=6 AND STATUS='ACTIVE'")==0,"Unfinished hold exists");
                require(count(jdbc,"SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION WHERE (SOURCE_ACCOUNT_ID=6 OR DESTINATION_ACCOUNT_ID=6) AND STATUS NOT IN ('SETTLED','REJECTED','FAILED','CANCELLED','RETURNED')")==0,"Unfinished payment exists");
                require(decimal(one(jdbc,"SELECT GL_DERIVED_BALANCE FROM M05_V_ACCOUNT_POSITION_RECON WHERE BANK_ACCOUNT_ID=6"),"GL_DERIVED_BALANCE").signum()==0,"Original ledger evidence changed");
                require(count(jdbc,"SELECT COUNT(*) FROM M05_GL_POSTING WHERE BANK_ACCOUNT_ID=6")==2,"Original posting count changed");
                long oldJournal=count(jdbc,"SELECT COALESCE(MAX(JOURNAL_ID),0) FROM M05_GL_JOURNAL"),oldPosting=count(jdbc,"SELECT COALESCE(MAX(POSTING_ID),0) FROM M05_GL_POSTING");
                String journals=hash(rows(jdbc,"SELECT * FROM M05_GL_JOURNAL WHERE JOURNAL_ID<="+oldJournal+" ORDER BY JOURNAL_ID"));
                String postings=hash(rows(jdbc,"SELECT * FROM M05_GL_POSTING WHERE POSTING_ID<="+oldPosting+" ORDER BY POSTING_ID"));
                String reserves=hash(rows(jdbc,"SELECT * FROM M07_RESERVE_POSITION ORDER BY RESERVE_ACCOUNT_ID"));
                String otherPositions=hash(rows(jdbc,"SELECT * FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID<>6 ORDER BY BANK_ACCOUNT_ID"));
                var projection=accounts.projectionSnapshot(6);require(projection.ledgerBalance().signum()==0,"Account projection changed");
                long liability=count(jdbc,"SELECT MIN(GL_ACCOUNT_ID) FROM M05_GL_POSTING WHERE BANK_ACCOUNT_ID=6");
                require(count(jdbc,"SELECT COUNT(DISTINCT GL_ACCOUNT_ID) FROM M05_GL_POSTING WHERE BANK_ACCOUNT_ID=6")==1&&count(jdbc,"SELECT COUNT(*) FROM M05_GL_ACCOUNT WHERE GL_ACCOUNT_ID="+liability+" AND ACCOUNT_CLASS='LIABILITY' AND NORMAL_SIDE='CR' AND CURRENCY_CODE='INR' AND ACTIVE_FLAG='Y'")==1,"Historical customer liability GL is not usable");
                require(count(jdbc,"SELECT COUNT(*) FROM M05_GL_ACCOUNT WHERE GL_CODE='DEMO_OPENING_FUNDS'")==0,"Demo opening GL already exists without its journal; investigate");
                jdbc.update("INSERT INTO M05_GL_ACCOUNT(GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE) VALUES ('DEMO_OPENING_FUNDS','Synthetic demo opening funds','ASSET','DR')");
                long asset=count(jdbc,"SELECT GL_ACCOUNT_ID FROM M05_GL_ACCOUNT WHERE GL_CODE='DEMO_OPENING_FUNDS'");
                LocalDate today=LocalDate.now(ZoneId.of("Asia/Kolkata"));
                jdbc.update("INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,TARGET_ACCOUNT_ID,PRODUCT_VERSION_ID,AMOUNT,VALUE_DATE) VALUES (?,'DEMO_REPAIR',?,?,?,'OPENING_FUNDING','VALIDATED',6,?,?,?)",ACTOR,KEY,CustomerHashService.digest(KEY),KEY,Long.parseLong(account.get("PRODUCT_VERSION_ID").toString()),AMOUNT,today);
                long txn=count(jdbc,"SELECT TXN_ID FROM M05_TXN_TRANSACTION_LOG WHERE ORIGINATOR_ID='"+ACTOR+"' AND CHANNEL_CODE='DEMO_REPAIR' AND REQUEST_KEY='"+KEY+"'");
                // Remove the unjournaled seed offset only inside this locked transaction.
                // The validated opening journal restores exactly the same spendable balance before commit.
                require(jdbc.update("UPDATE M05_ACCOUNT_POSITION SET POSTED_BALANCE=0 WHERE BANK_ACCOUNT_ID=6 AND POSTED_BALANCE=500000 AND POSITION_VERSION=6 AND LAST_JOURNAL_ID=18")==1,"Concurrent position change");
                var request=new JournalRequest(KEY,"OPENING_FUNDING",txn,null,null,null,null,null,today,
                    "Recognize user-confirmed synthetic demo opening funds; preserve existing customer balance",
                    List.of(new JournalLine(asset,null,null,null,"DR",AMOUNT,"Synthetic demo funding; no RBI reserve or physical cash movement"),new JournalLine(liability,6L,null,null,"CR",AMOUNT,"Previously unjournaled synthetic opening balance")));
                var journal=ledger.postJournal(request,ACTOR);require(journal.journalId()==ledger.postJournal(request,ACTOR).journalId(),"Opening replay duplicated");
                jdbc.update("UPDATE M05_TXN_TRANSACTION_LOG SET STATUS='POSTED',UPDATED_AT=SYSTIMESTAMP WHERE TXN_ID=?",txn);
                jdbc.update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,NULL,'RECEIVED',?,'SYNTHETIC_DEMO_OPENING')",txn,ACTOR);
                jdbc.update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,'RECEIVED','VALIDATED',?,'SYNTHETIC_DEMO_OPENING')",txn,ACTOR);
                jdbc.update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,'VALIDATED','POSTED',?,'SYNTHETIC_DEMO_OPENING')",txn,ACTOR);
                String eventId=UUID.randomUUID().toString();BigDecimal zero=BigDecimal.ZERO;
                var event=new ProjectionEvent(eventId,6L,"RECONCILIATION_CORRECTION",projection.version()+1,journal.journalId(),null,txn,null,AMOUNT,zero,zero,zero,AMOUNT,projection.blockedBalance(),projection.lienBalance(),projection.overdraftLimit(),OffsetDateTime.now(),KEY);
                ProjectionPolicy.validate(projection,event);events.projection(event);events.inbox(eventId,event.eventType(),"MB_TXN",6);
                var after=one(jdbc,"SELECT * FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=6 AND CURRENCY_CODE='INR'");
                require(decimal(after,"POSTED_BALANCE").compareTo(decimal(before,"POSTED_BALANCE"))==0&&decimal(after,"SPENDABLE_BALANCE").compareTo(decimal(before,"SPENDABLE_BALANCE"))==0,"Customer balance changed");
                require(count(jdbc,"SELECT COUNT(*) FROM M05_V_ACCOUNT_POSITION_RECON WHERE IS_MATCHED='N'")==0&&count(jdbc,"SELECT COUNT(*) FROM M05_V_GL_JOURNAL_CONTROL WHERE IS_BALANCED='N'")==0,"Ledger reconciliation failed");
                require(journals.equals(hash(rows(jdbc,"SELECT * FROM M05_GL_JOURNAL WHERE JOURNAL_ID<="+oldJournal+" ORDER BY JOURNAL_ID")))&&postings.equals(hash(rows(jdbc,"SELECT * FROM M05_GL_POSTING WHERE POSTING_ID<="+oldPosting+" ORDER BY POSTING_ID"))),"Existing immutable history changed");
                require(reserves.equals(hash(rows(jdbc,"SELECT * FROM M07_RESERVE_POSITION ORDER BY RESERVE_ACCOUNT_ID")))&&otherPositions.equals(hash(rows(jdbc,"SELECT * FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID<>6 ORDER BY BANK_ACCOUNT_ID"))),"Another account or RBI reserve changed");
                report.put("before",before);report.put("after",after);report.put("journalId",journal.journalId());report.put("transactionId",txn);report.put("syntheticFundingGlId",asset);report.put("customerBalanceUnchanged",true);report.put("existingJournalHistoryUnchanged",true);report.put("otherAccountsAndRbiReservesUnchanged",true);report.put("ledgerReconciled",true);
                events.audit(6,"SYNTHETIC_OPENING_RECONCILED",ACTOR,"SUCCESS",KEY,report);
                if(!apply)status.setRollbackOnly();report.put("status",apply?"COMMITTED":"ROLLED_BACK");
            }catch(Exception failure){throw new IllegalStateException("Synthetic opening reconciliation failed",failure);}
        });
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);System.out.println("Synthetic demo opening funding: "+report.get("status"));
    }
    static List<Map<String,Object>> rows(JdbcTemplate jdbc,String sql){return jdbc.query(sql,(rs,n)->{var row=new LinkedHashMap<String,Object>();var meta=rs.getMetaData();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),rs.getString(i));return row;});}
    static Map<String,Object> one(JdbcTemplate jdbc,String sql){var rows=rows(jdbc,sql);require(rows.size()==1,"Expected one evidence row");return rows.get(0);}
    static long count(JdbcTemplate jdbc,String sql){return Long.parseLong(one(jdbc,sql).values().iterator().next().toString());}
    static BigDecimal decimal(Map<String,Object> row,String key){return new BigDecimal(row.get(key).toString());}
    static String hash(Object value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}
    static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
