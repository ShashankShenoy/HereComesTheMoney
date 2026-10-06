package com.moneybags.txn.api;

import com.moneybags.txn.api.Contracts.TransactionView;
import com.moneybags.txn.api.Contracts.TransferRequest;
import com.moneybags.txn.api.Contracts.ReversalRequest;
import com.moneybags.txn.api.Contracts.ApprovalDecision;
import com.moneybags.txn.core.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer transaction endpoints. */
@RestController
@RequestMapping("/api/v1/transactions")
@SecurityRequirement(name = "module1Bearer")
public class TransactionController {
    private final LedgerService ledger; private final com.moneybags.integration.BankingService banking;
    private final com.moneybags.integration.BankingAccess access;

    /** Injects the transactional write service. */
    public TransactionController(LedgerService ledger, com.moneybags.integration.BankingService banking,com.moneybags.integration.BankingAccess access) { this.ledger = ledger; this.banking=banking;this.access=access; }

    /** Transfers money between two active accounts and returns the posted transaction. */
    @Operation(summary = "Post an internal transfer", description = "Idempotent for originator, channelCode, and requestKey. Requires published M03 rules, M05 GL mappings, and open M04 fences.")
    @PostMapping("/transfers")
    @PreAuthorize("hasAuthority('SCOPE_m05.transfer')")
    public TransactionView transfer(@Valid @RequestBody TransferRequest request, Authentication actor) {
        return banking.transfer(request);
    }

    /** Reads a Module 5 transaction and its first linked journal. */
    @Operation(summary = "Get a transaction")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_m05.read')")
    public TransactionView transaction(@PathVariable long id) { return ledger.transaction(id); }

    /** Creates a pending maker-checker reversal request. */
    @Operation(summary = "Request transfer reversal")
    @PostMapping("/{id}/reversal-requests")
    @PreAuthorize("hasAuthority('SCOPE_m05.reversal.request')")
    public TransactionView requestReversal(@PathVariable long id, @Valid @RequestBody ReversalRequest request, Authentication actor) {
        access.transaction("TXN_REVERSE",id);
        return ledger.requestReversal(id, request, actor.getName());
    }

    /** Approves or rejects a reversal as a distinct checker. */
    @Operation(summary = "Decide transfer reversal")
    @PostMapping("/{id}/reversal-decision")
    @PreAuthorize("hasAuthority('SCOPE_m05.reversal.approve')")
    public TransactionView decideReversal(@PathVariable long id, @Valid @RequestBody ApprovalDecision request, Authentication actor) {
        access.transaction("TXN_REVERSE_APPROVE",id);
        return ledger.decideReversal(id, request, actor.getName());
    }
}

