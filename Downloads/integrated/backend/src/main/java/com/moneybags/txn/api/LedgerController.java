package com.moneybags.txn.api;

import com.moneybags.txn.api.Contracts.*;
import com.moneybags.txn.core.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Integration endpoints for Module 4 controls, Module 6 holds, and ledger postings. */
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "module1Bearer")
public class LedgerController {
    private final LedgerService ledger; private final com.moneybags.integration.BankingAccess access; private final com.moneybags.integration.CustomerHashService hashes;

    /** Injects the ledger service. */
    public LedgerController(LedgerService ledger,com.moneybags.integration.BankingAccess access,com.moneybags.integration.CustomerHashService hashes) { this.ledger = ledger; this.access=access; this.hashes=hashes; }

    /** Reads an authoritative customer position. */
    @Operation(summary = "Get account position", description = "Spendable = posted - holds - liens - blocks + overdraft. Module 4's balance is a projection only.")
    @GetMapping("/accounts/{accountId}/position")
    @PreAuthorize("hasAuthority('SCOPE_m05.read')")
    public PositionView position(@PathVariable long accountId, @org.springframework.web.bind.annotation.RequestHeader(value="X-Customer-Hash",required=false) String hash) { access.account("TXN_READ",accountId);hashes.require(accountId,hash);return ledger.position(accountId); }

    /** Applies the next Module 4 control event and opens or closes posting directions. */
    @Operation(summary = "Apply Module 4 control", description = "Control versions must be consecutive. The eventId deduplicates delivery. Values are absolute snapshots.")
    @PostMapping("/accounts/{accountId}/controls")
    @PreAuthorize("hasAuthority('SCOPE_m05.control')")
    public PositionView control(@PathVariable long accountId, @Valid @RequestBody ControlRequest request, Authentication actor) {
        return ledger.applyControl(accountId, request, actor.getName());
    }

    /** Reserves funds for one outbound Module 6 payment. */
    @Operation(summary = "Place a payment hold", description = "The holdKey is idempotent. Hold expiry never authorizes release without confirmed payment state.")
    @PostMapping("/holds")
    @PreAuthorize("hasAuthority('SCOPE_m05.payment')")
    public HoldView hold(@Valid @RequestBody HoldRequest request, Authentication actor) { return ledger.placeHold(request, actor.getName()); }

    /** Releases a hold after Module 6 has established a terminal failure or cancellation. */
    @Operation(summary = "Release a confirmed payment hold")
    @PostMapping("/holds/{holdId}/release")
    @PreAuthorize("hasAuthority('SCOPE_m05.payment')")
    public HoldView release(@PathVariable long holdId, @Valid @RequestBody ReleaseRequest request, Authentication actor) {
        return ledger.releaseHold(holdId, request.reasonCode(), actor.getName());
    }

    /** Posts a balanced internal journal for a trusted payment, loan, or treasury service. */
    @Operation(summary = "Post balanced journal", description = "Every line is INR, at least one debit and one credit are required, and bank account lines use a liability GL. A hold can be consumed only by its payment net debit.")
    @PostMapping("/journals")
    @PreAuthorize("hasAuthority('SCOPE_m05.journal') and principal.userType == 'SERVICE'")
    public JournalView journal(@Valid @RequestBody JournalRequest request, Authentication actor) {
        return ledger.postJournal(request, actor.getName());
    }

    /** Reads the Oracle double-entry control view for one journal. */
    @Operation(summary = "Get journal balance control")
    @GetMapping("/journals/{journalId}/control")
    @PreAuthorize("hasAuthority('SCOPE_m05.read')")
    public Map<String, Object> journalControl(@PathVariable long journalId) { return ledger.journalControl(journalId); }

    /** Lists up to 200 account position mismatches for reconciliation staff. */
    @Operation(summary = "List account position exceptions")
    @GetMapping("/reconciliation/positions")
    @PreAuthorize("hasAuthority('SCOPE_m05.reconcile')")
    public List<Map<String, Object>> positionExceptions() { access.global("GL_RECONCILE");return ledger.positionExceptions(); }

    /** Lists up to 200 journals whose debit and credit totals differ. */
    @Operation(summary = "List unbalanced journals")
    @GetMapping("/reconciliation/journals")
    @PreAuthorize("hasAuthority('SCOPE_m05.reconcile')")
    public List<Map<String, Object>> unbalancedJournals() { access.global("GL_RECONCILE");return ledger.unbalancedJournals(); }
}
