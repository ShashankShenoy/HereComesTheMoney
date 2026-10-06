package com.moneybags.account.domain;

import com.moneybags.account.api.ApiException;
import com.moneybags.account.api.Models.*;
import com.moneybags.account.db.*;
import com.moneybags.account.security.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Applies only authenticated Module 5 events, with per-account ordering and deduplication. */
@Service
public class InboundService {
    private final AccountStore accounts;
    private final WorkflowStore workflows;
    private final EventStore events;
    private final Actor actor;
    private final TransactionTemplate tx;
    public InboundService(AccountStore accounts, WorkflowStore workflows, EventStore events,
                          Actor actor, TransactionTemplate tx) {
        this.accounts = accounts; this.workflows = workflows; this.events = events;
        this.actor = actor; this.tx = tx;
    }

    /** Applies an account-control ack after checking its source UUID and version. */
    public void controlAck(ControlAck ack) {
        actor.require("ACCOUNT_EVENTS"); localControlAck(ack); }
    public void localControlAck(ControlAck ack) {
        if (!Set.of("ACKNOWLEDGED", "FAILED").contains(ack.outcome())) throw bad("Unknown control outcome");
        if ("FAILED".equals(ack.outcome()) && (ack.failureCode() == null || ack.failureCode().isBlank()))
            throw bad("Failure code required");
        tx.executeWithoutResult(status -> {
            if (events.processed(ack.eventId())) return;
            AccountView account = accounts.account(ack.accountId(), true);
            Map<String, Object> control = workflows.controlForAck(ack.accountId(), ack.controlVersion())
                    .orElseThrow(() -> conflict("Unknown control version"));
            if (!HexFormat.of().formatHex(WorkflowStore.rawUuid(ack.sourceEventId())).equalsIgnoreCase(
                    String.valueOf(control.get("SOURCE_EVENT_HEX"))))
                throw conflict("Ack references a different control event");
            if (!"PENDING".equals(control.get("SYNC_STATUS"))) throw conflict("Control already finalized");
            boolean success = "ACKNOWLEDGED".equals(ack.outcome());
            if (success && (ack.fenceVersion() == null || ack.fenceVersion() < ack.controlVersion()))
                throw conflict("Module 5 fence version is stale");
            workflows.finishControl(ack.accountId(), ack.controlVersion(), ack);
            Number restrictionId = (Number) control.get("RESTRICTION_ID");
            if (restrictionId != null) {
                Map<String, Object> restriction = workflows.restriction(restrictionId.longValue());
                if (((Number) restriction.get("ACCOUNT_ID")).longValue() != ack.accountId())
                    throw conflict("Restriction account mismatch");
                boolean applying = "APPLY".equals(control.get("TARGET_ACTION"));
                workflows.finishRestriction(restrictionId.longValue(), applying, success, actor.id(), ack.failureCode());
                if ("ACTIVE".equals(account.lifecycleStatus())) {
                    String next = workflows.activeDisplayStatus(ack.accountId());
                    accounts.displayStatus(ack.accountId(), next, actor.id());
                    accounts.statusHistory(ack.accountId(), "RESTRICTION", account.accountStatus(), next,
                            success ? "CONTROL_ACKNOWLEDGED" : "CONTROL_FAILED", null, actor.id(), ack.eventId());
                }
                events.outbox(ack.accountId(), success
                                ? (applying ? "ACCOUNT_RESTRICTION_APPLIED" : "ACCOUNT_RESTRICTION_REMOVED")
                                : "ACCOUNT_RESTRICTION_FAILED",
                        ack.eventId(), Map.of("accountId", ack.accountId(),
                                "restrictionId", restrictionId.longValue(),
                                "controlVersion", ack.controlVersion()));
            } else if (success && "LIFECYCLE".equals(control.get("CONTROL_TYPE"))
                    && "PENDING_OPEN".equals(account.lifecycleStatus())) {
                accounts.lifecycle(ack.accountId(), "ACTIVE", "ACTIVE", actor.id());
                accounts.statusHistory(ack.accountId(), "LIFECYCLE", "PENDING_OPEN", "ACTIVE",
                        "MODULE5_FENCE_OPEN", null, actor.id(), ack.eventId());
                events.outbox(ack.accountId(), "ACCOUNT_ACTIVATED", ack.eventId(),
                        Map.of("accountId", ack.accountId(), "controlVersion", ack.controlVersion()));
            }
            events.inbox(ack.eventId(), "ACCOUNT_CONTROL_ACK", "MB_TXN", ack.accountId());
            events.audit(ack.accountId(), "CONTROL_ACK", actor.id(), success ? "SUCCESS" : "FAILED",
                    ack.eventId(), Map.of("controlVersion", ack.controlVersion(), "outcome", ack.outcome()));
        });
    }

    /** Advances a non-authoritative balance projection by exactly one Module 5 version. */
    public void projection(ProjectionEvent event) {
        actor.require("ACCOUNT_EVENTS");
        if (!Set.of("LEDGER_POSTED", "HOLD_PLACED", "HOLD_CONSUMED", "HOLD_RELEASED",
                "LIEN_APPLIED", "LIEN_RELEASED", "PARTIAL_BLOCK_APPLIED", "PARTIAL_BLOCK_RELEASED",
                "OVERDRAFT_CHANGED", "RECONCILIATION_CORRECTION").contains(event.eventType()))
            throw bad("Unknown projection event type");
        tx.executeWithoutResult(status -> {
            if (events.processed(event.eventId())) return;
            AccountView a = accounts.account(event.accountId(), true);
            if ("CLOSED".equals(a.lifecycleStatus()) || "CANCELLED".equals(a.lifecycleStatus()))
                throw conflict("Terminal account cannot receive new financial projection events");
            ProjectionPolicy.validate(a, event);
            events.projection(event);
            events.inbox(event.eventId(), event.eventType(), "MB_TXN", event.accountId());
        });
    }
    /** Builds a consistent client error. */
    private ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVENT", message); }
    /** Builds a consistent event-conflict error. */
    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "EVENT_CONFLICT", message); }
}

