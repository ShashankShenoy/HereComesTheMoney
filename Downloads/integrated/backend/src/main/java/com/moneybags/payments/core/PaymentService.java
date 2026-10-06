package com.moneybags.payments.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.payments.api.ApiException;
import com.moneybags.payments.api.Contracts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns idempotent instructions, the direction-aware state machine, and immutable rail evidence. */
@Service
public class PaymentService {
    private final com.moneybags.integration.BankingAccess access;
    private final PaymentRepository payments;
    private final IntegrationChecks checks;
    private final EventEmitter events;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Receives replaceable evidence checks and transactional persistence. */
    public PaymentService(PaymentRepository payments, IntegrationChecks checks, EventEmitter events,
                          JdbcTemplate jdbc, ObjectMapper mapper,com.moneybags.integration.BankingAccess access) {
        this.access=access;this.payments = payments; this.checks = checks; this.events = events; this.jdbc = jdbc; this.mapper = mapper;
    }

    /** Creates one payment or returns the original response for an identical request-key replay. */
    @Transactional
    public Contracts.Payment create(Contracts.CreatePayment request) {
        var actor=com.moneybags.integration.CurrentActor.get();
        if(!actor.userId().equals(request.originatorId()))throw new ApiException(HttpStatus.FORBIDDEN,"ORIGINATOR_MISMATCH","Originator must match the authenticated user");
        if("OUTBOUND".equals(request.direction())){
            if(request.sourceAccountId()==null)throw conflict("ACCOUNT_REQUIRED","Source account required");
            var account=access.account("PAYMENT_CREATE",request.sourceAccountId());
            if(!account.get("PRIMARY_CIF_ID").equals(request.sourceCifId()))throw conflict("CIF_MISMATCH","Source CIF must match the account");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM MBX_BENEFICIARY WHERE OWNER_USER_ID=? AND ACCOUNT_TOKEN=? AND BANK_CODE=? AND STATUS='ACTIVE'",Integer.class,actor.userId(),request.beneficiaryTokenRef(),request.beneficiaryBankCode())!=1)throw conflict("BENEFICIARY_UNVERIFIED","Use your independently verified beneficiary");
        }else if(!"SERVICE".equals(actor.userType())||!actor.permissions().contains("PAYMENT_INTERNAL"))throw new ApiException(HttpStatus.FORBIDDEN,"INBOUND_SERVICE_ONLY","Inbound rail instructions require a trusted service identity");
        if(request.requestedExecutionDate().isAfter(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))))throw conflict("FUTURE_PAYMENT","Scheduled execution is not enabled");
        byte[] hash = sha256(canonical(request));
        var existing = payments.existing(request.originatorId(), request.channelCode(), request.requestKey());
        if (!existing.isEmpty()) {
            if (!Arrays.equals(existing.get(0).hash(), hash)) throw conflict("IDEMPOTENCY_CONFLICT", "Request key was used for different details");
            return payments.get(existing.get(0).id());
        }
        validateCreate(request);
        if (Actor.type().equals("USER") && !Actor.id().equals(request.originatorId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "ORIGINATOR_MISMATCH", "Originator must match the authenticated actor");
        long id = payments.insert(request, hash);
        payments.received(id, request.direction());
        payments.idempotency(request, hash, id);
        events.emit("M06.PaymentReceived", id, request.correlationId(), Map.of("paymentId", id, "status", "RECEIVED", "direction", request.direction()));
        events.audit("CREATE_PAYMENT", id, "SUCCESS", null);
        return payments.get(id);
    }

    /** Verifies required cross-module references before committing a legal versioned state change. */
    @Transactional
    public Contracts.Payment transition(long id, Contracts.Transition command) {
        Contracts.Payment payment = payments.lock(id);
        if (payment.version() != command.expectedVersion()) throw conflict("STALE_VERSION", "Payment version changed");
        if (payment.status().equals(command.toStatus())) return payment;
        PaymentRepository.TransitionRule rule = payments.transitionRule(payment.direction(), payment.status(), command.toStatus())
                .stream().findFirst().orElseThrow(() -> conflict("ILLEGAL_TRANSITION", "Transition is not allowed for this direction"));
        if (rule.rail()) verifyRail(payment, command);
        if (command.toStatus().equals("AUTHORIZED")) authorize(payment);
        if (command.toStatus().equals("HELD")) {
            if (command.holdId() == null) throw conflict("HOLD_REQUIRED", "Module 5 hold ID is required");
            checks.hold(id, command.holdId(), payment.amount());
            if (payment.status().equals("LIQUIDITY_PENDING")) {
                if (command.liquidityHoldId() == null) throw conflict("LIQUIDITY_HOLD_REQUIRED", "Module 7 reserve hold is required");
                checks.liquidityHold(id, command.liquidityHoldId(), payment.amount(), payment.railCode());
            }
        }
        if (command.toStatus().equals("DEBIT_POSTED") || command.toStatus().equals("CREDIT_POSTED")) {
            if (command.ledgerJournalId() == null) throw conflict("JOURNAL_REQUIRED", "Module 5 posting journal is required");
            checks.journal(id, command.ledgerJournalId(), payment.amount(), "PAYMENT_PROVISIONAL");
            if (command.toStatus().equals("DEBIT_POSTED")) {
                if (payment.holdId() == null) throw conflict("HOLD_REQUIRED", "Posted debit must consume its original hold");
                checks.consumedHold(id, payment.holdId(), command.ledgerJournalId());
            }
            if (command.toStatus().equals("CREDIT_POSTED")) verifyTreasury(payment, command);
        }
        if (command.toStatus().equals("DISPATCHED")) {
            if (command.ledgerJournalId() == null) throw conflict("JOURNAL_REQUIRED", "Original debit journal is required");
            checks.journal(id, command.ledgerJournalId(), payment.amount(), "PAYMENT_PROVISIONAL");
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM M06_RAIL_DISPATCH WHERE PAYMENT_ID=? AND DISPATCH_STATUS IN ('SENT','ACKNOWLEDGED')", Integer.class, id);
            if (count == null || count != 1) throw conflict("DISPATCH_NOT_SENT", "Rail adapter has not confirmed sending");
        }
        if (command.toStatus().equals("SETTLED")) verifyTreasury(payment, command);
        if (command.toStatus().equals("REFUNDED")) {
            if (command.ledgerJournalId() == null) throw conflict("REFUND_JOURNAL_REQUIRED", "Module 5 refund journal is required");
            checks.journal(id, command.ledgerJournalId(), payment.amount(), "PAYMENT_REFUND");
        }
        if (rule.ledger() && command.ledgerJournalId() == null && command.treasuryEntryId() == null)
            throw conflict("LEDGER_EVIDENCE_REQUIRED", "Ledger or treasury evidence is required");
        payments.advance(payment, command);
        events.emit("M06.PaymentStatusChanged", id, null, Map.of("paymentId", id, "fromStatus", payment.status(), "toStatus", command.toStatus(), "version", payment.version() + 1));
        events.audit("TRANSITION_PAYMENT", id, "SUCCESS", command.reasonCode());
        return payments.get(id);
    }

    /** Persists a callback or inquiry once; identical rail hashes return their original evidence ID. */
    @Transactional
    public long evidence(long paymentId, Contracts.RailEvidence request) {
        Contracts.Payment payment = payments.lock(paymentId);
        byte[] hash;
        try { hash = HexFormat.of().parseHex(request.evidenceHashHex()); }
        catch (IllegalArgumentException error) { throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_EVIDENCE_HASH", "Evidence hash must be 64 hexadecimal characters"); }
        if (hash.length != 32) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_EVIDENCE_HASH", "Evidence hash must be SHA-256");
        List<Long> prior = payments.evidenceByHash(payment.railCode(), hash);
        if (!prior.isEmpty()) {
            if (payments.railStatus(paymentId, prior.get(0)).isEmpty()) throw conflict("EVIDENCE_HASH_CONFLICT", "Evidence hash belongs to another payment");
            return prior.get(0);
        }
        if (payment.railMessageId() != null && !payment.railMessageId().equals(request.railMessageId()))
            throw conflict("RAIL_MESSAGE_MISMATCH", "Evidence message ID differs from the payment dispatch");
        long id = payments.evidence(paymentId, payment.railCode(), request, hash);
        if (payment.railMessageId() == null)
            jdbc.update("UPDATE M06_PAYMENT_INSTRUCTION SET RAIL_MESSAGE_ID=?,RAIL_REFERENCE=COALESCE(RAIL_REFERENCE,?) WHERE PAYMENT_ID=?",
                    request.railMessageId(), request.externalReference(), paymentId);
        events.emit("M06.RailEvidenceRecorded", paymentId, null, Map.of("paymentId", paymentId, "evidenceId", id, "railStatus", request.railStatus()));
        events.audit("RECORD_RAIL_EVIDENCE", paymentId, "SUCCESS", request.reasonCode());
        return id;
    }

    /** Reads one payment. */
    public Contracts.Payment get(long id) { return payments.get(id); }

    /** Lists at most 100 recent payments, optionally filtering by state. */
    public List<Contracts.Payment> list(String status) { return payments.list(status); }

    /** Reads the immutable transition audit trail. */
    public List<Map<String, Object>> history(long id) { payments.get(id); return payments.history(id); }

    /** Reads rail evidence metadata without returning raw payloads. */
    public List<Map<String, Object>> evidenceList(long id) { payments.get(id); return payments.evidenceList(id); }

    /** Verifies the selected evidence has a status compatible with the destination state. */
    private void verifyRail(Contracts.Payment payment, Contracts.Transition command) {
        if (command.evidenceId() == null) throw conflict("RAIL_EVIDENCE_REQUIRED", "Rail evidence is required");
        String observed = payments.railStatus(payment.paymentId(), command.evidenceId()).stream().findFirst()
                .orElseThrow(() -> conflict("RAIL_EVIDENCE_MISMATCH", "Evidence is not scoped to this payment"));
        String target = command.toStatus();
        boolean compatible = switch (target) {
            case "ACCEPTED" -> observed.equals("ACCEPTED");
            case "REJECTED" -> observed.equals("REJECTED");
            case "UNKNOWN" -> observed.equals("UNKNOWN");
            case "SETTLEMENT_PENDING" -> observed.equals("PENDING") || observed.equals("ACCEPTED");
            case "SETTLED", "CREDIT_POSTED" -> observed.equals("SETTLED");
            case "REFUNDED" -> observed.equals("RETURNED");
            default -> true;
        };
        if (!compatible) throw conflict("RAIL_STATUS_MISMATCH", "Rail observation cannot support requested state");
    }

    /** Verifies a verified reserve movement before finality or inbound customer credit. */
    private void verifyTreasury(Contracts.Payment payment, Contracts.Transition command) {
        if (command.treasuryEntryId() == null) throw conflict("TREASURY_EVIDENCE_REQUIRED", "Verified Module 7 treasury entry is required");
        checks.treasury(payment.paymentId(), command.treasuryEntryId(), payment.amount());
    }

    /** Retrieves the authoritative party IDs for the selected side before authorization. */
    private void authorize(Contracts.Payment payment) {
        var row = jdbc.queryForMap("SELECT CHANNEL_CODE,SOURCE_ACCOUNT_ID,SOURCE_CIF_ID,DESTINATION_ACCOUNT_ID,DESTINATION_CIF_ID FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?", payment.paymentId());
        boolean outgoing = payment.direction().equals("OUTBOUND");
        Long account = row.get(outgoing ? "SOURCE_ACCOUNT_ID" : "DESTINATION_ACCOUNT_ID") == null ? null
                : ((Number) row.get(outgoing ? "SOURCE_ACCOUNT_ID" : "DESTINATION_ACCOUNT_ID")).longValue();
        String cif = (String) row.get(outgoing ? "SOURCE_CIF_ID" : "DESTINATION_CIF_ID");
        checks.authorize(payment, (String) row.get("CHANNEL_CODE"), account, cif);
    }

    /** Rejects malformed parties and return links before Oracle constraint errors occur. */
    private void validateCreate(Contracts.CreatePayment request) {
        if (request.direction() == null || request.kind() == null || request.railCode() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Direction, kind and rail are required");
        if (request.kind().equals("RETURN") != (request.originalPaymentId() != null))
            throw new ApiException(HttpStatus.BAD_REQUEST, "RETURN_LINK_REQUIRED", "Returns require exactly one original payment");
        if (request.originalPaymentId() != null) {
            Contracts.Payment original = payments.lock(request.originalPaymentId());
            java.math.BigDecimal priorReturns = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(AMOUNT),0) FROM M06_PAYMENT_INSTRUCTION WHERE ORIGINAL_PAYMENT_ID=? AND STATUS NOT IN ('REJECTED','CANCELLED')",
                    java.math.BigDecimal.class, request.originalPaymentId());
            if (!original.kind().equals("PAYMENT") || !original.status().equals("SETTLED")
                    || !original.railCode().equals(request.railCode())
                    || original.direction().equals(request.direction())
                    || priorReturns.add(request.amount()).compareTo(original.amount()) > 0)
                throw conflict("RETURN_ORIGINAL_MISMATCH", "Return must reverse a settled payment on the same rail within the original amount");
        }
        if (request.direction().equals("OUTBOUND") && (request.sourceAccountId() == null || request.sourceCifId() == null
                || request.beneficiaryTokenRef() == null || request.beneficiaryBankCode() == null))
            throw new ApiException(HttpStatus.BAD_REQUEST, "OUTBOUND_PARTIES_REQUIRED", "Source account, CIF and beneficiary token and bank are required");
        if (request.direction().equals("INBOUND") && (request.destinationAccountId() == null || request.destinationCifId() == null))
            throw new ApiException(HttpStatus.BAD_REQUEST, "INBOUND_PARTIES_REQUIRED", "Destination account and CIF are required");
    }

    /** Serializes all economic and routing fields for a deterministic request hash. */
    private String canonical(Contracts.CreatePayment request) {
        try { return mapper.writeValueAsString(request); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot hash payment request", error); }
    }

    /** Calculates SHA-256 for idempotency and external evidence matching. */
    private byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    /** Produces a stable conflict response. */
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}


