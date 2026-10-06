package com.moneybags.privacy.export;

import com.moneybags.privacy.common.ApiException;
import com.moneybags.privacy.common.RequestContextResolver;
import com.moneybags.privacy.outbox.OutboxService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Approval and package-reference lifecycle; encrypted packages live in the evidence vault. */
@RestController
@RequestMapping("/api/v1/privacy/evidence-exports")
public class EvidenceExportController {
    private final JdbcClient jdbc;
    private final RequestContextResolver contexts;
    private final OutboxService outbox;
    private final String vaultServiceId;

    public EvidenceExportController(JdbcClient jdbc, RequestContextResolver contexts, OutboxService outbox,
                                    @Value("${moneybags.integration.vault-service-id:EVIDENCE_VAULT}") String vaultServiceId) {
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.outbox = outbox;
        this.vaultServiceId = vaultServiceId;
    }

    /** Requests a case-linked export; repeated keys resolve to the original request. */
    @PostMapping
    @Transactional
    @PreAuthorize("hasAuthority('PRIVACY_EXPORT_REQUEST')")
    @Operation(summary = "Request a privacy evidence export")
    public EvidenceExport request(@Valid @RequestBody ExportRequest input,
                                  HttpServletRequest request, Authentication authentication) {
        var ctx = contexts.resolve(request, authentication);
        if (ctx.idempotencyKey() == null || ctx.idempotencyKey().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.");
        }
        var prior = jdbc.sql("SELECT EVIDENCE_EXPORT_ID FROM M09_PRIVACY_EVIDENCE_EXPORT WHERE IDEMPOTENCY_KEY=:key")
                .param("key", ctx.idempotencyKey()).query(String.class).optional();
        if (prior.isPresent()) {
            var existing = get(prior.get());
            if (!java.util.Objects.equals(existing.caseId(), input.caseId())
                    || !existing.reason().equals(input.reason())) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key belongs to a different export request.");
            }
            return existing;
        }
        var id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO M09_PRIVACY_EVIDENCE_EXPORT
                (EVIDENCE_EXPORT_ID,COMPLIANCE_CASE_ID,EXPORT_REFERENCE,EXPORT_STATUS,
                 REQUEST_REASON,REQUESTED_AT,REQUESTED_BY,CORRELATION_ID,IDEMPOTENCY_KEY)
                VALUES (:id,:caseId,:reference,'REQUESTED',:reason,SYSTIMESTAMP,:actor,:correlation,:key)
                """).param("id", id).param("caseId", input.caseId())
                .param("reference", "EXP-" + id.substring(0, 8).toUpperCase())
                .param("reason", input.reason()).param("actor", ctx.actorId())
                .param("correlation", ctx.correlationId()).param("key", ctx.idempotencyKey()).update();
        var result = get(id);
        outbox.emit("EVIDENCE_EXPORT", id, "EvidenceExportRequested", event(result), ctx);
        return result;
    }

    /** Checks an export with separation of duties using the authenticated reviewer. */
    @PostMapping("/{id}/approval")
    @Transactional
    @PreAuthorize("hasAuthority('PRIVACY_EXPORT_APPROVE')")
    @Operation(summary = "Approve an evidence export")
    public EvidenceExport approve(@PathVariable String id, HttpServletRequest request,
                                  Authentication authentication) {
        var ctx = contexts.resolve(request, authentication);
        var changed = jdbc.sql("""
                UPDATE M09_PRIVACY_EVIDENCE_EXPORT
                   SET EXPORT_STATUS='APPROVED',APPROVED_AT=SYSTIMESTAMP,APPROVED_BY=:actor
                 WHERE EVIDENCE_EXPORT_ID=:id AND EXPORT_STATUS='REQUESTED'
                   AND REQUESTED_BY<>:actor
                """).param("actor", ctx.actorId()).param("id", id).update();
        if (changed != 1) conflict();
        var result = get(id);
        outbox.emit("EVIDENCE_EXPORT", id, "EvidenceExportApproved", event(result), ctx);
        return result;
    }

    /** Records a vault-generated manifest and package after independent approval. */
    @PostMapping("/{id}/completion")
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_PRIVACY_INTERNAL')")
    @Operation(summary = "Record an encrypted evidence package from the vault")
    public EvidenceExport complete(@PathVariable String id, @Valid @RequestBody PackageReference input,
                                   HttpServletRequest request, Authentication authentication) {
        var ctx = contexts.resolve(request, authentication);
        if (!vaultServiceId.equals(ctx.actorId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "VAULT_CALLER_REQUIRED",
                    "Only the configured evidence vault may complete an export.");
        }
        if (!input.expiresAt().isAfter(OffsetDateTime.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXPORT_EXPIRY_INVALID",
                    "Evidence package expiry must be in the future.");
        }
        var changed = jdbc.sql("""
                UPDATE M09_PRIVACY_EVIDENCE_EXPORT
                   SET EXPORT_STATUS='COMPLETED',MANIFEST_URI=:manifest,MANIFEST_SHA256=:manifestHash,
                       PACKAGE_URI=:packageUri,PACKAGE_SHA256=:packageHash,
                       COMPLETED_AT=SYSTIMESTAMP,EXPIRES_AT=:expires
                 WHERE EVIDENCE_EXPORT_ID=:id AND EXPORT_STATUS='APPROVED'
                """).param("manifest", input.manifestUri()).param("manifestHash", input.manifestSha256())
                .param("packageUri", input.packageUri()).param("packageHash", input.packageSha256())
                .param("expires", input.expiresAt()).param("id", id).update();
        if (changed != 1) conflict();
        var result = get(id);
        outbox.emit("EVIDENCE_EXPORT", id, "EvidenceExportCompleted", event(result), ctx);
        return result;
    }

    /** Returns metadata only; the vault authorizes and audits every download. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PRIVACY_EXPORT_VIEW')")
    @Operation(summary = "Get evidence export status and hashes")
    public EvidenceExport get(@PathVariable String id) {
        return jdbc.sql("SELECT * FROM M09_PRIVACY_EVIDENCE_EXPORT WHERE EVIDENCE_EXPORT_ID=:id")
                .param("id", id).query((rs, n) -> new EvidenceExport(
                        rs.getString("EVIDENCE_EXPORT_ID"), rs.getString("COMPLIANCE_CASE_ID"),
                        rs.getString("EXPORT_REFERENCE"), rs.getString("EXPORT_STATUS"),
                        rs.getString("REQUEST_REASON"), rs.getString("MANIFEST_URI"),
                        rs.getString("MANIFEST_SHA256"), rs.getString("PACKAGE_URI"),
                        rs.getString("PACKAGE_SHA256"), rs.getObject("REQUESTED_AT", OffsetDateTime.class),
                        rs.getString("REQUESTED_BY"), rs.getObject("APPROVED_AT", OffsetDateTime.class),
                        rs.getString("APPROVED_BY"), rs.getObject("COMPLETED_AT", OffsetDateTime.class),
                        rs.getObject("EXPIRES_AT", OffsetDateTime.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "EXPORT_NOT_FOUND", "Evidence export was not found."));
    }

    private void conflict() {
        throw new ApiException(HttpStatus.CONFLICT, "EXPORT_STATE_CONFLICT",
                "Export state or separation-of-duties rule prevents this action.");
    }

    private ExportChanged event(EvidenceExport value) {
        return new ExportChanged(value.id(), value.caseId(), value.status());
    }

    public record ExportChanged(String id, String caseId, String status) {}

    public record ExportRequest(String caseId, @NotBlank @Size(max=500) String reason) {}
    public record PackageReference(@NotBlank String manifestUri,
                                   @NotBlank @Pattern(regexp="[0-9a-fA-F]{64}") String manifestSha256,
                                   @NotBlank String packageUri,
                                   @NotBlank @Pattern(regexp="[0-9a-fA-F]{64}") String packageSha256,
                                   @NotNull OffsetDateTime expiresAt) {}
    public record EvidenceExport(String id, String caseId, String reference, String status,
                                 String reason, String manifestUri, String manifestSha256,
                                 String packageUri, String packageSha256,
                                 OffsetDateTime requestedAt, String requestedBy,
                                 OffsetDateTime approvedAt, String approvedBy,
                                 OffsetDateTime completedAt, OffsetDateTime expiresAt) {}
}
