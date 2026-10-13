import java.sql.*;
import java.nio.file.*;
import java.util.*;

/** Read-only Oracle diagnostics; reports counts, never customer rows or credentials. */
public class OracleReadinessCheck {
    public static void main(String[] args) throws Exception {
        var checks = new LinkedHashMap<String, String>();
        checks.put("application objects are valid", "SELECT COUNT(*) FROM USER_OBJECTS WHERE (OBJECT_NAME LIKE 'M0%' OR OBJECT_NAME LIKE 'M10%' OR OBJECT_NAME LIKE 'MBX%') AND STATUS<>'VALID'");
        checks.put("application constraints are enabled and validated", "SELECT COUNT(*) FROM USER_CONSTRAINTS WHERE (TABLE_NAME LIKE 'M0%' OR TABLE_NAME LIKE 'M10%' OR TABLE_NAME LIKE 'MBX%') AND (STATUS<>'ENABLED' OR VALIDATED<>'VALIDATED')");
        checks.put("application triggers are enabled", "SELECT COUNT(*) FROM USER_TRIGGERS WHERE (TABLE_NAME LIKE 'M0%' OR TABLE_NAME LIKE 'M10%' OR TABLE_NAME LIKE 'MBX%') AND STATUS<>'ENABLED'");
        checks.put("every posted journal balances", "SELECT COUNT(*) FROM M05_V_GL_JOURNAL_CONTROL WHERE IS_BALANCED='N'");
        checks.put("account positions reconcile with postings", "SELECT COUNT(*) FROM M05_V_ACCOUNT_POSITION_RECON WHERE IS_MATCHED='N'");
        checks.put("active accounts have posting fences", "SELECT COUNT(*) FROM M04_BANK_ACCOUNT A WHERE LIFECYCLE_STATUS='ACTIVE' AND NOT EXISTS(SELECT 1 FROM M05_POSTING_FENCE F WHERE F.BANK_ACCOUNT_ID=A.ACCOUNT_ID)");
        checks.put("customer proof digests have expected length", "SELECT COUNT(*) FROM MBX_CUSTOMER_SECRET WHERE UTL_RAW.LENGTH(SECRET_DIGEST)<>32");
        checks.put("override approvals separate maker and checker", "SELECT COUNT(*) FROM MBX_OVERRIDE_APPROVAL WHERE MAKER_ID=CHECKER_ID");
        checks.put("immutable audit trigger exists and is enabled", "SELECT CASE WHEN COUNT(*)=1 THEN 0 ELSE 1 END FROM USER_TRIGGERS WHERE TRIGGER_NAME='MBX_AUDIT_IMMUTABLE' AND STATUS='ENABLED'");
        checks.put("synthetic accounts are the expected test pair", "SELECT CASE WHEN COUNT(*)=2 THEN 0 ELSE 1 END FROM M04_BANK_ACCOUNT WHERE (ACCOUNT_ID=3 AND ACCOUNT_NUMBER='MCPALPHA0001') OR (ACCOUNT_ID=4 AND ACCOUNT_NUMBER='MCPALPHA0002')");
        var rows = new ArrayList<String>();
        var mismatches = new ArrayList<String>();
        int failures = 0;
        try (Connection db = DriverManager.getConnection(System.getenv("DB_URL"), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))) {
            db.setAutoCommit(false);
            try (Statement command = db.createStatement()) { command.execute("SET TRANSACTION READ ONLY"); }
            try {
                for (var check : checks.entrySet()) {
                    long count = -1;
                    String error = "";
                    try (Statement command = db.createStatement()) {
                        command.setQueryTimeout(30);
                        try (ResultSet result = command.executeQuery(check.getValue())) { result.next(); count = result.getLong(1); }
                    } catch (SQLException failure) { error = ",\"oracleErrorCode\":" + failure.getErrorCode(); }
                    boolean pass = count == 0;
                    if (!pass) failures++;
                    rows.add("{\"category\":\"Oracle schema\",\"name\":\"" + check.getKey() + "\",\"status\":\"" + (pass ? "PASS" : "FAIL") + "\",\"exceptionCount\":" + count + error + "}");
                    System.out.println((pass ? "PASS: " : "FAIL: ") + check.getKey() + "; exceptions=" + count);
                }
                boolean timestampPass = true;
                try (PreparedStatement command = db.prepareStatement("SELECT GREATEST(?, ? + INTERVAL '0.000001' SECOND) FROM DUAL")) {
                    command.setQueryTimeout(30);
                    var start = java.time.OffsetDateTime.parse("2026-01-01T00:00:00.123456Z");
                    for (var end : List.of(start, start.minusSeconds(1), start.plusSeconds(1))) {
                        command.setObject(1, end);
                        command.setObject(2, start);
                        try (ResultSet result = command.executeQuery()) {
                            result.next();
                            var expected = end.isAfter(start) ? end : start.plusNanos(1000);
                            timestampPass &= result.getObject(1, java.time.OffsetDateTime.class).isEqual(expected);
                        }
                    }
                } catch (SQLException failure) { timestampPass = false; }
                if (!timestampPass) failures++;
                rows.add("{\"category\":\"Oracle schema\",\"name\":\"relationship end timestamps preserve positive microsecond intervals\",\"status\":\"" + (timestampPass ? "PASS" : "FAIL") + "\"}");
                System.out.println((timestampPass ? "PASS: " : "FAIL: ") + "relationship end timestamps preserve positive microsecond intervals");
                try (Statement command = db.createStatement()) {
                    command.setQueryTimeout(30);
                    try (ResultSet result = command.executeQuery("SELECT BANK_ACCOUNT_ID,POSTED_BALANCE,GL_DERIVED_BALANCE,DIFFERENCE FROM M05_V_ACCOUNT_POSITION_RECON WHERE IS_MATCHED='N' ORDER BY BANK_ACCOUNT_ID FETCH FIRST 200 ROWS ONLY")) {
                        while (result.next()) mismatches.add("{\"accountId\":" + result.getLong(1) + ",\"posted\":\"" + result.getBigDecimal(2).toPlainString() + "\",\"derived\":\"" + result.getBigDecimal(3).toPlainString() + "\",\"difference\":\"" + result.getBigDecimal(4).toPlainString() + "\"}");
                    }
                }
            } finally { db.rollback(); }
        }
        Files.writeString(Path.of(args[0]), "{\"database\":\"Oracle\",\"results\":[" + String.join(",", rows) + "],\"evidence\":{\"accountPositionMismatches\":[" + String.join(",", mismatches) + "]}}");
        if (failures > 0) System.exit(1);
    }
}
