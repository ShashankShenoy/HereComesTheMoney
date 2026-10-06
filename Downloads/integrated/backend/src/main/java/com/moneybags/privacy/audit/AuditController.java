package com.moneybags.privacy.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.privacy.common.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Append-only audit intake and narrowly filtered investigation search. */
@RestController
@RequestMapping("/api/v1/privacy/audit-events")
public class AuditController {
    private static final Set<String> SAFE_METADATA_KEYS = Set.of(
            "operation", "channel", "httpStatus", "decisionReason", "policyVersion", "riskBand");
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditRetentionPolicy retention;

    public AuditController(JdbcClient jdbc, ObjectMapper json, AuditRetentionPolicy retention) {
        this.jdbc = jdbc;
        this.json = json;
        this.retention = retention;
    }

    /** Ingests a source event once; the source and stream form its deduplication boundary. */
    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_PRIVACY_INTERNAL')")
    @Operation(summary = "Ingest a minimized immutable audit event")
    public Map<String, String> ingest(@Valid @RequestBody AuditInput input) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !input.sourceService().equals(authentication.getName())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AUDIT_SOURCE_MISMATCH",
                    "Authenticated service must match the audit source.");
        }
        if (!SAFE_METADATA_KEYS.containsAll(input.metadata().keySet())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUDIT_METADATA_NOT_ALLOWED",
                    "Audit metadata contains a key outside the allowlist.");
        }
        if (input.metadata().values().stream().anyMatch(value -> value == null
                || !value.matches("[A-Za-z0-9_.:-]{1,80}"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUDIT_METADATA_VALUE_INVALID",
                    "Audit metadata values must be short codes without free text.");
        }
        var existing = findSourceId(input.sourceService(), input.sourceStream(), input.sourceEventId());
        if (existing != null) return Map.of("auditEventId", existing, "status", "DUPLICATE");
        var retentionUntil = retention.until(input.occurredAt());
        try {
            var id = UUID.randomUUID().toString();
            var metadata = json.writeValueAsString(input.metadata());
            var hash = sha256(json.writeValueAsString(input));
            jdbc.sql("""
                    INSERT INTO M09_PRIVACY_AUDIT_EVENT
                    (AUDIT_EVENT_ID,SOURCE_SERVICE,SOURCE_STREAM,SOURCE_EVENT_ID,EVENT_TYPE,
                     OCCURRED_AT,OUTCOME,ACTOR_EXTERNAL_ID,SESSION_EXTERNAL_ID,ACTOR_ROLE_CODE,
                     ACTOR_SCOPE_REF,PURPOSE_CODE,SUBJECT_EXTERNAL_ID,RESOURCE_TYPE,
                     RESOURCE_EXTERNAL_ID,DATA_CLASSIFICATION,COMPLIANCE_CASE_ID,
                     CONSENT_DECISION_ID,CORRELATION_ID,REASON_CODE,EVENT_METADATA,EVENT_SHA256,
                     RETENTION_UNTIL,CREATED_AT,CREATED_BY)
                    VALUES (:id,:source,:stream,:eventId,:type,:occurred,:outcome,:actor,:session,
                            :role,:scope,:purpose,:subject,:resourceType,:resourceId,:classification,
                            :caseId,:decisionId,:correlation,:reason,:metadata,:hash,:retention,
                            SYSTIMESTAMP,:source)
                    """).param("id", id).param("source", input.sourceService())
                    .param("stream", input.sourceStream()).param("eventId", input.sourceEventId())
                    .param("type", input.eventType()).param("occurred", input.occurredAt())
                    .param("outcome", input.outcome()).param("actor", input.actorExternalId())
                    .param("session", input.sessionExternalId()).param("role", input.actorRoleCode())
                    .param("scope", input.actorScopeRef()).param("purpose", input.purposeCode())
                    .param("subject", input.subjectExternalId()).param("resourceType", input.resourceType())
                    .param("resourceId", input.resourceExternalId())
                    .param("classification", input.dataClassification())
                    .param("caseId", input.complianceCaseId()).param("decisionId", input.consentDecisionId())
                    .param("correlation", input.correlationId()).param("reason", input.reasonCode())
                    .param("metadata", metadata).param("hash", hash)
                    .param("retention", retentionUntil).update();
            return Map.of("auditEventId", id, "status", "CREATED");
        } catch (DuplicateKeyException race) {
            var id = findSourceId(input.sourceService(), input.sourceStream(), input.sourceEventId());
            if (id != null) return Map.of("auditEventId", id, "status", "DUPLICATE");
            throw race;
        } catch (JsonProcessingException problem) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUDIT_JSON_INVALID", "Audit input cannot be serialized.");
        }
    }

    /** Searches by a known reference and bounded time window; broad extraction uses approved exports. */
    @GetMapping
    @PreAuthorize("hasAuthority('PRIVACY_AUDIT_VIEW')")
    @Operation(summary = "Search audit events by case, resource, or actor")
    public List<AuditSummary> search(@RequestParam(required = false) String caseId,
                                     @RequestParam(required = false) String resourceType,
                                     @RequestParam(required = false) String resourceExternalId,
                                     @RequestParam(required = false) String actorExternalId,
                                     @RequestParam OffsetDateTime from,
                                     @RequestParam OffsetDateTime to,
                                     @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        if (caseId == null && actorExternalId == null &&
                (resourceType == null || resourceExternalId == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUDIT_FILTER_REQUIRED",
                    "A case, actor, or complete resource filter is required.");
        }
        if (to.isBefore(from) || to.isAfter(from.plusDays(31))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUDIT_WINDOW_INVALID",
                    "Search window must be positive and at most 31 days.");
        }
        return jdbc.sql("""
                SELECT AUDIT_EVENT_ID,SOURCE_SERVICE,SOURCE_STREAM,SOURCE_EVENT_ID,EVENT_TYPE,
                       OCCURRED_AT,OUTCOME,CORRELATION_ID,EVENT_SHA256
                  FROM M09_PRIVACY_AUDIT_EVENT
                 WHERE OCCURRED_AT>=:fromAt AND OCCURRED_AT<:toAt
                   AND (:caseId IS NULL OR COMPLIANCE_CASE_ID=:caseId)
                   AND (:actor IS NULL OR ACTOR_EXTERNAL_ID=:actor)
                   AND (:resourceType IS NULL OR RESOURCE_TYPE=:resourceType)
                   AND (:resourceId IS NULL OR RESOURCE_EXTERNAL_ID=:resourceId)
                 ORDER BY OCCURRED_AT DESC FETCH FIRST :limit ROWS ONLY
                """).param("fromAt", from).param("toAt", to).param("caseId", caseId)
                .param("actor", actorExternalId).param("resourceType", resourceType)
                .param("resourceId", resourceExternalId).param("limit", limit)
                .query((rs, n) -> new AuditSummary(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class),
                        rs.getString(7), rs.getString(8), rs.getString(9))).list();
    }

    private String findSourceId(String service, String stream, String eventId) {
        return jdbc.sql("""
                SELECT AUDIT_EVENT_ID FROM M09_PRIVACY_AUDIT_EVENT
                 WHERE SOURCE_SERVICE=:service AND SOURCE_STREAM=:stream AND SOURCE_EVENT_ID=:eventId
                """).param("service", service).param("stream", stream).param("eventId", eventId)
                .query(String.class).optional().orElse(null);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record AuditInput(@NotBlank String sourceService, @NotBlank String sourceStream,
                             @NotBlank String sourceEventId, @NotBlank String eventType,
                             @NotNull OffsetDateTime occurredAt, @NotBlank String outcome,
                             String actorExternalId, String sessionExternalId, String actorRoleCode,
                             String actorScopeRef, String purposeCode, String subjectExternalId,
                             String resourceType, String resourceExternalId, String dataClassification,
                             String complianceCaseId, String consentDecisionId, String correlationId,
                             String reasonCode, @NotNull Map<String, String> metadata) {}
    public record AuditSummary(String id, String sourceService, String sourceStream,
                               String sourceEventId, String eventType, OffsetDateTime occurredAt,
                               String outcome, String correlationId, String sha256) {}
}
