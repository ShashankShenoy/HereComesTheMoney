import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.nio.file.*;
import java.util.*;

/** Read-only provenance for the pre-existing account 6 reconciliation exception. */
public class OracleAccountRepairEvidence {
    public static void main(String[] args)throws Exception{
        var queries=new LinkedHashMap<String,String>();
        queries.put("account","SELECT * FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=6");
        queries.put("position","SELECT * FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=6");
        queries.put("reconciliation","SELECT * FROM M05_V_ACCOUNT_POSITION_RECON WHERE BANK_ACCOUNT_ID=6");
        queries.put("transactions","SELECT * FROM M05_TXN_TRANSACTION_LOG WHERE SOURCE_ACCOUNT_ID=6 OR TARGET_ACCOUNT_ID=6 ORDER BY TXN_ID");
        queries.put("postings","SELECT P.*,J.POSTING_KEY,J.JOURNAL_TYPE,J.VALUE_DATE,J.DESCRIPTION,J.CREATED_BY FROM M05_GL_POSTING P JOIN M05_GL_JOURNAL J ON J.JOURNAL_ID=P.JOURNAL_ID WHERE P.BANK_ACCOUNT_ID=6 ORDER BY P.POSTING_ID");
        queries.put("fence","SELECT * FROM M05_POSTING_FENCE WHERE BANK_ACCOUNT_ID=6");
        queries.put("accountAudit","SELECT ACTION_TYPE,RESULT,REASON_CODE,DETAILS_JSON,OCCURRED_AT FROM M04_ACCOUNT_AUDIT_EVENT WHERE ACCOUNT_ID=6 ORDER BY OCCURRED_AT");
        queries.put("accountStatusHistory","SELECT CHANGE_KIND,OLD_STATUS,NEW_STATUS,REASON_CODE,REASON_REMARKS,CHANGED_AT FROM M04_ACCOUNT_STATUS_HISTORY WHERE ACCOUNT_ID=6 ORDER BY CHANGED_AT");
        queries.put("openingEvidence","SELECT ATTEMPT_ID,DECISION_RESULT,FAILURE_REASON_CODE,EVALUATED_AT FROM M04_ACCOUNT_OPENING_EVIDENCE WHERE ACCOUNT_ID=6 ORDER BY EVALUATED_AT");
        queries.put("fundingObjects","SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE FROM USER_TAB_COLUMNS WHERE (TABLE_NAME LIKE 'M04%' OR TABLE_NAME LIKE 'MBX%') AND (TABLE_NAME LIKE '%OPEN%' OR TABLE_NAME LIKE '%FUND%' OR COLUMN_NAME LIKE '%BALANCE%' OR COLUMN_NAME LIKE '%EVIDENCE%') ORDER BY TABLE_NAME,COLUMN_ID");
        queries.put("auditObjects","SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE FROM USER_TAB_COLUMNS WHERE TABLE_NAME LIKE 'M04%' AND (TABLE_NAME LIKE '%AUDIT%' OR TABLE_NAME LIKE '%HIST%' OR TABLE_NAME LIKE '%REQUEST%' OR TABLE_NAME LIKE '%OPEN%') ORDER BY TABLE_NAME,COLUMN_ID");
        queries.put("openingFunding","SELECT TXN_ID,TXN_TYPE,STATUS,SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID,AMOUNT,REQUEST_KEY,VALUE_DATE,CREATED_AT FROM M05_TXN_TRANSACTION_LOG WHERE TXN_TYPE='OPENING_FUNDING' ORDER BY TXN_ID");
        queries.put("equityGl","SELECT G.GL_ACCOUNT_ID,G.GL_CODE,G.GL_NAME,G.NORMAL_SIDE,COALESCE(SUM(CASE WHEN P.ENTRY_SIDE='CR' THEN P.AMOUNT ELSE -P.AMOUNT END),0) BOOKED_BALANCE FROM M05_GL_ACCOUNT G LEFT JOIN M05_GL_POSTING P ON P.GL_ACCOUNT_ID=G.GL_ACCOUNT_ID WHERE G.ACCOUNT_CLASS='EQUITY' GROUP BY G.GL_ACCOUNT_ID,G.GL_CODE,G.GL_NAME,G.NORMAL_SIDE");
        var report=new LinkedHashMap<String,Object>();var properties=new Properties();
        properties.setProperty("user",System.getenv("DB_USERNAME"));properties.setProperty("password",System.getenv("DB_PASSWORD"));
        properties.setProperty("oracle.net.CONNECT_TIMEOUT","5000");properties.setProperty("oracle.jdbc.ReadTimeout","15000");
        try(var db=DriverManager.getConnection(System.getenv("DB_URL"),properties)){
            db.setAutoCommit(false);try(var s=db.createStatement()){s.execute("SET TRANSACTION READ ONLY");}
            try{for(var q:queries.entrySet()){report.put(q.getKey(),OracleRbiLedgerAudit.rows(db,q.getValue()));System.out.println("Read "+q.getKey());}}finally{db.rollback();}
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    }
}
