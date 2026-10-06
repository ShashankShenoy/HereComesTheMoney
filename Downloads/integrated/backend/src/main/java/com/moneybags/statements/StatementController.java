package com.moneybags.statements;

import com.moneybags.statements.ApiModels.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.moneybags.iam.security.UserPrincipal;
import org.springframework.web.bind.annotation.*;

/** Versioned public and worker APIs for Module 10. */
@RestController
@RequestMapping("/api/v1/reporting")
@Tag(name="Statement Reporting")
@SecurityRequirement(name="bearerAuth")
public class StatementController {
    private final RequestService requests;
    private final StatementService statements;
    private final DeliveryService deliveries;
    private final EventService events;
    private final AuthorizationService auth;
    private final String workerAudience;
    public StatementController(RequestService requests,StatementService statements,DeliveryService deliveries,
        EventService events,AuthorizationService auth,@Value("${moneybags.worker-token-audience}") String workerAudience) {
        this.requests=requests;this.statements=statements;this.deliveries=deliveries;
        this.events=events;this.auth=auth;this.workerAudience=workerAudience;
    }
    /** Creates or safely replays a requester-scoped idempotent statement request. */
    @PostMapping("/statement-requests")
    @Operation(summary="Create statement request",description="Requires fresh IAM, account and privacy decision; Idempotency-Key is mandatory.")
    public ResponseEntity<RequestView> create(@AuthenticationPrincipal UserPrincipal jwt,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CreateRequest body) {
        return ResponseEntity.status(201).body(requests.create(Actor.from(jwt),key,body));
    }
    /** Lists requests for one currently authorized account. */
    @GetMapping("/statement-requests")
    @Operation(summary="List account requests")
    public List<RequestView> requests(@AuthenticationPrincipal UserPrincipal jwt,@RequestParam long accountId,
        @RequestParam(defaultValue="25") int limit) {
        Actor actor=Actor.from(jwt);
        auth.require("VIEW","VIEW",actor,accountId,null,null,null,null,null,null,actor.id(),actor.type());
        return requests.list(accountId,actor.id(),limit);
    }
    /** Reads request status after rechecking its account scope. */
    @GetMapping("/statement-requests/{id}")
    @Operation(summary="Get request status")
    public RequestView request(@AuthenticationPrincipal UserPrincipal jwt,@PathVariable String id) {
        RequestService.Row r=requests.row(id); Actor actor=Actor.from(jwt);
        auth.require("VIEW","VIEW",actor,r.accountId(),r.cif(),null,id,r.purpose(),r.caseRef(),
            r.correlation(),r.requesterId(),r.requesterType());
        return requests.get(id);
    }
    /** Cancels a request before processing starts. */
    @PostMapping("/statement-requests/{id}/cancel")
    @Operation(summary="Cancel a pending request")
    public RequestView cancel(@AuthenticationPrincipal UserPrincipal jwt,@PathVariable String id) {
        RequestService.Row r=requests.row(id); Actor actor=Actor.from(jwt);
        auth.require("REQUEST","REQUEST",actor,r.accountId(),r.cif(),null,id,r.purpose(),r.caseRef(),
            r.correlation(),r.requesterId(),r.requesterType());
        return requests.cancel(id);
    }
    /** Worker endpoint that issues a statement from a posted GL source cut. */
    @PostMapping("/internal/statement-requests/{id}/process")
    @Operation(summary="Process authorized request",description="System JWT with worker audience required; reauthorizes immediately before issue.")
    public RequestView process(@AuthenticationPrincipal UserPrincipal jwt,@PathVariable String id) {
        return statements.process(id,worker(jwt));
    }
    @PostMapping("/statement-requests/{id}/process")
    @Operation(summary="Issue an authorized statement",description="Rechecks account access and customer hash. Staff additionally require STATEMENT_ISSUE.")
    public RequestView issue(@AuthenticationPrincipal UserPrincipal user,@PathVariable String id) {
        return statements.process(id,Actor.from(user));
    }
    @GetMapping("/statements/{id}/download")
    @Operation(summary="Download a statement",description="PDF, CSV or HTML, rendered from an immutable issued snapshot with a fresh disclosure check.")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal UserPrincipal user,@PathVariable String id,
        @RequestParam(defaultValue="PDF") String format) {
        StatementView view=statement(user,id);
        var result=StatementDocument.render(view,format);
        return ResponseEntity.ok().header("Cache-Control","no-store")
            .header("Content-Disposition","attachment; filename=statement-"+id+"."+format.toLowerCase(java.util.Locale.ROOT))
            .contentType(org.springframework.http.MediaType.parseMediaType(result.type())).body(result.bytes());
    }
    /** Lists issued statement headers for an authorized account. */
    @GetMapping("/statements")
    @Operation(summary="List issued statements")
    public List<StatementView> statements(@AuthenticationPrincipal UserPrincipal jwt,@RequestParam long accountId,
        @RequestParam(defaultValue="25") int limit) {
        Actor actor=Actor.from(jwt);
        auth.require("VIEW","VIEW",actor,accountId,null,null,null,null,null,null,actor.id(),actor.type());
        return statements.list(accountId,audience(actor),limit);
    }
    /** Returns immutable rendered lines only after a fresh disclosure decision. */
    @GetMapping("/statements/{id}")
    @Operation(summary="View issued statement")
    public StatementView statement(@AuthenticationPrincipal UserPrincipal jwt,@PathVariable String id) {
        StatementService.SnapshotInfo s=statements.info(id); Actor actor=Actor.from(jwt);
        requireAudience(actor,s);
        auth.require("VIEW","VIEW",actor,s.accountId(),s.cif(),id,s.requestId(),null,null,
            null,actor.id(),actor.type());
        return statements.get(id);
    }
    /** Requests delivery after separately authorizing the recipient/channel action. */
    @PostMapping("/statements/{id}/deliveries")
    @Operation(summary="Request a statement delivery")
    public ResponseEntity<DeliveryView> deliver(@AuthenticationPrincipal UserPrincipal jwt,@PathVariable String id,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody DeliveryRequest body) {
        StatementService.SnapshotInfo s=statements.info(id); Actor actor=Actor.from(jwt);
        requireAudience(actor,s);
        if(!s.state().equals("ISSUED")) throw new ApiException(HttpStatus.CONFLICT,"NOT_ISSUED","Statement is not available");
        RequestService.Row source=requests.row(s.requestId());
        String ref=auth.requireDelivery(actor,s.accountId(),s.cif(),id,s.requestId(),
            source.purpose(),source.caseRef(),body.correlationId(),source.requesterId(),source.requesterType(),
            body.channel(),body.recipientType(),body.recipientReferenceHash(),body.format());
        return ResponseEntity.status(201).body(deliveries.request(id,key,body,ref,actor));
    }
    /** Reads delivery evidence after reauthorizing access to its statement. */
    @GetMapping("/statements/{id}/deliveries")
    @Operation(summary="List delivery evidence")
    public List<DeliveryView> deliveries(@AuthenticationPrincipal UserPrincipal jwt,@PathVariable String id) {
        StatementService.SnapshotInfo s=statements.info(id); Actor actor=Actor.from(jwt);
        requireAudience(actor,s);
        auth.require("VIEW","VIEW",actor,s.accountId(),s.cif(),id,s.requestId(),null,null,
            null,actor.id(),actor.type());
        return deliveries.list(id);
    }
    /** Returns one delivery after its statement's fresh access check. */
    @GetMapping("/deliveries/{id}")
    @Operation(summary="Get delivery status")
    public DeliveryView delivery(@AuthenticationPrincipal UserPrincipal jwt,@PathVariable String id) {
        DeliveryView d=deliveries.get(id); StatementService.SnapshotInfo s=statements.info(d.statementId());
        Actor actor=Actor.from(jwt);
        requireAudience(actor,s);
        auth.require("VIEW","VIEW",actor,s.accountId(),s.cif(),s.id(),s.requestId(),null,null,
            null,actor.id(),actor.type());
        return d;
    }
    /** Records an authorized delivery worker's dispatch or final outcome. */
    @PostMapping("/internal/deliveries/{id}/outcome")
    @Operation(summary="Record delivery outcome")
    public DeliveryView outcome(@AuthenticationPrincipal UserPrincipal jwt,@PathVariable String id,
        @Valid @RequestBody DeliveryResult result) {
        Actor actor=worker(jwt); DeliveryView d=deliveries.get(id);
        StatementService.SnapshotInfo s=statements.info(d.statementId());
        RequestService.Row source=requests.row(s.requestId());
        DeliveryService.RecipientContext recipient=deliveries.context(id);
        auth.requireDelivery(actor,s.accountId(),s.cif(),s.id(),s.requestId(),source.purpose(),
            source.caseRef(),source.correlation(),source.requesterId(),source.requesterType(),
            recipient.channel(),recipient.type(),recipient.hash(),recipient.format());
        return deliveries.complete(id,result);
    }
    /** Deduplicates a trusted producer event by source service and event ID. */
    @PostMapping("/internal/events")
    @Operation(summary="Consume owner event")
    public Map<String,String> receive(@AuthenticationPrincipal UserPrincipal jwt,@Valid @RequestBody ConsumerEvent body) {
        worker(jwt); return Map.of("result",events.receive(body));
    }
    /** Lets a trusted publisher fetch pending Module 10 events. */
    @GetMapping("/internal/outbox")
    @Operation(summary="List pending outbox events")
    public List<OutboxEvent> outbox(@AuthenticationPrincipal UserPrincipal jwt,@RequestParam(defaultValue="25") int limit) {
        worker(jwt);return events.pending(limit);
    }
    /** Acknowledges an event only after broker publication succeeds. */
    @PostMapping("/internal/outbox/{id}/published")
    @Operation(summary="Acknowledge published event")
    public ResponseEntity<Void> published(@AuthenticationPrincipal UserPrincipal jwt,@PathVariable String id) {
        worker(jwt);events.published(id);return ResponseEntity.noContent().build();
    }
    /** Requires a machine identity with the configured audience for internal operations. */
    private Actor worker(UserPrincipal jwt) {
        Actor actor=Actor.from(jwt);
        if(!actor.type().equals("SYSTEM") || !jwt.permissions().contains("STATEMENT_INTERNAL"))
            throw new ApiException(HttpStatus.FORBIDDEN,"WORKER_ONLY","System worker token required");
        return actor;
    }
    /** Maps token identity to one policy audience; access decisions remain separate. */
    private String audience(Actor actor) {
        return actor.type().equals("STAFF")?"STAFF":actor.type().equals("REGULATOR")?"REGULATOR":"CUSTOMER";
    }
    /** Prevents cross-audience rendering of a statement made under another policy. */
    private void requireAudience(Actor actor,StatementService.SnapshotInfo info) {
        if(!info.audience().equals(audience(actor)))
            throw new ApiException(HttpStatus.FORBIDDEN,"AUDIENCE_MISMATCH","Statement audience mismatch");
    }
}


