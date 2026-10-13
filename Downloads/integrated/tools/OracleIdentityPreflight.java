import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.sql.*;
import java.nio.file.*;
import java.util.*;

/** Checks only the two known synthetic credentials without attempting logins or writes. */
public class OracleIdentityPreflight {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var credentials = mapper.readTree(System.in);
        var output = new ArrayList<Map<String,Object>>();
        var encoder = new BCryptPasswordEncoder(12);
        try (Connection db = DriverManager.getConnection(System.getenv("DB_URL"), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))) {
            db.setAutoCommit(false);
            try (Statement command = db.createStatement()) { command.execute("SET TRANSACTION READ ONLY"); }
            try {
                for (var credential : credentials) {
                    String username = credential.get("username").asText();
                    if (!Set.of("mcp_demo_customer_a", "mcp_demo_customer_b").contains(username)) throw new IllegalArgumentException("Synthetic users only");
                    var row = new LinkedHashMap<String,Object>();
                    row.put("category", "Oracle identity preflight"); row.put("name", username + " bundled password and login state"); row.put("username", username);
                    try (PreparedStatement query = db.prepareStatement("SELECT U.STATUS USER_STATUS,C.STATUS CREDENTIAL_STATUS,C.HASH_SCHEME,C.PASSWORD_HASH,C.FAILED_ATTEMPTS,CASE WHEN C.LOCKED_UNTIL>SYSTIMESTAMP THEN 1 ELSE 0 END IS_LOCKED,(SELECT COUNT(*) FROM M01_IAM_AUTH_FACTOR F WHERE F.USER_ID=U.USER_ID AND F.FACTOR_TYPE='TOTP' AND F.STATUS='ACTIVE') MFA_FACTORS FROM M01_IAM_USER U LEFT JOIN M01_IAM_CREDENTIAL C ON C.USER_ID=U.USER_ID WHERE U.USERNAME=?")) {
                        query.setString(1, username); query.setQueryTimeout(30);
                        try (ResultSet result = query.executeQuery()) {
                            boolean pass = false;
                            if (result.next()) {
                                boolean matches = "BCRYPT".equalsIgnoreCase(result.getString("HASH_SCHEME")) && encoder.matches(credential.get("password").asText(), result.getString("PASSWORD_HASH"));
                                row.put("passwordMatches", matches); row.put("userStatus", result.getString("USER_STATUS")); row.put("credentialStatus", result.getString("CREDENTIAL_STATUS")); row.put("currentlyLocked", result.getInt("IS_LOCKED") != 0); row.put("failedAttempts", result.getInt("FAILED_ATTEMPTS")); row.put("activeTotpFactors", result.getInt("MFA_FACTORS"));
                                pass = matches && "ACTIVE".equals(result.getString("USER_STATUS")) && "ACTIVE".equals(result.getString("CREDENTIAL_STATUS")) && result.getInt("IS_LOCKED") == 0 && result.getInt("MFA_FACTORS") == 0;
                            }
                            row.put("status", pass ? "PASS" : "FAIL");
                        }
                    }
                    output.add(row);
                }
            } finally {db.rollback();}
        }
        Files.writeString(Path.of(args[0]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("database", "Oracle", "results", output)));
        for (var row : output) System.out.println(row.get("status") + ": " + row.get("name") + "; passwordMatches=" + row.get("passwordMatches") + "; currentlyLocked=" + row.get("currentlyLocked") + "; userStatus=" + row.get("userStatus") + "; credentialStatus=" + row.get("credentialStatus") + "; activeTotpFactors=" + row.get("activeTotpFactors"));
    }
}
