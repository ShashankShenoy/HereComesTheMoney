package com.moneybags.statements;

import com.moneybags.statements.ApiModels.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.moneybags.iam.security.UserPrincipal;
import org.springframework.web.bind.annotation.*;

/** Separate policy administration endpoints for approved immutable versions. */
@RestController @RequestMapping("/api/v1/catalog") @Tag(name="Statement policy catalog")
@SecurityRequirement(name="bearerAuth")
public class CatalogController {
    private final CatalogService service;
    public CatalogController(CatalogService service) { this.service=service; }
    /** Creates a draft masking profile version. */
    @PostMapping("/masking-profiles") @Operation(summary="Create masking draft")
    public Map<String,String> mask(@AuthenticationPrincipal UserPrincipal jwt,@Valid @RequestBody PolicyDraft body) {
        return Map.of("id",service.maskDraft(Actor.from(jwt),body));
    }
    /** Lists masking profile metadata. */
    @GetMapping("/masking-profiles") @Operation(summary="List masking profiles")
    public List<Map<String,Object>> masks(@AuthenticationPrincipal UserPrincipal jwt) { return service.masks(Actor.from(jwt)); }
    /** Approves a draft after a fresh independent authorization check. */
    @PostMapping("/masking-profiles/{id}/approve") @Operation(summary="Approve masking version")
    public ResponseEntity<Void> approveMask(@AuthenticationPrincipal UserPrincipal jwt,@PathVariable String id) {
        service.approveMask(Actor.from(jwt),id);return ResponseEntity.noContent().build();
    }
    /** Retires an approved masking version. */
    @PostMapping("/masking-profiles/{id}/retire") @Operation(summary="Retire masking version")
    public ResponseEntity<Void> retireMask(@AuthenticationPrincipal UserPrincipal jwt,@PathVariable String id) {
        service.retireMask(Actor.from(jwt),id);return ResponseEntity.noContent().build();
    }
    /** Creates a narration draft. */
    @PostMapping("/narrations") @Operation(summary="Create narration draft")
    public Map<String,String> narration(@AuthenticationPrincipal UserPrincipal jwt,@Valid @RequestBody NarrationDraft body) {
        return Map.of("id",service.narrationDraft(Actor.from(jwt),body));
    }
    /** Lists narration metadata. */
    @GetMapping("/narrations") @Operation(summary="List narration versions")
    public List<Map<String,Object>> narrations(@AuthenticationPrincipal UserPrincipal jwt) { return service.narrations(Actor.from(jwt)); }
    /** Approves a narration version. */
    @PostMapping("/narrations/{id}/approve") @Operation(summary="Approve narration")
    public ResponseEntity<Void> approveNarration(@AuthenticationPrincipal UserPrincipal jwt,@PathVariable String id) {
        service.approveNarration(Actor.from(jwt),id);return ResponseEntity.noContent().build();
    }
    /** Retires an approved narration version. */
    @PostMapping("/narrations/{id}/retire") @Operation(summary="Retire narration")
    public ResponseEntity<Void> retireNarration(@AuthenticationPrincipal UserPrincipal jwt,@PathVariable String id) {
        service.retireNarration(Actor.from(jwt),id);return ResponseEntity.noContent().build();
    }
}

