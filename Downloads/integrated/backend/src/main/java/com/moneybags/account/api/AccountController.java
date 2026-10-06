package com.moneybags.account.api;

import com.moneybags.account.api.Models.*;
import com.moneybags.account.domain.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Public account API; no controller directly mutates the database. */
@RestController
@Validated
@org.springframework.transaction.annotation.Transactional
@RequestMapping("/api/v1/accounts")
@Tag(name = "Account Management")
public class AccountController {
    private final AccountService service;
    private final com.moneybags.integration.AccountControlBridge bridge;
    public AccountController(AccountService service, com.moneybags.integration.AccountControlBridge bridge) { this.service = service; this.bridge=bridge; }

    /** Searches accounts by primary CIF with bounded pagination. */
    @GetMapping
    @Operation(summary = "List accounts for a CIF")
    public Page<AccountView> list(@RequestParam String cifId,
                                  @RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(defaultValue = "0") int offset) {
        return service.byCif(cifId, limit, offset);
    }
    /** Creates a pending account after cross-module eligibility checks. */
    @PostMapping
    @Operation(summary = "Open a pending savings or current account")
    public ResponseEntity<AccountView> open(@Valid @RequestBody OpenAccount request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.open(request));
    }
    @PostMapping("/self")
    @Operation(summary = "Open a pending account for the signed-in demo customer")
    public ResponseEntity<AccountView> openSelf(@Valid @RequestBody SelfOpenAccount request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.openSelf(request));
    }
    @PostMapping("/self/{id}/activate")
    @Operation(summary = "Activate the signed-in demo customer's own pending account")
    public ResponseEntity<CommandResult> activateSelf(@PathVariable long id,@Valid @RequestBody RequestId request) {
        return ResponseEntity.accepted().body(synchronize(service.activateSelf(id,request.requestId())));
    }
    /** Returns an account and its read-only balance projection. */
    @GetMapping("/{id}")
    @Operation(summary = "Get account details")
    public AccountView get(@PathVariable long id) { return service.get(id); }
    /** Resolves an account by its customer-facing number. */
    @GetMapping("/by-number/{number}")
    @Operation(summary = "Find an account by account number")
    public AccountView byNumber(@PathVariable String number) { return service.byNumber(number); }
    /** Returns a named account child collection; collection names are fixed in the repository. */
    @GetMapping("/{id}/{collection}")
    @Operation(summary = "List parties, nominees, restrictions, limits, history, or workflow records")
    public List<Map<String, Object>> children(@PathVariable long id, @PathVariable String collection) {
        return service.children(id, collection);
    }
    /** Starts activation; Module 5's acknowledgement completes it. */
    @PostMapping("/{id}/activate")
    @Operation(summary = "Request activation and posting-fence opening")
    public ResponseEntity<CommandResult> activate(@PathVariable long id, @Valid @RequestBody RequestId request) {
        return ResponseEntity.accepted().body(synchronize(service.activate(id, request.requestId())));
    }
    /** Cancels an opening after Module 5 confirms zero funding and holds. */
    @PostMapping("/{id}/cancel-opening")
    @Operation(summary = "Cancel a pending opening after funding reversal")
    public ResponseEntity<Void> cancel(@PathVariable long id, @Valid @RequestBody ReasonCommand request) {
        service.cancelPending(id, request); return ResponseEntity.noContent().build();
    }
    /** Adds a joint holder, signatory, or guardian. */
    @PostMapping("/{id}/parties")
    @Operation(summary = "Add a non-primary account party")
    public ResponseEntity<Void> addParty(@PathVariable long id, @Valid @RequestBody PartyInput request) {
        service.addParty(id, request); return ResponseEntity.status(HttpStatus.CREATED).build();
    }
    /** Ends a party role without deleting historical identity. */
    @DeleteMapping("/{id}/parties/{partyId}")
    @Operation(summary = "End a non-primary account party role")
    public ResponseEntity<Void> endParty(@PathVariable long id, @PathVariable long partyId) {
        service.endParty(id, partyId); return ResponseEntity.noContent().build();
    }
    /** Replaces all active nominee shares in one operation. */
    @PutMapping("/{id}/nominees")
    @Operation(summary = "Replace active nominees, with shares totaling 100%")
    public ResponseEntity<Void> nominees(@PathVariable long id, @Valid @RequestBody ReplaceNominees request) {
        service.replaceNominees(id, request); return ResponseEntity.noContent().build();
    }
    /** Begins a freeze, block, or lien request. */
    @PostMapping("/{id}/restrictions")
    @Operation(summary = "Request a restriction; effective only after Module 5 acknowledgement")
    public ResponseEntity<CommandResult> restrict(@PathVariable long id, @Valid @RequestBody RestrictionCommand request) {
        return ResponseEntity.accepted().body(synchronize(service.restrict(id, request)));
    }
    /** Begins release of a restriction while the old control remains effective. */
    @PostMapping("/{id}/restrictions/{restrictionId}/release")
    @Operation(summary = "Request release of an active restriction")
    public ResponseEntity<CommandResult> release(@PathVariable long id, @PathVariable long restrictionId,
                                                  @Valid @RequestBody RequestId request) {
        return ResponseEntity.accepted().body(synchronize(service.removeRestriction(id, restrictionId, request.requestId())));
    }
    /** Creates an account-specific limit after product-policy validation. */
    @PostMapping("/{id}/limits")
    @Operation(summary = "Create an account limit")
    public ResponseEntity<Void> limit(@PathVariable long id, @Valid @RequestBody LimitCommand request) {
        service.addLimit(id, request); return ResponseEntity.status(HttpStatus.CREATED).build();
    }
    /** Creates an approved interest exception. */
    @PostMapping("/{id}/interest-overrides")
    @Operation(summary = "Create a version-bound interest override")
    public ResponseEntity<Void> interest(@PathVariable long id, @Valid @RequestBody InterestCommand request) {
        service.addInterestOverride(id, request); return ResponseEntity.status(HttpStatus.CREATED).build();
    }
    /** Adopts a new product version with treatment and consent checks. */
    @PostMapping("/{id}/product-version-adoptions")
    @Operation(summary = "Adopt a product version")
    public ResponseEntity<Void> adopt(@PathVariable long id, @Valid @RequestBody VersionCommand request) {
        service.adoptVersion(id, request); return ResponseEntity.status(HttpStatus.CREATED).build();
    }
    /** Enters closing and asks Module 5 to close the posting fence. */
    @PostMapping("/{id}/closures")
    @Operation(summary = "Request account closure")
    public ResponseEntity<CommandResult> closure(@PathVariable long id, @Valid @RequestBody ReasonCommand request) {
        return ResponseEntity.accepted().body(synchronize(service.requestClosure(id, request)));
    }
    /** Closes only after fresh authoritative clearances from peer modules. */
    @PostMapping("/{id}/closures/{requestId}/approve")
    @Operation(summary = "Approve a cleared closure request")
    public ResponseEntity<Void> approve(@PathVariable long id, @PathVariable String requestId) {
        service.approveClosure(id, requestId); return ResponseEntity.noContent().build();
    }
    /** Rejects closure and requests restoration of the posting fence. */
    @PostMapping("/{id}/closures/{requestId}/reject")
    @Operation(summary = "Reject a closure request")
    public ResponseEntity<CommandResult> reject(@PathVariable long id, @PathVariable String requestId,
                                                 @RequestBody Map<String, String> body) {
        return ResponseEntity.accepted().body(synchronize(service.rejectClosure(id, requestId, body.get("reason"))));
    }
    /** Starts an explicit minor-to-adult authority review. */
    @PostMapping("/{id}/majority-reviews")
    @Operation(summary = "Start majority review")
    public ResponseEntity<Void> startMajority(@PathVariable long id, @Valid @RequestBody MajorityStart request) {
        service.startMajority(id, request.requestId(), request.dueAt());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
    /** Decides a majority review after consent and IAM approval. */
    @PostMapping("/{id}/majority-reviews/decisions")
    @Operation(summary = "Complete or escalate majority review")
    public ResponseEntity<Void> decideMajority(@PathVariable long id, @Valid @RequestBody MajorityDecision request) {
        service.decideMajority(id, request); return ResponseEntity.noContent().build();
    }
    private CommandResult synchronize(CommandResult result) { bridge.apply(result.accountId());return new CommandResult(result.accountId(),"APPLIED",result.requestId(),result.controlVersion()); }
    public record MajorityStart(@NotBlank String requestId, @NotNull OffsetDateTime dueAt) { }
}
