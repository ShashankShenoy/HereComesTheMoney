package com.moneybags.privacy.hold;

import com.moneybags.privacy.common.PageResponse;
import com.moneybags.privacy.common.RequestContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

/** REST and internal disposition-check endpoints for scoped legal holds. */
@RestController
@RequestMapping("/api/v1/privacy/holds")
public class LegalHoldController {
    private final LegalHoldService service;
    private final RequestContextResolver contexts;

    public LegalHoldController(LegalHoldService service, RequestContextResolver contexts) {
        this.service = service;
        this.contexts = contexts;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PRIVACY_HOLD_VIEW')")
    @Operation(summary = "List legal holds")
    public PageResponse<LegalHold> list(@RequestParam(required=false) String status,
                                         @RequestParam(defaultValue="0") @Min(0) int page,
                                         @RequestParam(defaultValue="25") @Min(1) @Max(200) int size) {
        return service.list(status, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PRIVACY_HOLD_VIEW')")
    @Operation(summary = "Get a scoped legal hold")
    public LegalHold get(@PathVariable String id) { return service.get(id); }

    @PostMapping
    @PreAuthorize("hasAuthority('PRIVACY_HOLD_CREATE')")
    @Operation(summary = "Request a legal hold")
    public LegalHold create(@Valid @RequestBody HoldRequest input, HttpServletRequest request,
                            Authentication authentication) {
        return service.create(input.toCommand(), contexts.resolve(request, authentication));
    }

    @PostMapping("/{id}/activation")
    @PreAuthorize("hasAuthority('PRIVACY_HOLD_APPROVE')")
    @Operation(summary = "Approve and activate a legal hold")
    public LegalHold activate(@PathVariable String id, HttpServletRequest request, Authentication authentication) {
        return service.activate(id, contexts.resolve(request, authentication));
    }

    @PostMapping("/{id}/release-request")
    @PreAuthorize("hasAuthority('PRIVACY_HOLD_RELEASE')")
    @Operation(summary = "Request release of an active hold")
    public LegalHold requestRelease(@PathVariable String id, @Valid @RequestBody ReleaseRequest input,
                             HttpServletRequest request, Authentication authentication) {
        return service.requestRelease(id, input.authorityReference(),
                contexts.resolve(request, authentication));
    }

    @PostMapping("/{id}/release-approval")
    @PreAuthorize("hasAuthority('PRIVACY_HOLD_APPROVE')")
    @Operation(summary = "Approve a requested release as an independent checker")
    public LegalHold approveRelease(@PathVariable String id, HttpServletRequest request,
                                    Authentication authentication) {
        return service.approveRelease(id, contexts.resolve(request, authentication));
    }

    @PostMapping("/evaluations")
    @PreAuthorize("hasAuthority('SCOPE_PRIVACY_INTERNAL')")
    @Operation(summary = "Check whether a record is preserved before disposition")
    public LegalHoldService.HoldEvaluation evaluate(@Valid @RequestBody HoldTargetRequest input) {
        return service.evaluate(input.toTarget());
    }

    public record HoldRequest(@NotBlank String authorityReference,
                              @NotBlank String reason, String subjectService,
                              String subjectExternalId, String targetService,
                              String targetResourceType, String targetExternalId,
                              String recordCategory, OffsetDateTime recordFromAt,
                              OffsetDateTime recordToAt, @NotNull OffsetDateTime reviewDueAt) {
        LegalHoldService.CreateHold toCommand() {
            return new LegalHoldService.CreateHold(authorityReference, reason, subjectService,
                    subjectExternalId, targetService, targetResourceType, targetExternalId,
                    recordCategory, recordFromAt, recordToAt, reviewDueAt);
        }
    }

    public record ReleaseRequest(@NotBlank String authorityReference) {}

    public record HoldTargetRequest(String subjectService, String subjectExternalId,
                                    @NotBlank String targetService, @NotBlank String resourceType,
                                    @NotBlank String resourceExternalId, String recordCategory,
                                    OffsetDateTime recordAt) {
        LegalHoldRepository.HoldTarget toTarget() {
            return new LegalHoldRepository.HoldTarget(subjectService, subjectExternalId,
                    targetService, resourceType, resourceExternalId, recordCategory, recordAt);
        }
    }
}
