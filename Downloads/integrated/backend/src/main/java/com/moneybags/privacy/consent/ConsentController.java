package com.moneybags.privacy.consent;

import com.moneybags.privacy.common.RequestContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

/** Customer, staff and internal-service API boundary for consent decisions. */
@RestController
@RequestMapping("/api/v1/privacy/consents")
public class ConsentController {
    private final ConsentService service;
    private final RequestContextResolver contexts;

    public ConsentController(ConsentService service, RequestContextResolver contexts) {
        this.service = service;
        this.contexts = contexts;
    }

    /** Captures a grant or denial with identity, notice and idempotency evidence. */
    @PostMapping("/decisions")
    @PreAuthorize("hasAnyAuthority('PRIVACY_CONSENT_CAPTURE','PRIVACY_CONSENT_SELF')")
    @Operation(summary = "Capture a consent grant or denial")
    public ConsentDecision decide(@Valid @RequestBody DecisionRequest input,
                                  HttpServletRequest request, Authentication authentication) {
        return service.decide(input.toCommand(), contexts.resolve(request, authentication));
    }

    /** Captures withdrawal without modifying or deleting the original grant. */
    @PostMapping("/withdrawals")
    @PreAuthorize("hasAnyAuthority('PRIVACY_CONSENT_CAPTURE','PRIVACY_CONSENT_SELF')")
    @Operation(summary = "Withdraw optional consent")
    public ConsentDecision withdraw(@Valid @RequestBody WithdrawalRequest input,
                                    HttpServletRequest request, Authentication authentication) {
        return service.withdraw(input.toCommand(), contexts.resolve(request, authentication));
    }

    /** Returns immutable decision history for one subject and purpose. */
    @GetMapping("/history")
    @PreAuthorize("hasAnyAuthority('PRIVACY_CONSENT_VIEW','PRIVACY_CONSENT_SELF')")
    @Operation(summary = "Get consent history")
    public List<ConsentDecision> history(@RequestParam String subjectService,
                                         @RequestParam String subjectExternalId,
                                         @RequestParam String purposeCode,
                                         HttpServletRequest request, Authentication authentication) {
        return service.historyAuthorized(subjectService, subjectExternalId, purposeCode,
                contexts.resolve(request, authentication));
    }

    /** Synchronous internal check used by optional downstream processing. */
    @GetMapping("/evaluations")
    @PreAuthorize("hasAnyAuthority('PRIVACY_CONSENT_VIEW','PRIVACY_CONSENT_SELF','SCOPE_PRIVACY_INTERNAL')")
    @Operation(summary = "Evaluate current consent")
    public ConsentService.ConsentEvaluation evaluate(@RequestParam String subjectService,
                                                      @RequestParam String subjectExternalId,
                                                      @RequestParam String purposeCode,
                                                      @RequestParam(required = false) OffsetDateTime at,
                                                      HttpServletRequest request, Authentication authentication) {
        return service.evaluateAuthorized(subjectService, subjectExternalId, purposeCode, at,
                contexts.resolve(request, authentication));
    }

    public record DecisionRequest(
            @NotBlank @Size(max=120) String subjectService,
            @NotBlank @Size(max=100) String subjectExternalId,
            @Size(max=36) String sourceConsentId,
            @NotBlank @Size(max=80) String purposeCode,
            @NotNull ConsentDecision.Decision decision,
            @NotBlank @Size(max=50) String channelCode,
            @NotBlank @Pattern(regexp="[0-9a-fA-F]{64}") String noticeSha256,
            OffsetDateTime effectiveAt, OffsetDateTime expiresAt,
            @Size(max=1000) String evidenceUri,
            @Pattern(regexp="[0-9a-fA-F]{64}") String evidenceSha256) {
        ConsentService.DecideConsent toCommand() {
            return new ConsentService.DecideConsent(subjectService, subjectExternalId, sourceConsentId, purposeCode, decision,
                    channelCode, noticeSha256, effectiveAt, expiresAt, evidenceUri, evidenceSha256);
        }
    }

    public record WithdrawalRequest(
            @NotBlank String subjectService, @NotBlank String subjectExternalId,
            @NotBlank String purposeCode, @NotBlank String channelCode,
            String evidenceUri, @Pattern(regexp="[0-9a-fA-F]{64}") String evidenceSha256) {
        ConsentService.WithdrawConsent toCommand() {
            return new ConsentService.WithdrawConsent(subjectService, subjectExternalId, purposeCode,
                    channelCode, evidenceUri, evidenceSha256);
        }
    }
}
