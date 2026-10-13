package com.moneybags.privacy.audit;

import com.moneybags.privacy.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/** Derives audit retention from an approved, effective bank policy. */
@Component
public class AuditRetentionPolicy {
    private final JdbcClient jdbc;

    public AuditRetentionPolicy(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Returns an approved retention end; missing policy blocks audit intake. */
    public OffsetDateTime until(OffsetDateTime occurredAt) {
        var months = jdbc.sql("""
                SELECT RETENTION_MONTHS FROM M09_PRIVACY_RETENTION_SCHEDULE
                 WHERE OWNING_SERVICE='M09_PRIVACY' AND RECORD_CATEGORY='AUDIT_EVENT'
                   AND APPROVED_AT IS NOT NULL AND EFFECTIVE_FROM<=:at
                   AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>:at)
                 ORDER BY POLICY_VERSION DESC FETCH FIRST 1 ROW ONLY
                """).param("at", occurredAt).query(Integer.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "AUDIT_RETENTION_POLICY_MISSING", "An approved audit retention policy is required."));
        return occurredAt.plusMonths(months);
    }
}
