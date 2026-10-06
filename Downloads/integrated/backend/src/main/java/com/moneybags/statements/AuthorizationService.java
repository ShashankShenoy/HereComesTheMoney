package com.moneybags.statements;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Enforces fresh policy decisions and persists allowed or denied evidence. */
@Service
public class AuthorizationService {
    private final DecisionClient decisions;
    private final AuditService audits;
    public AuthorizationService(DecisionClient decisions, AuditService audits) {
        this.decisions = decisions; this.audits = audits;
    }
    /** Authorizes an action against current owner facts; a denial is audited and fails closed. */
    public String require(String action, String event, Actor actor, Long accountId, String cif,
        String statementId, String requestId, String purpose, String caseRef, String correlationId,
        String requesterId, String requesterType) {
        return evaluate(new DecisionClient.DecisionQuery(actor.id(), actor.type(), actor.sessionId(),
            action, accountId, cif, statementId, purpose, caseRef, requesterId, requesterType),
            event, actor, accountId, statementId, requestId, purpose, caseRef, correlationId);
    }
    /** Includes recipient and render facts in a fresh delivery decision. */
    public String requireDelivery(Actor actor, Long accountId, String cif, String statementId,
        String requestId, String purpose, String caseRef, String correlationId, String requesterId,
        String requesterType, String channel, String recipientType, String recipientHash, String format) {
        return evaluate(new DecisionClient.DecisionQuery(actor.id(),actor.type(),actor.sessionId(),
            "DELIVERY",accountId,cif,statementId,purpose,caseRef,requesterId,requesterType,
            channel,recipientType,recipientHash,format),"DELIVERY",actor,accountId,statementId,
            requestId,purpose,caseRef,correlationId);
    }
    /** Audits the exact owner decision, including denial and service outages. */
    private String evaluate(DecisionClient.DecisionQuery query,String event,Actor actor,Long accountId,
        String statementId,String requestId,String purpose,String caseRef,String correlationId) {
        DecisionClient.Decision d;
        try {
            d = decisions.decide(query);
        } catch (ApiException e) {
            audits.record(event, actor, accountId, statementId, requestId, purpose, caseRef,
                null, "FAILED", null, correlationId);
            throw e;
        }
        audits.record(event, actor, accountId, statementId, requestId, purpose, caseRef,
            d.authorizationRef(), d.allowed() ? "ALLOWED" : "DENIED",
            d.allowed() ? null : (d.reasonCode() == null ? "POLICY_DENIED" : d.reasonCode()), correlationId);
        if (!d.allowed()) throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Statement access denied");
        return d.authorizationRef();
    }
}
