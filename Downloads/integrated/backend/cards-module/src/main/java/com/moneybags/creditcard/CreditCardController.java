package com.moneybags.creditcard;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.moneybags.creditcard.CreditCardDtos.*;

@RestController
@RequestMapping("/api/v1/credit-cards")
@Validated
public class CreditCardController {
    private final CreditCardService service;
    public CreditCardController(CreditCardService service){this.service=service;}

    @GetMapping("/products") @Operation(summary="Browse credit card products")
    public List<Map<String,Object>> products(){return service.products();}

    @PostMapping("/products") @Operation(summary="Propose a credit card product",description="Create immutable versioned terms for independent approval. INR; simple daily ACT/365 interest without a grace period; no annual or late fees.")
    public Map<String,Object> createProduct(@Valid @RequestBody ProductInput request){return service.createProduct(request);}

    @PostMapping("/products/{id}/decision") @Operation(summary="Approve or reject credit card terms",description="Independent employee approval. Leave approved limit empty; that field applies only to customer applications.")
    public Map<String,Object> decideProduct(@PathVariable String id,@Valid @RequestBody Decision request){return service.decideProduct(id,request);}

    @PostMapping("/products/{id}/retire") @Operation(summary="Retire a credit card product",description="Stops new applications and approvals. Existing cards retain their agreed terms.")
    public Map<String,Object> retireProduct(@PathVariable String id,@Valid @RequestBody Command request){return service.retireProduct(id,request);}

    @GetMapping("/applications") @Operation(summary="Review credit card applications")
    public List<Map<String,Object>> applications(){return service.applications();}

    @PostMapping("/applications") @Operation(summary="Apply for a credit card",description="Accept the disclosed product version. Requires an adult individual with current verified KYC and an active personal savings/current account.")
    public Map<String,Object> apply(@Valid @RequestBody ApplicationInput request){return service.apply(request);}

    @PostMapping("/applications/{id}/decision") @Operation(summary="Decide a credit card application",description="An independent employee with CC_LIMIT amount and APR authority approves the requested limit or a lower amount. Approval issues an inactive simulated card.")
    public Map<String,Object> decideApplication(@PathVariable String id,@Valid @RequestBody Decision request){return service.decideApplication(id,request);}

    @PostMapping("/applications/{id}/cancel") @Operation(summary="Cancel a pending credit card application")
    public Map<String,Object> cancel(@PathVariable String id,@Valid @RequestBody Command request){return service.cancelApplication(id,request);}

    @GetMapping @Operation(summary="List your credit cards")
    public List<Map<String,Object>> cards(){return service.cards();}

    @GetMapping("/{id}") @Operation(summary="View card balance and available credit")
    public Map<String,Object> card(@PathVariable String id){return service.card(id);}

    @PostMapping("/{id}/controls") @Operation(summary="Activate, freeze, unblock a freeze, or close a card",description="An employee can permanently block a card. Closure requires zero principal and interest. Repayment remains available on frozen, blocked or expired cards.")
    public Map<String,Object> control(@PathVariable String id,@Valid @RequestBody Control request){return service.control(id,request);}

    @PostMapping("/{id}/purchases") @Operation(summary="Make a simulated card purchase",description="Immediate local posting to a simulated merchant settlement account. Enforces available credit, current KYC, expiry and overdue minimum payments.")
    public Map<String,Object> purchase(@PathVariable String id,@Valid @RequestBody Purchase request){return service.purchase(id,request);}

    @PostMapping("/{id}/repayments") @Operation(summary="Repay a credit card",description="Debits the agreed repayment account atomically. Accrued interest is posted first; repayment clears interest before principal. Overpayment is rejected.")
    public Map<String,Object> repay(@PathVariable String id,@Valid @RequestBody Repayment request){return service.repay(id,request);}

    @PostMapping("/{id}/purchases/{entryId}/refund") @Operation(summary="Refund a simulated purchase",description="Authorized employee, full refund once. Reduces principal first; any excess returns to the linked deposit account. Previously accrued interest is not waived.")
    public Map<String,Object> refund(@PathVariable String id,@PathVariable @Min(1) long entryId,@Valid @RequestBody Refund request){return service.refund(id,entryId,request);}

    @PostMapping("/{id}/statements") @Operation(summary="Generate the next credit card statement",description="Enter the card's next scheduled statement date. Future or skipped billing periods are rejected. Catch up all due statements before new transactions.")
    public Map<String,Object> bill(@PathVariable String id,@Valid @RequestBody Billing request){return service.bill(id,request);}

    @GetMapping("/{id}/transactions") @Operation(summary="Review card transactions")
    public List<Map<String,Object>> entries(@PathVariable String id,@RequestParam(defaultValue="0") @Min(0) long afterEntry,
                                           @RequestParam(defaultValue="50") @Min(1) @Max(200) int limit){return service.entries(id,afterEntry,limit);}

    @GetMapping("/{id}/statements") @Operation(summary="List credit card statements")
    public List<Map<String,Object>> statements(@PathVariable String id){return service.statements(id);}

    @GetMapping("/{id}/statements/{statementId}") @Operation(summary="Read a credit card statement and its transactions")
    public Map<String,Object> statement(@PathVariable String id,@PathVariable String statementId){return service.statement(id,statementId);}

    @GetMapping("/{id}/reconciliation") @Operation(summary="Reconcile card balance with the ledger")
    public Map<String,Object> reconciliation(@PathVariable String id){return service.reconciliation(id);}
}
