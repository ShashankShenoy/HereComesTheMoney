package com.moneybags.statements;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes immutable evidence in a separate transaction, including denied access. */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** Records the decision before any response; an audit write failure blocks disclosure. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String event, Actor actor, Long accountId, String statementId, String requestId,
        String purpose, String caseRef, String authorizationRef, String result, String denial,
        String correlationId) {
        jdbc.update("""
            INSERT INTO M10_STATEMENT_ACCESS_AUDIT
              (ACCESS_AUDIT_ID,EVENT_TYPE,ACTOR_TYPE,ACTOR_ID,SESSION_ID,ACCOUNT_ID,STATEMENT_ID,
               REQUEST_ID,PURPOSE_CODE,CASE_REFERENCE,AUTHORIZATION_REF,RESULT,DENIAL_REASON_CODE,
               CORRELATION_ID,REQUEST_METADATA)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, UUID.randomUUID().toString(), event, actor.type(), actor.id(), actor.sessionId(),
            accountId, statementId, requestId, purpose, caseRef, authorizationRef, result, denial,
            correlationId, "{}");
    }
}
