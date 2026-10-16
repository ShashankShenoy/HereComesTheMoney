import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Consistent, read-only RBI/ledger diagnostics. Credentials are supplied only through the environment. */
public class OracleRbiLedgerAudit {
    public static void main(String[] args) throws Exception {
        var checks = new LinkedHashMap<String,String>();
        checks.put("journals balance with at least two postings", "SELECT COUNT(*) FROM M05_V_GL_JOURNAL_CONTROL WHERE IS_BALANCED<>'Y'");
        checks.put("customer positions equal immutable postings", "SELECT COUNT(*) FROM M05_V_ACCOUNT_POSITION_RECON WHERE IS_MATCHED<>'Y'");
        checks.put("reserve positions equal treasury movements", "SELECT COUNT(*) FROM M07_V_RESERVE_POSITION_RECON WHERE IS_MATCHED<>'Y'");
        checks.put("reserve asset GL equals reserve position", "SELECT COUNT(*) FROM M07_RESERVE_ACCOUNT R JOIN M07_RESERVE_POSITION P ON P.RESERVE_ACCOUNT_ID=R.RESERVE_ACCOUNT_ID WHERE P.CONFIRMED_BALANCE<>(SELECT COALESCE(SUM(CASE WHEN G.ENTRY_SIDE='DR' THEN G.AMOUNT ELSE -G.AMOUNT END),0) FROM M05_GL_POSTING G WHERE G.GL_ACCOUNT_ID=R.GL_ACCOUNT_ID)");
        checks.put("reserve holds equal active reservations", "SELECT COUNT(*) FROM M07_RESERVE_POSITION P WHERE P.ACTIVE_HOLD_AMOUNT<>(SELECT COALESCE(SUM(H.AMOUNT),0) FROM M07_TREASURY_LIQUIDITY_HOLD H WHERE H.RESERVE_ACCOUNT_ID=P.RESERVE_ACCOUNT_ID AND H.STATUS IN ('RESERVED','COMMITTED'))");
        checks.put("reserves have nonnegative spendable balances", "SELECT COUNT(*) FROM M07_RESERVE_POSITION WHERE AVAILABLE_BALANCE<0");
        checks.put("reserve GL configuration is unique and valid", "SELECT COUNT(*) FROM M07_RESERVE_ACCOUNT R JOIN M05_GL_ACCOUNT G ON G.GL_ACCOUNT_ID=R.GL_ACCOUNT_ID WHERE R.ACCOUNT_STATUS='ACTIVE' AND (G.ACCOUNT_CLASS<>'ASSET' OR G.NORMAL_SIDE<>'DR' OR G.ACTIVE_FLAG<>'Y' OR G.CURRENCY_CODE<>'INR' OR (SELECT COUNT(*) FROM M07_RESERVE_ACCOUNT X WHERE X.GL_ACCOUNT_ID=R.GL_ACCOUNT_ID)<>1)");
        checks.put("treasury movements have verified evidence and balanced journals", "SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER L LEFT JOIN M07_SETTLEMENT_EVIDENCE E ON E.EVIDENCE_ID=L.EVIDENCE_ID LEFT JOIN M05_V_GL_JOURNAL_CONTROL J ON J.JOURNAL_ID=L.GL_JOURNAL_ID WHERE E.EVIDENCE_ID IS NULL OR E.EVIDENCE_STATUS<>'VERIFIED' OR J.JOURNAL_ID IS NULL OR J.IS_BALANCED<>'Y'");
        checks.put("treasury movement amounts match evidence", "SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER L JOIN M07_SETTLEMENT_EVIDENCE E ON E.EVIDENCE_ID=L.EVIDENCE_ID WHERE L.AMOUNT<>E.AMOUNT OR L.RESERVE_ACCOUNT_ID<>E.RESERVE_ACCOUNT_ID OR L.MOVEMENT_SIDE<>E.MOVEMENT_SIDE OR L.RAIL_CODE<>E.RAIL_CODE");
        checks.put("each evidence is consumed at most once", "SELECT COUNT(*) FROM (SELECT EVIDENCE_ID FROM M07_CENTRAL_TREASURY_LEDGER GROUP BY EVIDENCE_ID HAVING COUNT(*)>1)");
        checks.put("simulated settlements conserve participant funds", "SELECT COUNT(*) FROM (SELECT SETTLEMENT_REFERENCE FROM MBX_SIM_MOVEMENT GROUP BY SETTLEMENT_REFERENCE HAVING SUM(NET_AMOUNT)<>0)");
        checks.put("participant movements preserve arithmetic", "SELECT COUNT(*) FROM MBX_SIM_MOVEMENT WHERE BALANCE_AFTER<>BALANCE_BEFORE+NET_AMOUNT OR BALANCE_AFTER<0");
        checks.put("external participant holds are within balances", "SELECT COUNT(*) FROM MBX_SIM_BANK WHERE BALANCE<0 OR HELD_AMOUNT<0 OR HELD_AMOUNT>BALANCE");
        checks.put("simulated confirmed settlements have movements", "SELECT COUNT(*) FROM MBX_SIM_SETTLEMENT S WHERE NOT EXISTS(SELECT 1 FROM MBX_SIM_MOVEMENT M WHERE M.SETTLEMENT_REFERENCE=S.SETTLEMENT_REFERENCE)");
        checks.put("settled simulator payments have settlement confirmation", "SELECT COUNT(*) FROM MBX_SIM_PAYMENT S JOIN M06_PAYMENT_INSTRUCTION P ON P.PAYMENT_ID=S.PAYMENT_ID WHERE P.STATUS='SETTLED' AND (S.SETTLEMENT_REFERENCE IS NULL OR NOT EXISTS(SELECT 1 FROM MBX_SIM_SETTLEMENT X WHERE X.SETTLEMENT_REFERENCE=S.SETTLEMENT_REFERENCE))");
        checks.put("settled simulator cycles have no unfinished payments", "SELECT COUNT(*) FROM M07_SETTLEMENT_CYCLE C JOIN MBX_SIM_WINDOW W ON W.SETTLEMENT_CYCLE_ID=C.SETTLEMENT_CYCLE_ID WHERE C.STATUS='SETTLED' AND EXISTS(SELECT 1 FROM M07_SETTLEMENT_CYCLE_ITEM I JOIN M06_PAYMENT_INSTRUCTION P ON P.PAYMENT_ID=I.PAYMENT_ID WHERE I.SETTLEMENT_CYCLE_ID=C.SETTLEMENT_CYCLE_ID AND P.STATUS<>'SETTLED')");
        checks.put("simulator cycle membership agrees with payment batch", "SELECT COUNT(*) FROM M07_SETTLEMENT_CYCLE_ITEM I JOIN M07_SETTLEMENT_CYCLE C ON C.SETTLEMENT_CYCLE_ID=I.SETTLEMENT_CYCLE_ID JOIN MBX_SIM_WINDOW W ON W.SETTLEMENT_CYCLE_ID=C.SETTLEMENT_CYCLE_ID JOIN M06_PAYMENT_INSTRUCTION P ON P.PAYMENT_ID=I.PAYMENT_ID WHERE P.SETTLEMENT_CYCLE_ID<>C.SETTLEMENT_CYCLE_ID OR P.CLEARING_BATCH_ID<>C.CLEARING_BATCH_ID OR P.RAIL_CODE<>C.RAIL_CODE OR P.AMOUNT<>I.AMOUNT");
        checks.put("confirmed reserve openings have independent checker", "SELECT COUNT(*) FROM MBX_RBI_RESERVE_OPENING WHERE STATUS='CONFIRMED' AND (CHECKER_ID=MAKER_ID OR CHECKER_ID IS NULL OR JOURNAL_ID IS NULL OR TREASURY_ENTRY_ID IS NULL)");
        checks.put("confirmed cash deliveries have independent checker", "SELECT COUNT(*) FROM MBX_RBI_CASH_DELIVERY WHERE STATUS='CONFIRMED' AND (CHECKER_ID=MAKER_ID OR CHECKER_ID IS NULL OR JOURNAL_ID IS NULL OR TREASURY_ENTRY_ID IS NULL)");
        checks.put("posting fences exist for active accounts", "SELECT COUNT(*) FROM M04_BANK_ACCOUNT A WHERE LIFECYCLE_STATUS='ACTIVE' AND NOT EXISTS(SELECT 1 FROM M05_POSTING_FENCE F WHERE F.BANK_ACCOUNT_ID=A.ACCOUNT_ID)");
        var results=new ArrayList<Map<String,Object>>();
        var snapshots=new LinkedHashMap<String,Object>();
        var report=new LinkedHashMap<String,Object>();
        var properties=new Properties();
        properties.setProperty("user",System.getenv("DB_USERNAME"));
        properties.setProperty("password",System.getenv("DB_PASSWORD"));
        properties.setProperty("oracle.net.CONNECT_TIMEOUT","5000");
        properties.setProperty("oracle.jdbc.ReadTimeout","15000");
        try(var db=DriverManager.getConnection(System.getenv("DB_URL"),properties)) {
            db.setAutoCommit(false);
            try(var s=db.createStatement()){s.execute("SET TRANSACTION READ ONLY");}
            try {
                for(var check:checks.entrySet()) {
                    var row=new LinkedHashMap<String,Object>();row.put("name",check.getKey());
                    try(var s=db.createStatement()) {
                        s.setQueryTimeout(30);
                        try(var rs=s.executeQuery(check.getValue())){rs.next();long count=rs.getLong(1);row.put("status",count==0?"PASS":"FAIL");row.put("exceptionCount",count);}
                    } catch(SQLException e){row.put("status","ERROR");row.put("oracleErrorCode",e.getErrorCode());}
                    results.add(row);System.out.println(row);
                }
                for(String table:List.of("M05_ACCOUNT_POSITION","M05_GL_JOURNAL","M05_GL_POSTING","M05_TXN_TRANSACTION_LOG","M07_RESERVE_POSITION","M07_CENTRAL_TREASURY_LEDGER","M07_TREASURY_LIQUIDITY_HOLD","M06_PAYMENT_INSTRUCTION","M07_SETTLEMENT_CYCLE","MBX_SIM_BANK","MBX_SIM_SETTLEMENT","MBX_SIM_MOVEMENT")) {
                    var digest=MessageDigest.getInstance("SHA-256");long count=0;
                    try(var s=db.createStatement()){s.setQueryTimeout(30);try(var rs=s.executeQuery("SELECT * FROM "+table+" ORDER BY 1,2")){
                        int columns=rs.getMetaData().getColumnCount();while(rs.next()){count++;for(int i=1;i<=columns;i++){String value=rs.getString(i);if(value==null)digest.update((byte)0);else{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update((byte)1);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}}}
                    }}
                    snapshots.put(table,Map.of("rowCount",count,"sha256",HexFormat.of().formatHex(digest.digest())));
                }
                report.put("accountPositionMismatches",rows(db,"SELECT R.BANK_ACCOUNT_ID,R.POSTED_BALANCE,R.GL_DERIVED_BALANCE,R.DIFFERENCE,(SELECT COUNT(*) FROM M05_GL_POSTING P WHERE P.BANK_ACCOUNT_ID=R.BANK_ACCOUNT_ID) POSTING_COUNT FROM M05_V_ACCOUNT_POSITION_RECON R WHERE R.IS_MATCHED='N'"));
                report.put("reservePositions",rows(db,"SELECT * FROM M07_V_RESERVE_POSITION_RECON ORDER BY RESERVE_ACCOUNT_ID"));
                report.put("openCycles",rows(db,"SELECT SETTLEMENT_CYCLE_ID,RAIL_CODE,STATUS FROM M07_SETTLEMENT_CYCLE WHERE STATUS<>'SETTLED' ORDER BY SETTLEMENT_CYCLE_ID"));
                report.put("recentSettlementErrors",rows(db,"SELECT ERROR_CODE,COUNT(*) ERROR_COUNT,MAX(OCCURRED_AT) LAST_AT FROM MBX_EMPLOYEE_ERROR WHERE ERROR_CODE LIKE '%SETTLE%' OR ERROR_CODE LIKE '%LEDGER%' OR ERROR_CODE LIKE '%RESERVE%' GROUP BY ERROR_CODE"));
                report.put("remainingAuditFixtures",rows(db,"SELECT 'GL_MASTER' FIXTURE_TYPE,COUNT(*) FIXTURE_COUNT FROM M05_GL_ACCOUNT WHERE GL_NAME='Rollback audit fixture' UNION ALL SELECT 'BENEFICIARY',COUNT(*) FROM MBX_BENEFICIARY WHERE DISPLAY_NAME='Oracle rollback test' UNION ALL SELECT 'TREASURY_WORK',COUNT(*) FROM M07_TREASURY_WORK_ITEM WHERE DETAIL_TEXT IN ('Rollback audit','Rollback approval fixture','QA treasury work-item creation test; no funds movement or adjustment requested.')"));
            } finally {db.rollback();}
        }
        report.put("database","Oracle");report.put("readOnly",true);report.put("checkedAt",java.time.Instant.now().toString());report.put("checks",results);report.put("snapshots",snapshots);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
        if(results.stream().anyMatch(r->!"PASS".equals(r.get("status"))))System.exit(1);
    }
    static List<Map<String,Object>> rows(Connection db,String sql)throws SQLException{
        var rows=new ArrayList<Map<String,Object>>();try(var s=db.createStatement()){s.setQueryTimeout(30);try(var rs=s.executeQuery(sql)){var meta=rs.getMetaData();while(rs.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),rs.getString(i));rows.add(row);}}}return rows;
    }
}
