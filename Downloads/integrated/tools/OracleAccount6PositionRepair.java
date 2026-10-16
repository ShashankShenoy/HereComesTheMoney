import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Narrow repair for the documented account-6 exception. Default mode always rolls back. */
public class OracleAccount6PositionRepair {
    static final String CORRELATION="account6-position-rebuild-20261015";
    static final String ACTOR="reconciliation-maintenance";
    static final ObjectMapper JSON=new ObjectMapper();
    public static void main(String[] args)throws Exception{
        if(args.length<1||args.length>2||(args.length==2&&!args[1].equals("--apply")))
            throw new IllegalArgumentException("Usage: OracleAccount6PositionRepair report.json [--apply]; default is rollback rehearsal");
        boolean apply=args.length==2;
        var properties=new Properties();properties.setProperty("user",System.getenv("DB_USERNAME"));properties.setProperty("password",System.getenv("DB_PASSWORD"));
        properties.setProperty("oracle.net.CONNECT_TIMEOUT","5000");properties.setProperty("oracle.jdbc.ReadTimeout","15000");
        var report=new LinkedHashMap<String,Object>();report.put("accountId",6);report.put("mode",apply?"APPLY":"ROLLBACK_REHEARSAL");
        try(var db=DriverManager.getConnection(System.getenv("DB_URL"),properties)){
            db.setAutoCommit(false);
            try{
                var account=one(db,"SELECT ACCOUNT_NUMBER,ACCOUNT_STATUS,LEDGER_BALANCE,AVAILABLE_BALANCE FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=6 FOR UPDATE");
                require("MBMUS0000000000026".equals(account.get("ACCOUNT_NUMBER"))&&"ACTIVE".equals(account.get("ACCOUNT_STATUS")),"Unexpected account identity or status");
                var before=one(db,"SELECT * FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=6 AND CURRENCY_CODE='INR' FOR UPDATE");
                if(count(db,"SELECT COUNT(*) FROM M05_SECURITY_AUDIT_EVENT WHERE CORRELATION_ID='"+CORRELATION+"' AND ACTION_CODE='ACCOUNT_POSITION_REBUILT'")>0){
                    require(decimal(before,"POSTED_BALANCE").signum()==0,"Previously repaired position changed; inspect instead of replaying");
                    report.put("status","ALREADY_APPLIED");report.put("position",before);db.rollback();
                }else{
                    require(decimal(before,"POSTED_BALANCE").compareTo(new BigDecimal("500000"))==0,"Expected stored balance changed");
                    require("4".equals(before.get("POSITION_VERSION"))&&"18".equals(before.get("LAST_JOURNAL_ID")),"Position history changed since reviewed evidence");
                    for(String field:List.of("ACTIVE_HOLD_AMOUNT","ACTIVE_LIEN_AMOUNT","ACTIVE_BLOCK_AMOUNT","OVERDRAFT_LIMIT"))require(decimal(before,field).signum()==0,"Account controls or holds changed");
                    require(decimal(account,"LEDGER_BALANCE").signum()==0&&decimal(account,"AVAILABLE_BALANCE").signum()==0,"Module 4 projection changed");
                    require(count(db,"SELECT COUNT(*) FROM M05_FUNDS_HOLD WHERE BANK_ACCOUNT_ID=6 AND STATUS='ACTIVE'")==0,"Unfinished payment hold exists");
                    require(count(db,"SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION WHERE (SOURCE_ACCOUNT_ID=6 OR DESTINATION_ACCOUNT_ID=6) AND STATUS NOT IN ('SETTLED','REJECTED','FAILED','CANCELLED','RETURNED')")==0,"Unfinished payment exists");
                    var postings=OracleRbiLedgerAudit.rows(db,"SELECT P.POSTING_ID,P.JOURNAL_ID,P.ENTRY_SIDE,P.AMOUNT FROM M05_GL_POSTING P WHERE P.BANK_ACCOUNT_ID=6 ORDER BY POSTING_ID");
                    require(postings.size()==2&&"13".equals(postings.get(0).get("JOURNAL_ID"))&&"18".equals(postings.get(1).get("JOURNAL_ID")),"Immutable posting evidence changed");
                    var recon=one(db,"SELECT GL_DERIVED_BALANCE FROM M05_V_ACCOUNT_POSITION_RECON WHERE BANK_ACCOUNT_ID=6");
                    require(decimal(recon,"GL_DERIVED_BALANCE").signum()==0,"Authoritative ledger balance changed");
                    String journalsBefore=hash(OracleRbiLedgerAudit.rows(db,"SELECT * FROM M05_GL_JOURNAL ORDER BY JOURNAL_ID"));
                    String postingsBefore=hash(OracleRbiLedgerAudit.rows(db,"SELECT * FROM M05_GL_POSTING ORDER BY POSTING_ID"));
                    try(var update=db.prepareStatement("UPDATE M05_ACCOUNT_POSITION SET POSTED_BALANCE=0,POSITION_VERSION=POSITION_VERSION+1,AS_OF=SYSTIMESTAMP WHERE BANK_ACCOUNT_ID=6 AND CURRENCY_CODE='INR' AND POSTED_BALANCE=500000 AND POSITION_VERSION=4 AND LAST_JOURNAL_ID=18")){
                        require(update.executeUpdate()==1,"Concurrent position change; repair aborted");
                    }
                    var after=one(db,"SELECT * FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=6 AND CURRENCY_CODE='INR'");
                    require("Y".equals(one(db,"SELECT IS_MATCHED FROM M05_V_ACCOUNT_POSITION_RECON WHERE BANK_ACCOUNT_ID=6").get("IS_MATCHED")),"Rebuild did not reconcile");
                    require(journalsBefore.equals(hash(OracleRbiLedgerAudit.rows(db,"SELECT * FROM M05_GL_JOURNAL ORDER BY JOURNAL_ID")))&&postingsBefore.equals(hash(OracleRbiLedgerAudit.rows(db,"SELECT * FROM M05_GL_POSTING ORDER BY POSTING_ID"))),"Journal history changed");
                    var details=new LinkedHashMap<String,Object>();details.put("source","IMMUTABLE_GL_POSTINGS");details.put("before",before);details.put("after",after);details.put("postingEvidence",postings);details.put("journalHistoryUnchanged",true);
                    try(var audit=db.prepareStatement("INSERT INTO M04_ACCOUNT_AUDIT_EVENT(AUDIT_ID,ACCOUNT_ID,ACTION_TYPE,ACTOR_USER_ID,RESULT,REASON_CODE,BEFORE_STATE_HASH,AFTER_STATE_HASH,DETAILS_JSON,CORRELATION_ID) VALUES (?,6,'LEDGER_POSITION_REBUILT',?,'SUCCESS','REPLAY_IMMUTABLE_GL',?,?,?,?)")){
                        audit.setString(1,UUID.randomUUID().toString());audit.setString(2,ACTOR);audit.setString(3,hash(before));audit.setString(4,hash(after));audit.setString(5,JSON.writeValueAsString(details));audit.setString(6,CORRELATION);audit.executeUpdate();
                    }
                    try(var audit=db.prepareStatement("INSERT INTO M05_SECURITY_AUDIT_EVENT(ACTOR_ID,CORRELATION_ID,ACTION_CODE,RESOURCE_TYPE,RESOURCE_ID,RESULT_CODE,REASON_CODE) VALUES (?,?,'ACCOUNT_POSITION_REBUILT','ACCOUNT','6','SUCCESS','REPLAY_IMMUTABLE_GL')")){
                        audit.setString(1,ACTOR);audit.setString(2,CORRELATION);audit.executeUpdate();
                    }
                    report.putAll(details);report.put("correlationId",CORRELATION);
                    if(apply){db.commit();report.put("status","COMMITTED");}else{db.rollback();report.put("status","ROLLED_BACK");}
                }
            }catch(Exception failure){db.rollback();throw failure;}
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
        System.out.println("Account 6 position repair: "+report.get("status"));
    }
    static Map<String,Object> one(Connection db,String sql)throws SQLException{
        var rows=OracleRbiLedgerAudit.rows(db,sql);require(rows.size()==1,"Expected exactly one evidence row");return rows.get(0);
    }
    static long count(Connection db,String sql)throws SQLException{return Long.parseLong(one(db,sql).values().iterator().next().toString());}
    static BigDecimal decimal(Map<String,Object> row,String key){return new BigDecimal(row.get(key).toString());}
    static String hash(Object value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}
    static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
