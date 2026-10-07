package com.moneybags.treasury.api;

import static com.moneybags.treasury.api.TreasuryRequests.*;

import com.moneybags.treasury.domain.TreasuryModels.*;
import com.moneybags.treasury.service.TreasuryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Versioned HTTP boundary for Treasury users and Modules 5/6. */
@RestController
@RequestMapping("/api/v1/treasury")
@Tag(name = "Treasury", description = "Reserve, liquidity, settlement and reconciliation operations")
public class TreasuryController {
    private final TreasuryService service;

    public TreasuryController(TreasuryService service) { this.service = service; }

    /** Registers an RBI/RTGS reserve account. */
    @PostMapping("/reserve-accounts")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_LIQUIDITY_MANAGE')")
    @Operation(summary = "Create a reserve account and zero position")
    public ResponseEntity<ReserveAccount> createAccount(@Valid @RequestBody CreateReserveAccount body, Principal principal) {
        ReserveAccount created = service.createReserveAccount(body, actor(principal));
        return ResponseEntity.created(location("/api/v1/treasury/reserve-accounts/{id}", created.id())).body(created);
    }

    /** Lists reserve account configuration. */
    @GetMapping("/reserve-accounts")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_LIQUIDITY_MANAGE') or @treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public List<ReserveAccount> accounts() { return service.reserveAccounts(); }

    /** Returns the current reserve read model. */
    @GetMapping("/reserve-accounts/{id}/position")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_LIQUIDITY_MANAGE') or @treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public ReservePosition position(@PathVariable long id) { return service.position(id); }

    /** Confirmed entries in the bank's local RBI/RTGS reserve mirror. */
    @GetMapping("/reserve-accounts/{id}/ledger")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public List<ReserveLedgerLine> reserveLedger(@PathVariable long id,
                                                  @RequestParam(defaultValue="50") int limit,
                                                  @RequestParam(defaultValue="0") int offset) {
        if(limit<1||limit>200||offset<0)throw com.moneybags.treasury.domain.DomainException.invalid("Use a limit from 1 to 200 and a non-negative offset");
        return service.reserveLedger(id, limit, offset);
    }

    @GetMapping("/reserve-accounts/{id}/reconciliation")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public ReserveReconciliation reserveReconciliation(@PathVariable long id) {
        return service.reserveReconciliation(id);
    }

    /** Earmarks liquidity for exactly one payment or settlement cycle. */
    @PostMapping("/liquidity-holds")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_LIQUIDITY_MANAGE')")
    @Operation(summary = "Reserve liquidity", description = "Idempotent by holdKey and the canonical request hash.")
    public ResponseEntity<LiquidityHold> reserve(@Valid @RequestBody CreateLiquidityHold body, Principal principal) {
        LiquidityHold created = service.reserve(body, actor(principal));
        return ResponseEntity.created(location("/api/v1/treasury/liquidity-holds/{id}", created.id())).body(created);
    }

    /** Lists liquidity holds for support and orchestration. */
    @GetMapping("/liquidity-holds")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_LIQUIDITY_MANAGE')")
    public List<LiquidityHold> holds(@RequestParam(required=false) Long reserveAccountId,
                                     @RequestParam(required=false) String status) {
        return service.holds(reserveAccountId, status);
    }

    /** Commits a hold before dispatch or releases it when the obligation ends. */
    @PostMapping("/liquidity-holds/{id}/decision")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_LIQUIDITY_MANAGE')")
    public LiquidityHold decideHold(@PathVariable long id, @Valid @RequestBody HoldDecision body, Principal principal) {
        return service.decideHold(id, body, actor(principal));
    }

    /** Opens one clearing/rail settlement cycle. */
    @PostMapping("/settlement-cycles")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public ResponseEntity<SettlementCycle> createCycle(@Valid @RequestBody CreateCycle body, Principal principal) {
        SettlementCycle created = service.createCycle(body, actor(principal));
        return ResponseEntity.created(location("/api/v1/treasury/settlement-cycles/{id}", created.id())).body(created);
    }

    /** Lists recent settlement cycles. */
    @GetMapping("/settlement-cycles")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public List<SettlementCycle> cycles(@RequestParam(required=false) String status,
                                        @RequestParam(required=false) String railCode) {
        return service.cycles(status, railCode);
    }

