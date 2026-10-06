package com.moneybags.payments.api;

import com.moneybags.payments.core.DispatchService;
import com.moneybags.payments.core.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Public instruction reads and narrowly scoped internal orchestration commands. */
@RestController
@RequestMapping("/api/v1")
public class PaymentController {
    private final PaymentService payments;
    private final DispatchService dispatches;

    /** Receives payment and rail dispatch use cases. */
    public PaymentController(PaymentService payments, DispatchService dispatches) {
        this.payments = payments; this.dispatches = dispatches;
    }

    /** Creates a payment using the originator/channel/request-key idempotency scope. */
    @Operation(summary = "Create payment instruction", description = "Returns the original payment for an identical request-key replay; a different payload returns 409.")
    @PostMapping("/payments")
    @PreAuthorize("hasAuthority('SCOPE_M06_PAYMENT_WRITE')")
    public ResponseEntity<Contracts.Payment> create(@Valid @RequestBody Contracts.CreatePayment request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(payments.create(request));
    }

    /** Lists a bounded, optionally status-filtered payment queue. */
    @Operation(summary = "List recent payments")
    @GetMapping("/payments")
    @PreAuthorize("hasAuthority('SCOPE_M06_READ')")
    public List<Contracts.Payment> list(@RequestParam(required = false) String status) { return payments.list(status); }

    /** Reads one payment and its current optimistic version. */
    @Operation(summary = "Get payment")
    @GetMapping("/payments/{id}")
    @PreAuthorize("hasAuthority('SCOPE_M06_READ')")
    public Contracts.Payment get(@PathVariable long id) { return payments.get(id); }

    /** Reads its immutable state changes. */
    @Operation(summary = "Get payment status history")
    @GetMapping("/payments/{id}/history")
    @PreAuthorize("hasAuthority('SCOPE_M06_READ')")
    public List<Map<String, Object>> history(@PathVariable long id) { return payments.history(id); }

    /** Applies a direction-aware state transition after evidence checks. */
    @Operation(summary = "Advance payment state", description = "Internal orchestration only. The database transition matrix and required rail, journal and treasury evidence are checked before commit.")
    @PostMapping("/payments/{id}/transitions")
    @PreAuthorize("hasAuthority('SCOPE_M06_INTERNAL')")
    public Contracts.Payment transition(@PathVariable long id, @Valid @RequestBody Contracts.Transition request) {
        return payments.transition(id, request);
    }

    /** Ingests one immutable rail callback, clearing or inquiry observation. */
    @Operation(summary = "Record rail status evidence", description = "The SHA-256 hash is unique per rail. A replay returns the existing evidence ID.")
    @PostMapping("/payments/{id}/rail-evidence")
    @PreAuthorize("hasAuthority('SCOPE_M06_RAIL')")
    public Map<String, Long> evidence(@PathVariable long id, @Valid @RequestBody Contracts.RailEvidence request) {
        return Map.of("evidenceId", payments.evidence(id, request));
    }

    /** Lists stored rail evidence references for one payment. */
    @Operation(summary = "List rail evidence")
    @GetMapping("/payments/{id}/rail-evidence")
    @PreAuthorize("hasAuthority('SCOPE_M06_READ')")
    public List<Map<String, Object>> evidenceList(@PathVariable long id) { return payments.evidenceList(id); }

    /** Queues one stable outbound rail message after Module 5 debit posting. */
    @Operation(summary = "Prepare outbound rail dispatch")
    @PostMapping("/payments/{id}/dispatch")
    @PreAuthorize("hasAuthority('SCOPE_M06_INTERNAL')")
    public Map<String, Object> prepare(@PathVariable long id, @Valid @RequestBody Contracts.PrepareDispatch request) {
        return dispatches.prepare(id, request);
    }

    /** Lists READY, INQUIRY_REQUIRED or another specific dispatch state. */
    @Operation(summary = "List rail adapter work")
    @GetMapping("/dispatches")
    @PreAuthorize("hasAuthority('SCOPE_M06_RAIL')")
    public List<Map<String, Object>> queue(@RequestParam(defaultValue = "READY") String status) { return dispatches.queue(status); }

    /** Stores a send or inquiry attempt outcome for the rail adapter. */
    @Operation(summary = "Record dispatch attempt")
    @PostMapping("/dispatches/{id}/attempts")
    @PreAuthorize("hasAuthority('SCOPE_M06_RAIL')")
    public Map<String, Object> attempt(@PathVariable long id, @Valid @RequestBody Contracts.DispatchAttempt request) {
        return dispatches.attempt(id, request);
    }

    /** Reads the attempt log for investigation and retry decisions. */
    @Operation(summary = "List dispatch attempts")
    @GetMapping("/dispatches/{id}/attempts")
    @PreAuthorize("hasAnyAuthority('SCOPE_M06_RAIL','SCOPE_M06_READ')")
    public List<Map<String, Object>> attempts(@PathVariable long id) { return dispatches.attempts(id); }
}

