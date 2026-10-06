package com.moneybags.privacy.casework;

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

/** REST boundary for rights requests and compliance workflows. */
@RestController
@RequestMapping("/api/v1/privacy/cases")
public class CaseController {
    private final CaseService service;
    private final RequestContextResolver contexts;

    public CaseController(CaseService service, RequestContextResolver contexts) {
        this.service = service;
        this.contexts = contexts;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PRIVACY_CASE_VIEW')")
    @Operation(summary = "List compliance cases")
    public PageResponse<ComplianceCase> list(@RequestParam(required=false) String status,
                                              @RequestParam(defaultValue="0") @Min(0) int page,
                                              @RequestParam(defaultValue="25") @Min(1) @Max(200) int size) {
        return service.list(status, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PRIVACY_CASE_VIEW')")
    @Operation(summary = "Get a case and its routed tasks")
    public ComplianceCase get(@PathVariable String id) { return service.get(id); }

    @GetMapping("/{caseId}/tasks/{taskId}")
    @PreAuthorize("hasAuthority('SCOPE_PRIVACY_INTERNAL')")
    @Operation(summary = "Get a task assigned to the authenticated owning service")
    public CaseService.AssignedTask getTask(@PathVariable String caseId, @PathVariable String taskId,
                                           HttpServletRequest request, Authentication authentication) {
        return service.getTask(caseId, taskId, contexts.resolve(request, authentication));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PRIVACY_CASE_CREATE')")
    @Operation(summary = "Open and route a compliance case")
    public ComplianceCase create(@Valid @RequestBody CaseRequest input, HttpServletRequest request,
                                 Authentication authentication) {
        return service.create(input.toCommand(), contexts.resolve(request, authentication));
    }

    @PostMapping("/{id}/identity-verification")
    @PreAuthorize("hasAuthority('PRIVACY_CASE_VERIFY_IDENTITY')")
    @Operation(summary = "Record identity verification")
    public ComplianceCase verify(@PathVariable String id, @RequestHeader("If-Match") long version,
                                 HttpServletRequest request, Authentication authentication) {
        return service.verifyIdentity(id, version, contexts.resolve(request, authentication));
    }

    @PostMapping("/{caseId}/tasks/{taskId}/completion")
    @PreAuthorize("hasAnyAuthority('PRIVACY_CASE_WORK','SCOPE_PRIVACY_INTERNAL')")
    @Operation(summary = "Complete a routed service task")
    public ComplianceCase completeTask(@PathVariable String caseId, @PathVariable String taskId,
                                       @RequestHeader("If-Match") long version,
                                       @Valid @RequestBody TaskCompletion input,
                                       HttpServletRequest request, Authentication authentication) {
        return service.completeTask(caseId, taskId, input.toCommand(), version,
                contexts.resolve(request, authentication));
    }

    @PostMapping("/{id}/closure")
    @PreAuthorize("hasAuthority('PRIVACY_CASE_CLOSE')")
    @Operation(summary = "Close a completed compliance case")
    public ComplianceCase close(@PathVariable String id, @RequestHeader("If-Match") long version,
                                HttpServletRequest request, Authentication authentication) {
        return service.close(id, version, contexts.resolve(request, authentication));
    }

    public record CaseRequest(@NotNull ComplianceCase.CaseType type,
                              @NotNull ComplianceCase.Priority priority,
                              String requesterService, String requesterExternalId, String requesterAuthorityRef,
                              @NotBlank String subjectService, @NotBlank String subjectExternalId,
                              String purposeId, String targetService, @Size(max=8) java.util.List<@NotBlank String> targetServices,
                              String targetResourceType,
                              String targetExternalId, @NotBlank @Size(max=4000) String reason,
                              OffsetDateTime dueAt) {
        CaseService.CreateCase toCommand() {
            return new CaseService.CreateCase(type, priority, requesterService, requesterExternalId,
                    requesterAuthorityRef, subjectService, subjectExternalId, purposeId, targetService, targetServices,
                    targetResourceType, targetExternalId, reason, dueAt);
        }
    }

    public record TaskCompletion(@NotNull ComplianceCase.TaskStatus status,
                                 @Size(max=200) String responseReference,
                                 @Size(max=4000) String failureReason) {
        CaseService.CompleteTask toCommand() {
            return new CaseService.CompleteTask(status, responseReference, failureReason);
        }
    }
}
