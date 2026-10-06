package com.moneybags.txn.api;

import com.moneybags.txn.api.OperationsContracts.*;
import com.moneybags.txn.core.OperationsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Restricted operational setup and maker-checker period closure. */
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "module1Bearer")
public class OperationsController {
    private final OperationsService service;
    private final com.moneybags.integration.BankingAccess access;

    /** Injects the operational write service. */
    public OperationsController(OperationsService service,com.moneybags.integration.BankingAccess access) { this.service = service;this.access=access; }

    /** Adds an INR GL account. */
    @Operation(summary = "Create GL account")
    @PostMapping("/gl/accounts")
    @PreAuthorize("hasAuthority('SCOPE_m05.admin')")
    public Map<String, Object> createGl(@Valid @RequestBody GlAccountRequest request) { access.global("GL_ADMIN");return service.createGl(request); }

    /** Maps a product version and role to a GL account. */
    @Operation(summary = "Create product GL mapping")
    @PostMapping("/gl/mappings")
    @PreAuthorize("hasAuthority('SCOPE_m05.admin')")
    public Map<String, Object> createMapping(@Valid @RequestBody MappingRequest request, Authentication actor) {
        access.global("GL_ADMIN");return service.createMapping(request, actor.getName());
    }

    /** Creates a pending period-close request. */
    @Operation(summary = "Request period close")
    @PostMapping("/period-closes")
    @PreAuthorize("hasAuthority('SCOPE_m05.close.request')")
    public Map<String, Object> requestClose(@Valid @RequestBody CloseRequest request, Authentication actor) {
        access.global("GL_CLOSE");return service.requestClose(request, actor.getName());
    }

    /** Approves or rejects a close as a different IAM actor. */
    @Operation(summary = "Decide period close")
    @PostMapping("/period-closes/{id}/decision")
    @PreAuthorize("hasAuthority('SCOPE_m05.close.approve')")
    public Map<String, Object> decideClose(@PathVariable long id, @Valid @RequestBody CloseDecision request, Authentication actor) {
        access.global("GL_CLOSE_APPROVE");return service.decideClose(id, request, actor.getName());
    }

    /** Records a fee assessment pending a separate posting or waiver workflow. */
    @Operation(summary = "Assess a fee")
    @PostMapping("/fees")
    @PreAuthorize("hasAuthority('SCOPE_m05.fee')")
    public Map<String, Object> assessFee(@Valid @RequestBody FeeRequest request) { if(request.bankAccountId()!=null)access.account("FEE_ASSESS",request.bankAccountId());else if(request.loanFacilityId()!=null)access.facility("FEE_ASSESS",request.loanFacilityId());return service.assessFee(request); }
}