    /** Adds Module 6 payment snapshots to an open cycle. */
    @PostMapping("/settlement-cycles/{id}/items")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public SettlementCycle addItems(@PathVariable long id, @Valid @RequestBody AddCycleItems body, Principal principal) {
        return service.addCycleItems(id, body, actor(principal));
    }

    /** Lists the immutable payment membership of a cycle. */
    @GetMapping("/settlement-cycles/{id}/items")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public List<SettlementCycleItem> items(@PathVariable long id) { return service.cycleItems(id); }

    /** Freezes cycle membership and totals. */
    @PostMapping("/settlement-cycles/{id}/close")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public SettlementCycle closeCycle(@PathVariable long id, Principal principal) {
        return service.closeCycle(id, actor(principal));
    }

    /** Records content-hashed evidence from a rail, clearing file or reserve statement. */
    @PostMapping("/settlement-evidence")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public ResponseEntity<SettlementEvidence> evidence(@Valid @RequestBody SettlementEvidenceCommand body,
                                                        Principal principal) {
        SettlementEvidence created = service.recordEvidence(body, actor(principal));
        return ResponseEntity.created(location("/api/v1/treasury/settlement-evidence/{id}", created.id())).body(created);
    }

    /** Appends the local reserve mirror after Module 5 has committed the GL journal. */
    @PostMapping("/reserve-movements/confirm")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    @Operation(summary = "Confirm reserve movement", description = "Requires verified evidence and an already committed Module 5 GL journal ID.")
    public TreasuryEntry confirm(@Valid @RequestBody ConfirmMovement body, Principal principal) {
        return service.confirmMovement(body, actor(principal));
    }

    /** Opens a reconciliation mismatch for operations. */
    @PostMapping("/reconciliation-exceptions")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public ResponseEntity<ReconciliationException> openException(@Valid @RequestBody OpenException body,
                                                                  Principal principal) {
        ReconciliationException created = service.openException(body, actor(principal));
        return ResponseEntity.created(location("/api/v1/treasury/reconciliation-exceptions/{id}", created.id())).body(created);
    }

    /** Lists the prioritized reconciliation queue. */
    @GetMapping("/reconciliation-exceptions")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public List<ReconciliationException> exceptions(@RequestParam(required=false) String status,
                                                     @RequestParam(required=false) String severity) {
        return service.exceptions(status, severity);
    }

    /** Resolves or waives an exception with a reason. */
    @PostMapping("/reconciliation-exceptions/{id}/resolution")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public ResponseEntity<Void> resolveException(@PathVariable long id, @Valid @RequestBody ResolveException body,
                                                  Principal principal) {
        service.resolveException(id, body, actor(principal));
        return ResponseEntity.noContent().build();
    }

    /** Opens maker/checker work. */
    @PostMapping("/work-items")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public ResponseEntity<WorkItem> createWork(@Valid @RequestBody CreateWorkItem body, Principal principal) {
        WorkItem created = service.createWork(body, actor(principal));
        return ResponseEntity.created(location("/api/v1/treasury/work-items/{id}", created.id())).body(created);
    }

    /** Lists maker/checker work. */
    @GetMapping("/work-items")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public List<WorkItem> work(@RequestParam(required=false) String status,
                               @RequestParam(required=false) String ownerId) {
        return service.workItems(status, ownerId);
    }

    /** Sends a maker's work for approval. */
    @PostMapping("/work-items/{id}/submit")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public ResponseEntity<Void> submit(@PathVariable long id, @RequestParam int expectedVersion, Principal principal) {
        service.submitWork(id, actor(principal), expectedVersion);
        return ResponseEntity.noContent().build();
    }

    /** Records an independent checker decision. */
    @PostMapping("/work-items/{id}/decision")
    @PreAuthorize("@treasuryAccess.allowed(authentication, 'TREASURY_RECONCILE')")
    public ResponseEntity<Void> decide(@PathVariable long id, @Valid @RequestBody WorkDecision body) {
        service.decideWork(id, new WorkDecision(body.decision(), com.moneybags.integration.CurrentActor.get().userId(), body.glJournalId(), body.expectedVersion()));
        return ResponseEntity.noContent().build();
    }

    private static URI location(String path, Object id) {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(path).buildAndExpand(id).toUri();
    }

    private static String actor(Principal principal) { return principal == null ? "local-operator" : principal.getName(); }
}

