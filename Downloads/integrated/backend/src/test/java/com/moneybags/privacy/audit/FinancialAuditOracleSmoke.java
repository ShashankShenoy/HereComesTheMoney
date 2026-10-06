package com.moneybags.privacy.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.common.api.BusinessException;
import com.moneybags.common.database.*;
import com.moneybags.iam.repository.*;
import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.iam.service.AccessDecisionService;
import com.moneybags.integration.*;
import com.moneybags.txn.api.Contracts.*;
import com.moneybags.txn.core.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Opt-in Oracle acceptance test using DB_URL/DB_USERNAME/DB_PASSWORD.
 * Requires existing synthetic MCPALPHA0001/0002 accounts and demo IAM users.
 * All DML rolls back; Oracle identity sequences can advance.
 */
public class FinancialAuditOracleSmoke {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String url = System.getenv("DB_URL");
        check(url != null && url.startsWith("jdbc:oracle:"), "Oracle JDBC URL required");
        var source = new DriverManagerDataSource(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        var db = new JdbcTemplate(source);
        check(db.queryForObject("SELECT COUNT(*) FROM M04_BANK_ACCOUNT WHERE "
                + "(ACCOUNT_ID=3 AND ACCOUNT_NUMBER='MCPALPHA0001') OR "
                + "(ACCOUNT_ID=4 AND ACCOUNT_NUMBER='MCPALPHA0002')", Integer.class) == 2,
                "Synthetic account pair missing; refusing to test other accounts");
        var schema = new SchemaRepository(db);
        var iam = new IamRepository(db, schema);
        var decisions = new AccessDecisionService(new IamAdminRepository(db, schema), iam, Clock.systemUTC());
        var access = new BankingAccess(new BusinessRepository(db, schema), decisions);
        var hashes = new CustomerHashService(db);
        var ledger = new LedgerService(db, new ObjectMapper().findAndRegisterModules(),
                new AccountCatalog(db), new PaymentCatalog(db));
        var banking = new BankingService(new BusinessRepository(db, schema), access, ledger);
        var audit = new FinancialAuditController(db, access, hashes);
        var manager = new DataSourceTransactionManager(source);
        var transaction = new TransactionTemplate(manager);
        var nested = new TransactionTemplate(manager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        var before = counts(db);
        BigDecimal sourceBefore = ledger.position(3).posted(), targetBefore = ledger.position(4).posted();
        String key = "audit-smoke-" + UUID.randomUUID();
        try {
            transaction.execute(status -> {
                status.setRollbackOnly();
                var maker = identity(iam, "mcp_demo_customer_a");
                var reviewerUser = iam.userByName("mcp_demo_checker").orElseThrow();
                check(!"CUSTOMER".equals(reviewerUser.userType()), "Synthetic checker must be staff");
                String role = UUID.randomUUID().toString();
                // Test-only authorization fixture, invisible to other connections and rolled back.
                db.update("INSERT INTO M01_IAM_ROLE(ROLE_ID,ROLE_CODE,DISPLAY_NAME,STATUS,SENSITIVE_FLAG,ROW_VERSION) "
                        + "VALUES (?,?,?,'ACTIVE','N',1)", role, "AUDIT_TEST_" + role.substring(0, 8), "Rollback-only audit test");
                check(db.update("INSERT INTO M01_IAM_ROLE_PERMISSION(ROLE_ID,PERMISSION_ID) SELECT ?,PERMISSION_ID "
                        + "FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE IN ('IAM_AUDIT_READ','TXN_READ')", role) == 2,
                        "Required audit and financial permissions are missing from the catalog");
                db.update("INSERT INTO M01_IAM_USER_ROLE(ASSIGNMENT_ID,USER_ID,ROLE_ID,SCOPE_TYPE,STATUS,VALID_FROM,CREATED_AT) "
                        + "VALUES (?,?,?,'GLOBAL','ACTIVE',SYSTIMESTAMP,SYSTIMESTAMP)",
                        UUID.randomUUID().toString(), reviewerUser.userId(), role);
                var reviewer = identity(iam, reviewerUser.username());
                authenticate(maker);
                var request = new TransferRequest(3L, 4L, new BigDecimal("0.01"), "WEB", key,
                        UUID.randomUUID().toString(), LocalDate.now(ZoneId.of("Asia/Kolkata")));
                var posted = banking.transfer(request);
                check("POSTED".equals(posted.status()), "Transfer did not post");
                check(sourceBefore.subtract(request.amount()).compareTo(ledger.position(3).posted()) == 0,
                        "Source not debited exactly once");
                check(targetBefore.add(request.amount()).compareTo(ledger.position(4).posted()) == 0,
                        "Target not credited exactly once");
                check(banking.transfer(request).transactionId() == posted.transactionId(), "Replay created another transaction");
                authenticate(reviewer);
                var response = audit.search(posted.transactionId(), null, null);
                check("no-store".equals(response.getHeaders().getCacheControl()), "Response must not be cached");
                var events = response.getBody();
                check(events != null && events.size() == 1, "Posting/replay must produce exactly one audit entry");
                var event = events.get(0);
                check(event.sourceAccountId() == 3 && event.targetAccountId() == 4, "Wrong account direction");
                check("0001".equals(event.sourceAccountEnding()) && "0002".equals(event.targetAccountEnding()), "Wrong account endings");
                check("POSTED".equals(event.event()) && maker.userId().equals(event.actorUserId())
                        && event.occurredAt() != null, "Missing posting, actor or time evidence");
                check(event.amount() == null, "Amount leaked without a key");
                check(audit.search(posted.transactionId(), 4L, null).getBody().size() == 1, "Target filter missed transfer");
                check(audit.search(posted.transactionId(), -1L, null).getBody().isEmpty(), "Unrelated filter returned transfer");
                String temporaryHash = hashes.issue(maker.userId());
                check(audit.search(posted.transactionId(), null, temporaryHash).getBody().get(0).amount()
                        .compareTo(request.amount()) == 0, "Authorized reviewer cannot read exact amount");
                check(audit.search(posted.transactionId(), null, "x".repeat(43)).getBody().get(0).amount() == null,
                        "Invalid key disclosed amount");
                authenticate(maker);
                try { audit.search(null, null, null); throw new AssertionError("Customer accessed global audit"); }
                catch (BusinessException denied) { check(denied.status() == org.springframework.http.HttpStatus.FORBIDDEN,
                        "Unexpected authorization failure"); }
                try {
                    nested.execute(s -> ledger.transfer(new TransferRequest(3L, 4L, new BigDecimal("999999999.00"),
                            "WEB", key + "-failed", UUID.randomUUID().toString(), request.valueDate()), maker.userId()));
                    throw new AssertionError("Insufficient-funds transfer posted");
                } catch (com.moneybags.txn.api.ApiException expected) {
                    check(db.queryForObject("SELECT COUNT(*) FROM M05_TXN_TRANSACTION_LOG WHERE REQUEST_KEY=?",
                            Integer.class, key + "-failed") == 0, "Failed transaction not rolled back");
                }
                try {
                    nested.execute(s -> db.update("UPDATE M05_TXN_STATUS_HISTORY SET REASON_CODE='TAMPER' "
                            + "WHERE TXN_STATUS_HISTORY_ID=?", event.eventId()));
                    throw new AssertionError("Oracle allowed audit evidence to change");
                } catch (DataAccessException expected) {
                    Throwable cause = expected;
                    boolean immutable = false;
                    while (cause != null) {
                        if (cause instanceof java.sql.SQLException sql && sql.getErrorCode() == 20003) immutable = true;
                        cause = cause.getCause();
                    }
                    check(immutable, "Update failed for a reason other than immutability trigger");
                }
                check("Y".equals(db.queryForObject("SELECT IS_BALANCED FROM M05_V_GL_JOURNAL_CONTROL WHERE JOURNAL_ID=?",
                        String.class, posted.journalId())), "Journal unbalanced");
                var reversal = ledger.requestReversal(posted.transactionId(), new ReversalRequest(key + "-reversal",
                        "Rollback-only acceptance test", UUID.randomUUID().toString(), request.valueDate()), maker.userId());
                authenticate(reviewer);
                check(audit.search(reversal.transactionId(), null, null).getBody().isEmpty(),
                        "Pending reversal appeared as a completed financial event");
                ledger.decideReversal(reversal.transactionId(), new ApprovalDecision("APPROVED", "Test checker"), reviewer.userId());
                var originalEvents = audit.search(posted.transactionId(), null, null).getBody();
                check(originalEvents.size() == 2 && originalEvents.stream().anyMatch(e -> "POSTED".equals(e.event()))
                        && originalEvents.stream().anyMatch(e -> "REVERSED".equals(e.event())),
                        "Reversal did not preserve the original posting and append a reversal event");
                var reversalEvent = audit.search(reversal.transactionId(), null, null).getBody().get(0);
                check(reversalEvent.sourceAccountId() == 4 && reversalEvent.targetAccountId() == 3
                        && reversalEvent.originalTransactionId() == posted.transactionId(), "Reversal account direction or reference wrong");
                SecurityContextHolder.clearContext();
                try { audit.search(null, null, null); throw new AssertionError("Anonymous user accessed audit"); }
                catch (BusinessException denied) { check(denied.status() == org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "Anonymous audit should require sign-in"); }
                System.out.println("PASS: Oracle transfer, account pair, replay, filters, amount protection, authorization, failure rollback, immutable evidence, balanced ledger and reversal history");
                return null;
            });
        } finally { SecurityContextHolder.clearContext(); }
        check(before.equals(counts(db)), "Record counts changed after rollback");
        check(sourceBefore.compareTo(ledger.position(3).posted()) == 0 && targetBefore.compareTo(ledger.position(4).posted()) == 0,
                "Balances changed after rollback");
        check(db.queryForObject("SELECT COUNT(*) FROM M05_TXN_TRANSACTION_LOG WHERE REQUEST_KEY=?", Integer.class, key) == 0,
                "Test transaction persisted");
        System.out.println("PASS: rollback restored balances and observed module record counts; no test transaction persisted");
    }

    private static UserPrincipal identity(IamRepository iam, String username) {
        var user = iam.userByName(username).orElseThrow();
        String session = UUID.randomUUID().toString();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        iam.createSession(session, user.userId(), "MONEYBAGS_WEB", "rollback-only-audit-test", now,
                now.plusMinutes(5), now.plusMinutes(5));
        iam.setAuthLevel(session, "MFA@" + Instant.now().getEpochSecond());
        return new UserPrincipal(user.userId(), username, user.userType(), session, 1L,
                iam.roles(user.userId(), now), iam.permissions(user.userId(), now), iam.cifIds(user.userId(), now));
    }

    private static void authenticate(UserPrincipal user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private static Map<String, Long> counts(JdbcTemplate db) {
        var result = new LinkedHashMap<String, Long>();
        for (String table : List.of("M01_IAM_SESSION", "M01_IAM_ROLE", "M01_IAM_ROLE_PERMISSION", "M01_IAM_USER_ROLE",
                "M02_CIF_CUSTOMER", "M03_PM_PRODUCT", "M04_BANK_ACCOUNT",
                "M05_TXN_TRANSACTION_LOG", "M05_TXN_STATUS_HISTORY", "M05_GL_JOURNAL", "M05_GL_POSTING",
                "M05_SECURITY_AUDIT_EVENT", "M05_OUTBOX_EVENT", "M06_PAYMENT_INSTRUCTION", "M07_RESERVE_ACCOUNT",
                "M08_LOAN_FACILITY", "M09_PRIVACY_AUDIT_EVENT", "M10_STATEMENT_REQUEST", "MBX_AUDIT"))
            result.put(table, db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        return result;
    }
}
