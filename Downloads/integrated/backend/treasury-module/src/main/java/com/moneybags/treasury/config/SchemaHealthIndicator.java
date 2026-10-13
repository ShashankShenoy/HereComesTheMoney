package com.moneybags.treasury.config;

import java.util.List;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Detects deployment against the older MB_TREASURY schema or an incomplete Module 7 installation. */
@org.springframework.context.annotation.Profile("!local")
@Component("module7Schema")
public class SchemaHealthIndicator implements HealthIndicator {
    private static final List<String> TABLES = List.of(
        "M07_RESERVE_ACCOUNT", "M07_SETTLEMENT_CYCLE", "M07_SETTLEMENT_CYCLE_ITEM",
        "M07_SETTLEMENT_EVIDENCE", "M07_CENTRAL_TREASURY_LEDGER", "M07_RESERVE_POSITION",
        "M07_TREASURY_LIQUIDITY_HOLD", "M07_RECONCILIATION_EXCEPTION", "M07_TREASURY_WORK_ITEM",
        "M07_SECURITY_AUDIT_EVENT", "M07_OUTBOX_EVENT", "M07_CONSUMER_INBOX");

    private final JdbcClient jdbc;

    public SchemaHealthIndicator(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Reports missing M07 tables in the health endpoint instead of allowing latent runtime failures. */
    @Override
    public Health health() {
        try {
            List<String> found = jdbc.sql("SELECT TABLE_NAME FROM USER_TABLES WHERE TABLE_NAME LIKE 'M07_%'")
                .query(String.class).list();
            List<String> missing = TABLES.stream().filter(table -> !found.contains(table)).toList();
            return missing.isEmpty()
                ? Health.up().withDetail("schema", "single-owner M07").withDetail("tables", TABLES.size()).build()
                : Health.down().withDetail("missingTables", missing).withDetail("hint", "Run the supplied Modules 1-10 schema installer").build();
        } catch (RuntimeException ex) {
            return Health.down(ex).withDetail("hint", "Check Oracle connectivity and schema owner").build();
        }
    }
}
