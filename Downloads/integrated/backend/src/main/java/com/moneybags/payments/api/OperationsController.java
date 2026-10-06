package com.moneybags.payments.api;

import com.moneybags.payments.core.OperationsService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Clearing, reconciliation, exception and maker-checker endpoints for operations staff. */
@RestController
@RequestMapping("/api/v1")
public class OperationsController {
    private final OperationsService service;
    private final com.moneybags.integration.BankingAccess access;

    /** Receives the operations use cases. */
    public OperationsController(OperationsService service,com.moneybags.integration.BankingAccess access) { this.service = service;this.access=access; }

    /** Opens a clearing batch. */
    @Operation(summary = "Open clearing batch")
    @PostMapping("/clearing-batches")
    @PreAuthorize("hasAuthority('SCOPE_M06_OPERATIONS')")
    public Map<String, Object> createBatch(@Valid @RequestBody Contracts.CreateBatch request) { access.global("PAYMENT_OPERATE"); return service.createBatch(request); }

    /** Lists the recent clearing batches. */
    @Operation(summary = "List clearing batches")
    @GetMapping("/clearing-batches")
    @PreAuthorize("hasAuthority('SCOPE_M06_READ')")
    public List<Map<String, Object>> batches() { access.global("PAYMENT_READ"); return service.batches(); }

    /** Reads one batch with gross and net amounts. */
    @Operation(summary = "Get clearing batch")
    @GetMapping("/clearing-batches/{id}")
    @PreAuthorize("hasAuthority('SCOPE_M06_READ')")
    public Map<String, Object> batch(@PathVariable long id) { access.global("PAYMENT_READ"); return service.batch(id); }

    /** Adds one eligible accepted payment to an open batch. */
    @Operation(summary = "Add clearing item")
    @PostMapping("/clearing-batches/{id}/items")
    @PreAuthorize("hasAuthority('SCOPE_M06_OPERATIONS')")
    public Map<String, Object> addBatchItem(@PathVariable long id, @Valid @RequestBody Contracts.AddBatchItem request) { access.global("PAYMENT_OPERATE");
        return service.addBatchItem(id, request);
    }

    /** Lists the items assigned to a batch. */
    @Operation(summary = "List clearing items")
    @GetMapping("/clearing-batches/{id}/items")
    @PreAuthorize("hasAuthority('SCOPE_M06_READ')")
    public List<Map<String, Object>> batchItems(@PathVariable long id) { access.global("PAYMENT_READ"); return service.batchItems(id); }

    /** Closes a nonempty batch to further additions. */
    @Operation(summary = "Close clearing batch")
    @PostMapping("/clearing-batches/{id}/close")
    @PreAuthorize("hasAuthority('SCOPE_M06_OPERATIONS')")
    public Map<String, Object> closeBatch(@PathVariable long id) { access.global("PAYMENT_OPERATE"); return service.closeBatch(id); }

    /** Links a matching Module 7 settlement cycle and reflects its finality. */
    @Operation(summary = "Link Module 7 settlement cycle")
    @PostMapping("/clearing-batches/{id}/settlement")
    @PreAuthorize("hasAuthority('SCOPE_M06_TREASURY')")
    public Map<String, Object> linkSettlement(@PathVariable long id, @Valid @RequestBody Contracts.BatchSettlement request) {
        return service.linkSettlement(id, request);
    }

    /** Runs the four-control payment reconciliation and appends a versioned result. */
    @Operation(summary = "Run payment reconciliation", description = "Checks Module 5 posting and suspense, rail settlement evidence, Module 7 verified reserve movement and amount equality.")
    @PostMapping("/payments/{id}/reconciliations")
    @PreAuthorize("hasAuthority('SCOPE_M06_RECONCILE')")
    public Map<String, Object> reconcile(@PathVariable long id, @Valid @RequestBody Contracts.Reconcile request) { access.global("PAYMENT_RECONCILE");
        return service.reconcile(id, request);
    }

    /** Reads the current reconciliation summary. */
    @Operation(summary = "Get current reconciliation")
    @GetMapping("/payments/{id}/reconciliation")
    @PreAuthorize("hasAuthority('SCOPE_M06_READ')")
    public Map<String, Object> reconciliation(@PathVariable long id) { access.global("PAYMENT_READ"); return service.reconciliation(id); }

    /** Reads all append-only reconciliation runs. */
    @Operation(summary = "List reconciliation runs")
    @GetMapping("/payments/{id}/reconciliation-runs")
    @PreAuthorize("hasAuthority('SCOPE_M06_READ')")
    public List<Map<String, Object>> reconciliationRuns(@PathVariable long id) { access.global("PAYMENT_READ"); return service.reconciliationRuns(id); }

    /** Lists reconciliation exceptions, optionally filtered by status. */
    @Operation(summary = "List reconciliation exceptions")
    @GetMapping("/reconciliation-exceptions")
    @PreAuthorize("hasAuthority('SCOPE_M06_READ')")
    public List<Map<String, Object>> exceptions(@RequestParam(required = false) String status) { access.global("PAYMENT_READ"); return service.exceptions(status); }

    /** Assigns, resolves or waives one exception. */
    @Operation(summary = "Act on reconciliation exception")
    @PostMapping("/reconciliation-exceptions/{id}/actions")
    @PreAuthorize("hasAuthority('SCOPE_M06_OPERATIONS')")
    public Map<String, Object> exceptionAction(@PathVariable long id, @Valid @RequestBody Contracts.ExceptionAction request) { access.global("PAYMENT_OPERATE");
        return service.exceptionAction(id, request);
    }

    /** Creates a pending manual-action approval request. */
    @Operation(summary = "Request maker-checker approval")
    @PostMapping("/approvals")
    @PreAuthorize("hasAuthority('SCOPE_M06_MAKER')")
    public Map<String, Object> requestApproval(@Valid @RequestBody Contracts.ApprovalRequest request) { access.global("PAYMENT_CREATE");
        return service.requestApproval(request);
    }

    /** Lists approvals by their current state. */
    @Operation(summary = "List approval tasks")
    @GetMapping("/approvals")
    @PreAuthorize("hasAuthority('SCOPE_M06_READ')")
    public List<Map<String, Object>> approvals(@RequestParam(defaultValue = "PENDING") String status) { access.global("PAYMENT_READ"); return service.approvals(status); }

    /** Records an independent checker decision without executing the approved action. */
    @Operation(summary = "Decide approval")
    @PostMapping("/approvals/{id}/decision")
    @PreAuthorize("hasAuthority('SCOPE_M06_CHECKER')")
    public Map<String, Object> decideApproval(@PathVariable long id, @Valid @RequestBody Contracts.ApprovalDecision request) { access.global("PAYMENT_APPROVE");
        return service.decideApproval(id, request);
    }
}

