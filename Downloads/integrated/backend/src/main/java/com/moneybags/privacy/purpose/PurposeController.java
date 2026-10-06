package com.moneybags.privacy.purpose;

import com.moneybags.privacy.common.PageResponse;
import com.moneybags.privacy.common.RequestContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.OffsetDateTime;

/** REST boundary for versioned processing-purpose administration. */
@RestController
@RequestMapping("/api/v1/privacy/purposes")
public class PurposeController {
    private final PurposeService service;
    private final RequestContextResolver contexts;

    public PurposeController(PurposeService service, RequestContextResolver contexts) {
        this.service = service;
        this.contexts = contexts;
    }

    /** Lists catalogue versions for operational and approval screens. */
    @GetMapping
    @PreAuthorize("hasAuthority('PRIVACY_PURPOSE_VIEW')")
    @Operation(summary = "List processing-purpose versions")
    public PageResponse<ProcessingPurpose> list(@RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size) {
        return service.list(status, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PRIVACY_PURPOSE_VIEW')")
    @Operation(summary = "Get one processing-purpose version")
    public ProcessingPurpose get(@PathVariable String id) { return service.get(id); }

    /** Creates a draft whose content becomes immutable after approval. */
    @PostMapping
    @PreAuthorize("hasAuthority('PRIVACY_PURPOSE_MANAGE')")
    @Operation(summary = "Create a draft purpose version")
    public ResponseEntity<ProcessingPurpose> create(@Valid @RequestBody PurposeRequest request,
                                                     HttpServletRequest servletRequest,
                                                     Authentication authentication) {
        var created = service.create(request.toCommand(), contexts.resolve(servletRequest, authentication));
        return ResponseEntity.created(URI.create("/api/v1/privacy/purposes/" + created.id())).body(created);
    }

    /** Performs the checker step with optimistic locking. */
    @PostMapping("/{id}/approval")
    @PreAuthorize("hasAuthority('PRIVACY_PURPOSE_APPROVE')")
    @Operation(summary = "Approve and activate a purpose version")
    public ProcessingPurpose approve(@PathVariable String id,
                                     @RequestHeader("If-Match") long expectedVersion,
                                     HttpServletRequest request, Authentication authentication) {
        return service.approve(id, expectedVersion, contexts.resolve(request, authentication));
    }

    /** Validated API payload for purpose creation. */
    public record PurposeRequest(
            @NotBlank @Size(max = 80) String code,
            @Min(1) int version,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 120) String ownerService,
            @NotBlank @Size(max = 40) String legalBasisCode,
            @Size(max = 1000) String noticeUri,
            @Pattern(regexp = "[0-9a-fA-F]{64}") String noticeSha256,
            @NotNull OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            OffsetDateTime reviewDueAt) {
        PurposeService.CreatePurpose toCommand() {
            return new PurposeService.CreatePurpose(code, version, name, ownerService, legalBasisCode,
                    noticeUri, noticeSha256, effectiveFrom, effectiveTo, reviewDueAt);
        }
    }
}
