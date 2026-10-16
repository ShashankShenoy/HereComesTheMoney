import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Restores the explicitly requested pre-repair position, preserving both correction audit trails. */
public class OracleAccount6PositionRestore {
    static final String CORRELATION="account6-position-restore-20261015";
    static final ObjectMapper JSON=new ObjectMapper();
    public static void main(String[] args)throws Exception{
        if(args.length!=2||!args[1].equals("--restore-approved-previous-position"))throw new IllegalArgumentException("Explicit restoration flag required");
        var properties=new Properties();properties.setProperty("user",System.getenv("DB_USERNAME"));properties.setProperty("password",System.getenv("DB_PASSWORD"));
        properties.setProperty("oracle.net.CONNECT_TIMEOUT","5000");properties.setProperty("oracle.jdbc.ReadTimeout","15000");
        var report=new LinkedHashMap<String,Object>();report.put("accountId",6);report.put("reason","User explicitly requested restoration of the previous INR 500000 position; journal history remains unchanged.");
        try(var db=DriverManager.getConnection(System.getenv("DB_URL"),properties)){
            db.setAutoCommit(false);try{
                var account=one(db,"SELECT ACCOUNT_NUMBER FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=6 FOR UPDATE");
                require("MBMUS0000000000026".equals(account.get("ACCOUNT_NUMBER")),"Unexpected account identity");
                var before=one(db,"SELECT * FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=6 AND CURRENCY_CODE='INR' FOR UPDATE");
                if(count(db,"SELECT COUNT(*) FROM M05_SECURITY_AUDIT_EVENT WHERE CORRELATION_ID='"+CORRELATION+"' AND ACTION_CODE='ACCOUNT_POSITION_RESTORED'")>0){
                    require(decimal(before,"POSTED_BALANCE").compareTo(new BigDecimal("500000"))==0,"Restored balance subsequently changed");
                    report.put("status","ALREADY_RESTORED");report.put("after",before);db.rollback();
                }else{
                    require(decimal(before,"POSTED_BALANCE").signum()==0&&"5".equals(before.get("POSITION_VERSION"))&&"18".equals(before.get("LAST_JOURNAL_ID")),"Position changed after the approved rebuild");
                    require(count(db,"SELECT COUNT(*) FROM M05_SECURITY_AUDIT_EVENT WHERE CORRELATION_ID='account6-position-rebuild-20261015' AND ACTION_CODE='ACCOUNT_POSITION_REBUILT'")==1,"Original rebuild audit missing");
                    for(String field:List.of("ACTIVE_HOLD_AMOUNT","ACTIVE_LIEN_AMOUNT","ACTIVE_BLOCK_AMOUNT","OVERDRAFT_LIMIT"))require(decimal(before,field).signum()==0,"Account controls changed");
                    String journals=hash(OracleRbiLedgerAudit.rows(db,"SELECT * FROM M05_GL_JOURNAL ORDER BY JOURNAL_ID"));
                    String postings=hash(OracleRbiLedgerAudit.rows(db,"SELECT * FROM M05_GL_POSTING ORDER BY POSTING_ID"));
                    try(var update=db.prepareStatement("UPDATE M05_ACCOUNT_POSITION SET POSTED_BALANCE=500000,POSITION_VERSION=POSITION_VERSION+1,AS_OF=SYSTIMESTAMP WHERE BANK_ACCOUNT_ID=6 AND CURRENCY_CODE='INR' AND POSTED_BALANCE=0 AND POSITION_VERSION=5 AND LAST_JOURNAL_ID=18")){require(update.executeUpdate()==1,"Concurrent position change");}
                    var after=one(db,"SELECT * FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=6 AND CURRENCY_CODE='INR'");
                    require(journals.equals(hash(OracleRbiLedgerAudit.rows(db,"SELECT * FROM M05_GL_JOURNAL ORDER BY JOURNAL_ID")))&&postings.equals(hash(OracleRbiLedgerAudit.rows(db,"SELECT * FROM M05_GL_POSTING ORDER BY POSTING_ID"))),"Journal history changed");
                    report.put("before",before);report.put("after",after);report.put("journalHistoryUnchanged",true);report.put("correlationId",CORRELATION);
                    try(var audit=db.prepareStatement("INSERT INTO M04_ACCOUNT_AUDIT_EVENT(AUDIT_ID,ACCOUNT_ID,ACTION_TYPE,ACTOR_USER_ID,RESULT,REASON_CODE,BEFORE_STATE_HASH,AFTER_STATE_HASH,DETAILS_JSON,CORRELATION_ID) VALUES (?,6,'LEDGER_POSITION_RESTORED','reconciliation-maintenance','SUCCESS','USER_REQUEST_RESTORE',?,?,?,?)")){
                        audit.setString(1,UUID.randomUUID().toString());audit.setString(2,hash(before));audit.setString(3,hash(after));audit.setString(4,JSON.writeValueAsString(report));audit.setString(5,CORRELATION);audit.executeUpdate();
                    }
                    try(var audit=db.prepareStatement("INSERT INTO M05_SECURITY_AUDIT_EVENT(ACTOR_ID,CORRELATION_ID,ACTION_CODE,RESOURCE_TYPE,RESOURCE_ID,RESULT_CODE,REASON_CODE) VALUES ('reconciliation-maintenance',?,'ACCOUNT_POSITION_RESTORED','ACCOUNT','6','SUCCESS','USER_REQUEST_RESTORE')")){audit.setString(1,CORRELATION);audit.executeUpdate();}
                    db.commit();report.put("status","RESTORED");
                }
            }catch(Exception failure){db.rollback();throw failure;}
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);System.out.println("Account 6 previous balance: "+report.get("status"));
    }
    static Map<String,Object> one(Connection db,String sql)throws SQLException{var rows=OracleRbiLedgerAudit.rows(db,sql);require(rows.size()==1,"Expected exactly one evidence row");return rows.get(0);}
    static long count(Connection db,String sql)throws SQLException{return Long.parseLong(one(db,sql).values().iterator().next().toString());}
    static BigDecimal decimal(Map<String,Object> row,String key){return new BigDecimal(row.get(key).toString());}
    static String hash(Object value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}
    static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
