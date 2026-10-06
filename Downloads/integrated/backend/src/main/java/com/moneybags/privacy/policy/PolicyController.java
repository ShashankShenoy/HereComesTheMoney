package com.moneybags.privacy.policy;

import com.moneybags.privacy.common.ApiException;
import com.moneybags.privacy.hold.LegalHoldService;
import com.moneybags.privacy.hold.LegalHoldRepository;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

/** Serves approved field and retention policy to record-owning services. */
@RestController
@RequestMapping("/api/v1/privacy/policies")
public class PolicyController {
    private final JdbcClient jdbc;
    private final LegalHoldService holds;

    public PolicyController(JdbcClient jdbc, LegalHoldService holds) {
        this.jdbc = jdbc;
        this.holds = holds;
    }

    /** Lists registered assets; records remain in the owning service. */
    @GetMapping("/assets")
    @PreAuthorize("hasAuthority('PRIVACY_POLICY_VIEW')")
    @Operation(summary = "List registered data assets")
    public List<Asset> assets(@RequestParam String owner) {
        return jdbc.sql("""
                SELECT DATA_ASSET_ID,OWNING_SERVICE,ASSET_CODE,ASSET_TYPE,LOCATION_REF,STATUS
                  FROM M09_PRIVACY_DATA_ASSET WHERE OWNING_SERVICE=:owner ORDER BY ASSET_CODE
                """).param("owner", owner).query((rs, n) -> new Asset(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))).list();
    }

    /** Returns approved field controls for a registered asset. */
    @GetMapping("/assets/{assetId}/fields")
    @PreAuthorize("hasAuthority('PRIVACY_POLICY_VIEW')")
    @Operation(summary = "Get field protection controls")
    public List<FieldControl> fields(@PathVariable String assetId) {
        return jdbc.sql("""
                SELECT FIELD_NAME,DATA_CATEGORY,SENSITIVITY_LEVEL,IS_DIRECT_IDENTIFIER,
                       ENCRYPTION_REQUIRED,MASKING_METHOD,LOG_ALLOWED,NON_PROD_ALLOWED,
                       RETENTION_SCHEDULE_ID
                  FROM M09_PRIVACY_DATA_CONTROL WHERE DATA_ASSET_ID=:id ORDER BY FIELD_NAME
                """).param("id", assetId).query((rs, n) -> new FieldControl(rs.getString(1),
                rs.getString(2), rs.getString(3), "Y".equals(rs.getString(4)),
                "Y".equals(rs.getString(5)), rs.getString(6), "Y".equals(rs.getString(7)),
                "Y".equals(rs.getString(8)), rs.getString(9))).list();
    }

    /** Finds the effective approved retention rule for an owner and record category. */
    @GetMapping("/retention")
    @PreAuthorize("hasAnyAuthority('PRIVACY_POLICY_VIEW','SCOPE_PRIVACY_INTERNAL')")
    @Operation(summary = "Get the effective retention schedule")
    public RetentionSchedule retention(@RequestParam String owner,
                                       @RequestParam String category,
                                       @RequestParam(required = false) OffsetDateTime at) {
        var moment = at == null ? OffsetDateTime.now() : at;
        return jdbc.sql("""
                SELECT RETENTION_SCHEDULE_ID,OWNING_SERVICE,RECORD_CATEGORY,POLICY_VERSION,
                       RETENTION_START_EVENT,RETENTION_MONTHS,DISPOSAL_ACTION,EFFECTIVE_FROM,EFFECTIVE_TO
                  FROM M09_PRIVACY_RETENTION_SCHEDULE
                 WHERE OWNING_SERVICE=:owner AND RECORD_CATEGORY=:category AND STATUS='ACTIVE'
                   AND EFFECTIVE_FROM<=:at AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>:at)
                 ORDER BY POLICY_VERSION DESC FETCH FIRST 1 ROW ONLY
                """).param("owner", owner).param("category", category).param("at", moment)
                .query((rs, n) -> new RetentionSchedule(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getInt(4), rs.getString(5), rs.getInt(6),
                        rs.getString(7), rs.getObject(8, OffsetDateTime.class),
                        rs.getObject(9, OffsetDateTime.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "RETENTION_POLICY_NOT_FOUND", "No effective approved retention rule exists."));
    }

    /** Checks both retention maturity and legal hold before a data owner disposes a record. */
    @PostMapping("/disposition-evaluations")
    @PreAuthorize("hasAuthority('SCOPE_PRIVACY_INTERNAL')")
    @Operation(summary = "Evaluate retention maturity and preservation hold")
    public DispositionEvaluation disposition(@Valid @RequestBody DispositionRequest request) {
        var now = OffsetDateTime.now();
        var rule = retention(request.ownerService(), request.recordCategory(), now);
        var held = holds.evaluate(new LegalHoldRepository.HoldTarget(request.subjectService(),
                request.subjectExternalId(), request.ownerService(), request.resourceType(),
                request.resourceExternalId(), request.recordCategory(), request.recordAt())).dispositionBlocked();
        var mature = !request.retentionStartedAt().plusMonths(rule.months()).isAfter(now);
        return new DispositionEvaluation(!held && mature, held ? "ACTIVE_HOLD"
                : mature ? "RETENTION_MATURE" : "RETENTION_NOT_MATURE", rule.id(), rule.action());
    }

    public record Asset(String id, String owner, String code, String type, String locationRef, String status) {}
    public record FieldControl(String fieldName, String category, String sensitivity,
                               boolean directIdentifier, boolean encryptionRequired,
                               String maskingMethod, boolean logAllowed, boolean nonProdAllowed,
                               String retentionScheduleId) {}
    public record RetentionSchedule(String id, String owner, String category, int version,
                                    String startEvent, int months, String action,
                                    OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo) {}
    public record DispositionRequest(@NotBlank String ownerService, @NotBlank String recordCategory,
                                     @NotBlank String resourceType, @NotBlank String resourceExternalId,
                                     String subjectService, String subjectExternalId,
                                     OffsetDateTime recordAt, @NotNull OffsetDateTime retentionStartedAt) {}
    public record DispositionEvaluation(boolean allowed, String reason,
                                        String scheduleId, String action) {}
}
