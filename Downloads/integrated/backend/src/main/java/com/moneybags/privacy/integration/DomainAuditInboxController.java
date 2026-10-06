package com.moneybags.privacy.integration;

import com.moneybags.privacy.audit.AuditController;
import com.moneybags.privacy.common.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Accepts a domain audit event once and records its privacy projection atomically. */
@RestController
@RequestMapping("/api/v1/internal/privacy/domain-events")
public class DomainAuditInboxController {
    private final JdbcClient jdbc;
    private final AuditController audit;

    public DomainAuditInboxController(JdbcClient jdbc, AuditController audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Deduplicates source events and only acknowledges them after the audit row commits. */
    @PostMapping
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_PRIVACY_INTERNAL')")
    @Operation(summary = "Consume a source audit event through the Module 9 inbox")
    public Map<String, String> receive(@Valid @RequestBody DomainEvent event) {
        if (!event.eventId().equals(event.audit().sourceEventId())
                || !event.sourceService().equals(event.audit().sourceService())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INBOX_SOURCE_MISMATCH",
                    "Envelope and audit source identifiers must match.");
        }
        var received = jdbc.sql("""
                SELECT PROCESSED_AT FROM M09_PRIVACY_INBOX_EVENT
                 WHERE SOURCE_SERVICE=:source AND EVENT_ID=:id
                """).param("source", event.sourceService()).param("id", event.eventId())
                .query((rs, n) -> rs.getObject(1)).optional();
        if (received.isPresent()) return Map.of("status", "DUPLICATE");
        jdbc.sql("""
                INSERT INTO M09_PRIVACY_INBOX_EVENT
                (EVENT_ID,SOURCE_SERVICE,EVENT_TYPE,RECEIVED_AT)
                VALUES (:id,:source,:type,SYSTIMESTAMP)
                """).param("id", event.eventId()).param("source", event.sourceService())
                .param("type", event.eventType()).update();
        var result = audit.ingest(event.audit());
        jdbc.sql("""
                UPDATE M09_PRIVACY_INBOX_EVENT SET PROCESSED_AT=SYSTIMESTAMP
                 WHERE SOURCE_SERVICE=:source AND EVENT_ID=:id
                """).param("source", event.sourceService()).param("id", event.eventId()).update();
        return result;
    }

    public record DomainEvent(@NotBlank String sourceService, @NotBlank String eventId,
                              @NotBlank String eventType, @NotNull @Valid AuditController.AuditInput audit) {}
}
