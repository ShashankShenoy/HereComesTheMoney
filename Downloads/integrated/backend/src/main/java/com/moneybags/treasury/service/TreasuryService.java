package com.moneybags.treasury.service;

import static com.moneybags.treasury.domain.TreasuryModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.treasury.api.TreasuryRequests.*;
import com.moneybags.treasury.domain.DomainException;
import com.moneybags.treasury.repository.TreasuryRepository;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

/** Implements Module 7 use cases and transaction boundaries. */
@Service
public class TreasuryService {
    private final TreasuryRepository repository;
    private final ObjectMapper objectMapper;

    public TreasuryService(TreasuryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Creates reserve master data together with a zero position and an outbox event. */
    @Transactional
    public ReserveAccount createReserveAccount(CreateReserveAccount command, String actor) {
        ReserveAccount account = repository.createReserveAccount(command);
        event("treasury.reserve-account.created.v1", "RESERVE_ACCOUNT", account.id(), account);
        audit(actor, "RESERVE_ACCOUNT_CREATE", "RESERVE_ACCOUNT", account.id(), "SUCCESS", null);
        return account;
    }

    /** Lists configured reserve accounts. */
    @Transactional(readOnly = true)
    public List<ReserveAccount> reserveAccounts() { return repository.findReserveAccounts(); }

    /** Reads a reserve position; callers must not use it as posting authority. */
    @Transactional(readOnly = true)
    public ReservePosition position(long accountId) {
        return repository.findPosition(accountId).orElseThrow(() -> DomainException.notFound("Reserve position", accountId));
    }

    @Transactional(readOnly = true)
    public List<ReserveLedgerLine> reserveLedger(long accountId, int limit, int offset) {
        repository.findReserveAccount(accountId).orElseThrow(() -> DomainException.notFound("Reserve account", accountId));
        return repository.findLedger(accountId, limit, offset);
    }

    @Transactional(readOnly = true)
    public ReserveReconciliation reserveReconciliation(long accountId) {
        return repository.findReserveReconciliation(accountId)
            .orElseThrow(() -> DomainException.notFound("Reserve position", accountId));
    }

    /** Atomically reserves available liquidity and makes retries idempotent by hold key plus request hash. */
    @Transactional
    public LiquidityHold reserve(CreateLiquidityHold command, String actor) {
        validateExclusiveScope(command.paymentId(), command.settlementCycleId());
        byte[] hash = hash(command);
        var existing = repository.findHoldByKey(command.holdKey());
        if (existing.isPresent()) {
            if (!MessageDigest.isEqual(existing.get().requestHash(), hash)) {
                throw DomainException.conflict("IDEMPOTENCY_MISMATCH", "Hold key was already used with a different request");
            }
            return existing.get();
        }
        ReserveAccount account = repository.findReserveAccount(command.reserveAccountId())
            .orElseThrow(() -> DomainException.notFound("Reserve account", command.reserveAccountId()));
        if (!"ACTIVE".equals(account.status())) throw DomainException.invalid("Reserve account is not active");
        if (command.expiresAt() != null && !command.expiresAt().isAfter(OffsetDateTime.now())) {
            throw DomainException.invalid("expiresAt must be in the future");
        }
        ReservePosition position = repository.lockPosition(command.reserveAccountId());
        var spendable = position.availableBalance().subtract(account.safetyBuffer());
        if (spendable.compareTo(command.amount()) < 0) {
            throw DomainException.conflict("INSUFFICIENT_RESERVE_LIQUIDITY", "Amount exceeds available balance after safety buffer");
        }
        LiquidityHold hold = repository.insertHold(command, hash, "LHR-" + UUID.randomUUID());
        event("treasury.liquidity.reserved.v1", "LIQUIDITY_HOLD", hold.id(), hold);
        audit(actor, "LIQUIDITY_RESERVE", "LIQUIDITY_HOLD", hold.id(), "SUCCESS", null);
        return hold;
    }

    /** Commits a hold before rail dispatch or releases it after cancellation/rejection. */
    @Transactional
    public LiquidityHold decideHold(long id, HoldDecision command, String actor) {
        LiquidityHold hold = repository.lockHold(id);
        if (hold.status() != HoldStatus.RESERVED) throw DomainException.conflict("INVALID_HOLD_STATE", "Only RESERVED holds can transition");
        if ("RELEASE".equals(command.decision()) && (command.reason() == null || command.reason().isBlank())) {
            throw DomainException.invalid("A release reason is required");
        }
        repository.lockPosition(hold.reserveAccountId());
        repository.transitionHold(hold, command);
        LiquidityHold updated = repository.lockHold(id);
        event("treasury.liquidity." + updated.status().name().toLowerCase() + ".v1", "LIQUIDITY_HOLD", id, updated);
        audit(actor, "LIQUIDITY_" + command.decision(), "LIQUIDITY_HOLD", id, "SUCCESS", command.reason());
        return updated;
    }

    /** Lists liquidity holds for Module 6 support and operations. */
    @Transactional(readOnly = true)
    public List<LiquidityHold> holds(Long accountId, String status) { return repository.findHolds(accountId, status); }

    /** Expires uncommitted reservations in bounded, skip-locked batches across service instances. */
    @Scheduled(fixedDelayString = "${moneybags.liquidity-expiry-interval-ms:30000}")
    @Transactional
    public void expireReservations() {
        for (LiquidityHold hold : repository.lockExpiredHolds()) {
            repository.lockPosition(hold.reserveAccountId());
            repository.expireHold(hold);
            event("treasury.liquidity.expired.v1", "LIQUIDITY_HOLD", hold.id(),
                java.util.Map.of("holdId", hold.id(), "reason", "EXPIRY_REACHED"));
            audit("system", "LIQUIDITY_EXPIRE", "LIQUIDITY_HOLD", hold.id(), "SUCCESS", "EXPIRY_REACHED");
        }
    }

    /** Opens a cycle with a stable Module 6 clearing batch reference. */
    @Transactional
    public SettlementCycle createCycle(CreateCycle command, String actor) {
        repository.findReserveAccount(command.reserveAccountId())
            .orElseThrow(() -> DomainException.notFound("Reserve account", command.reserveAccountId()));
        SettlementCycle cycle = repository.createCycle(command);
        event("treasury.settlement-cycle.opened.v1", "SETTLEMENT_CYCLE", cycle.id(), cycle);
        audit(actor, "CYCLE_CREATE", "SETTLEMENT_CYCLE", cycle.id(), "SUCCESS", null);
        return cycle;
    }

    /** Adds payment snapshots and recalculates cycle totals while the cycle row is locked. */
    @Transactional
    public SettlementCycle addCycleItems(long cycleId, AddCycleItems command, String actor) {
        SettlementCycle cycle = repository.lockCycle(cycleId);
        if (cycle.status() != CycleStatus.OPEN) throw DomainException.conflict("CYCLE_NOT_OPEN", "Items can only be added to OPEN cycles");
        command.items().forEach(item -> repository.addCycleItem(cycleId, item));
        repository.recalculateCycle(cycleId);
        audit(actor, "CYCLE_ITEMS_ADD", "SETTLEMENT_CYCLE", cycleId, "SUCCESS", null);
        return repository.findCycle(cycleId).orElseThrow();
    }

    /** Closes an open cycle so its totals can no longer change. */
    @Transactional
    public SettlementCycle closeCycle(long cycleId, String actor) {
        SettlementCycle cycle = repository.lockCycle(cycleId);
        if (cycle.status() != CycleStatus.OPEN) throw DomainException.conflict("CYCLE_NOT_OPEN", "Only an OPEN cycle can close");
        repository.recalculateCycle(cycleId);
        repository.closeCycle(cycleId);
        SettlementCycle closed = repository.findCycle(cycleId).orElseThrow();
        event("treasury.settlement-cycle.closed.v1", "SETTLEMENT_CYCLE", cycleId, closed);
        audit(actor, "CYCLE_CLOSE", "SETTLEMENT_CYCLE", cycleId, "SUCCESS", null);
        return closed;
    }

    /** Lists settlement cycles for the Oracle JET workbench. */
    @Transactional(readOnly = true)
    public List<SettlementCycle> cycles(String status, String rail) { return repository.findCycles(status, rail); }

    /** Returns payment snapshots in one settlement cycle. */
    @Transactional(readOnly = true)
    public List<SettlementCycleItem> cycleItems(long cycleId) { return repository.findCycleItems(cycleId); }

    /** Persists append-only, content-hashed external settlement evidence. */
    @Transactional
    public SettlementEvidence recordEvidence(SettlementEvidenceCommand command, String actor) {
        validateExclusiveScope(command.paymentId(), command.settlementCycleId());
        SettlementEvidence evidence = repository.insertEvidence(command,
            sha256(command.evidencePayload().getBytes(StandardCharsets.UTF_8)));
        event("treasury.settlement-evidence.recorded.v1", "SETTLEMENT_EVIDENCE", evidence.id(), evidence);
        audit(actor, "EVIDENCE_RECORD", "SETTLEMENT_EVIDENCE", evidence.id(), "SUCCESS", null);
        return evidence;
    }

    /** Mirrors a Module 5 GL journal only after verified evidence exists; retry is safe by unique evidence/journal keys. */
    @Transactional
    public TreasuryEntry confirmMovement(ConfirmMovement command, String actor) {
        SettlementEvidence evidence = repository.findEvidence(command.evidenceId())
            .orElseThrow(() -> DomainException.notFound("Settlement evidence", command.evidenceId()));
        if (!"VERIFIED".equals(evidence.status())) throw DomainException.invalid("Only VERIFIED evidence can create a reserve entry");
        repository.lockPosition(evidence.reserveAccountId());
        var existing = repository.findEntryByEvidence(evidence.id());
        if (existing.isPresent()) {
            if (existing.get().glJournalId() != command.glJournalId()) {
                throw DomainException.conflict("EVIDENCE_ALREADY_USED", "Evidence is already linked to a different GL journal");
            }
            return existing.get();
        }
        TreasuryEntry entry = repository.insertTreasuryEntry(evidence, command.glJournalId());
        repository.applyEntryToPosition(entry);
        repository.consumeMatchingHold(entry);
        if (entry.cycleId() != null) repository.settleCycle(entry.cycleId());
        event("treasury.reserve-movement.confirmed.v1", "TREASURY_ENTRY", entry.id(), entry);
        audit(actor, "MOVEMENT_CONFIRM", "TREASURY_ENTRY", entry.id(), "SUCCESS", null);
        return entry;
    }

    public record CashDeliveryPosting(long evidenceId, long treasuryEntryId) {}

    /** Locks and checks the simulated RBI position before any cash-delivery journal is posted. */
    @Transactional
    public ReserveAccount requireCashDeliveryCapacity(long reserveAccountId, BigDecimal amount) {
        ReserveAccount account = repository.findReserveAccount(reserveAccountId)
            .orElseThrow(() -> DomainException.notFound("Reserve account", reserveAccountId));
        if (!"ACTIVE".equals(account.status()) || !"RBI_CURRENT".equals(account.accountType()))
            throw DomainException.invalid("Cash delivery requires an active RBI current reserve account");
        ReservePosition position = repository.lockPosition(reserveAccountId);
        ReserveReconciliation control = repository.findReserveReconciliation(reserveAccountId)
            .orElseThrow(() -> DomainException.notFound("Reserve reconciliation", reserveAccountId));
        if (!"Y".equals(control.isMatched()))
            throw DomainException.conflict("RESERVE_RECONCILIATION", "Reserve ledger and position do not agree");
        if (position.availableBalance().subtract(account.safetyBuffer()).compareTo(amount) < 0)
            throw DomainException.conflict("INSUFFICIENT_RESERVE_LIQUIDITY", "Cash shipment exceeds available RBI reserve after the safety buffer");
        return account;
    }

    /** Commits verified cash receipt to the local reserve mirror in the caller's journal transaction. */
    @Transactional
    public CashDeliveryPosting confirmCashDelivery(String deliveryId, long reserveAccountId, BigDecimal amount,
                                                    String shipmentRef, String receiptRef, long journalId,
                                                    OffsetDateTime occurredAt, String checker) {
        requireCashDeliveryCapacity(reserveAccountId, amount);
        long evidenceId = repository.insertCashDeliveryEvidence(deliveryId, reserveAccountId, amount,
            shipmentRef, receiptRef, occurredAt,
            sha256((deliveryId + "|" + shipmentRef + "|" + receiptRef + "|" + amount.toPlainString()).getBytes(StandardCharsets.UTF_8)));
        TreasuryEntry entry = repository.insertCashDeliveryEntry(deliveryId, reserveAccountId, evidenceId,
            amount, shipmentRef, journalId, occurredAt);
        repository.applyEntryToPosition(entry);
        event("treasury.cash-delivery.confirmed.v1", "TREASURY_ENTRY", entry.id(),
            java.util.Map.of("deliveryId", deliveryId, "reserveAccountId", reserveAccountId,
                "amount", amount, "journalId", journalId));
        audit(checker, "CASH_DELIVERY_CONFIRMED", "TREASURY_ENTRY", entry.id(), "SUCCESS", null);
        return new CashDeliveryPosting(evidenceId, entry.id());
    }

    /** Adds one approved synthetic capital opening to the local RBI mirror. */
    @Transactional
    public CashDeliveryPosting confirmReserveOpening(String openingId, long reserveAccountId, BigDecimal amount,
                                                     String evidenceRef, long journalId,
                                                     OffsetDateTime occurredAt, String checker) {
        repository.lockPosition(reserveAccountId);
        long evidenceId = repository.insertReserveOpeningEvidence(openingId, reserveAccountId, amount,
            evidenceRef, occurredAt,
            sha256((openingId + "|" + evidenceRef + "|" + amount.toPlainString()).getBytes(StandardCharsets.UTF_8)));
        TreasuryEntry entry = repository.insertReserveOpeningEntry(openingId, reserveAccountId, evidenceId,
            amount, evidenceRef, journalId, occurredAt);
        repository.applyEntryToPosition(entry);
        event("treasury.reserve-opening.confirmed.v1", "TREASURY_ENTRY", entry.id(),
            java.util.Map.of("openingId", openingId, "reserveAccountId", reserveAccountId,
                "amount", amount, "journalId", journalId));
        audit(checker, "RESERVE_OPENING_CONFIRMED", "TREASURY_ENTRY", entry.id(), "SUCCESS", null);
        return new CashDeliveryPosting(evidenceId, entry.id());
    }

    /** Opens an exception from automated reconciliation or an operator. */
    @Transactional
    public ReconciliationException openException(OpenException command, String actor) {
        if (command.paymentId() == null && command.settlementCycleId() == null && command.treasuryEntryId() == null) {
            throw DomainException.invalid("At least one exception scope identifier is required");
        }
        ReconciliationException exception = repository.insertException(command);
        event("treasury.reconciliation-exception.opened.v1", "RECON_EXCEPTION", exception.id(), exception);
        audit(actor, "RECON_OPEN", "RECON_EXCEPTION", exception.id(), "SUCCESS", null);
        return exception;
    }

    /** Lists the reconciliation queue. */
    @Transactional(readOnly = true)
    public List<ReconciliationException> exceptions(String status, String severity) {
        return repository.findExceptions(status, severity);
    }

    /** Completes an exception with explicit resolution evidence. */
    @Transactional
    public void resolveException(long id, ResolveException command, String actor) {
        repository.resolveException(id, command);
        event("treasury.reconciliation-exception.closed.v1", "RECON_EXCEPTION", id, command);
        audit(actor, "RECON_RESOLVE", "RECON_EXCEPTION", id, "SUCCESS", command.resolutionCode());
    }

    /** Creates a funding or reconciliation work item with strict subject scope. */
    @Transactional
    public WorkItem createWork(CreateWorkItem command, String actor) {
        validateSubject(command.subjectType(), command.paymentId(), command.settlementCycleId());
        WorkItem item = repository.insertWorkItem(command);
        audit(actor, "WORK_CREATE", "WORK_ITEM", item.id(), "SUCCESS", null);
        return item;
    }

    /** Lists operations work. */
    @Transactional(readOnly = true)
    public List<WorkItem> workItems(String status, String owner) { return repository.findWorkItems(status, owner); }

    /** Submits a maker's completed work for independent approval. */
    @Transactional
    public void submitWork(long id, String maker, int version) {
        repository.submitWork(id, maker, version);
        event("treasury.work-item.submitted.v1", "WORK_ITEM", id, java.util.Map.of("maker", maker));
    }

    /** Records a checker decision; repository SQL prevents self-approval. */
    @Transactional
    public void decideWork(long id, WorkDecision command) {
        repository.decideWork(id, command);
        event("treasury.work-item.decided.v1", "WORK_ITEM", id, command);
    }

    private void validateExclusiveScope(Long paymentId, Long cycleId) {
        if ((paymentId == null) == (cycleId == null)) throw DomainException.invalid("Exactly one of paymentId or settlementCycleId is required");
    }

    private void validateSubject(String subject, Long paymentId, Long cycleId) {
        boolean valid = switch (subject) {
            case "PAYMENT" -> paymentId != null && cycleId == null;
            case "CYCLE" -> paymentId == null && cycleId != null;
            case "RESERVE_ACCOUNT" -> paymentId == null && cycleId == null;
            default -> false;
        };
        if (!valid) throw DomainException.invalid("Subject identifiers do not match subjectType");
    }

    private byte[] hash(Object value) {
        try { return sha256(objectMapper.writeValueAsBytes(value)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot hash command", e); }
    }

    private byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void event(String type, String aggregate, long id, Object payload) {
        try {
            repository.insertOutbox(UUID.randomUUID(), type, aggregate, Long.toString(id), MDC.get("correlationId"),
                objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize outbox payload", e); }
    }

    private void audit(String actor, String action, String type, long id, String result, String reason) {
        repository.insertAudit(actor == null ? "system" : actor, MDC.get("correlationId"), action, type,
            Long.toString(id), result, reason);
    }
}
