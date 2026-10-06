package com.moneybags.privacy.consent;

import com.moneybags.privacy.common.ApiException;
import com.moneybags.privacy.audit.ConsentAuditWriter;
import com.moneybags.privacy.common.RequestContext;
import com.moneybags.privacy.outbox.OutboxService;
import com.moneybags.privacy.purpose.PurposeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Captures explicit decisions and evaluates optional-processing eligibility. */
@Service
public class ConsentService {
    private final ConsentRepository consents;
    private final PurposeRepository purposes;
    private final SubjectVerificationPort verification;
    private final OutboxService outbox;
    private final ConsentAuditWriter audit;

    public ConsentService(ConsentRepository consents, PurposeRepository purposes,
                          SubjectVerificationPort verification, OutboxService outbox,
                          ConsentAuditWriter audit) {
        this.consents = consents;
        this.purposes = purposes;
        this.verification = verification;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** Captures a grant or denial after checking the current notice and actor authority. */
    @Transactional
    public ConsentDecision decide(DecideConsent command, RequestContext context) {
        if (command.decision() != ConsentDecision.Decision.GRANTED
                && command.decision() != ConsentDecision.Decision.DENIED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_INTERACTIVE_DECISION",
                    "Only grants and denials use the decision endpoint.");
        }
        requireIdempotency(context);
        var sourceConsentId = command.sourceConsentId() == null || command.sourceConsentId().isBlank()
                ? null : command.sourceConsentId();
        var existing = consents.findByIdempotencyKey(context.idempotencyKey());
        if (existing.isPresent()) {
            var prior = existing.get();
            if (!prior.subjectService().equals(command.subjectService())
                    || !prior.subjectExternalId().equals(command.subjectExternalId())
                    || !prior.purposeCode().equals(command.purposeCode())
                    || !Objects.equals(prior.sourceConsentId(), sourceConsentId)
                    || prior.decision() != command.decision()) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key belongs to a different consent request.");
            }
            return prior;
        }
        var now = OffsetDateTime.now();
        var effectiveAt = command.effectiveAt() == null ? now : command.effectiveAt();
        if (effectiveAt.isBefore(now.minusSeconds(5))
                || (command.expiresAt() != null && !command.expiresAt().isAfter(effectiveAt))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONSENT_TIME_INVALID",
                    "Decision cannot be backdated and expiry must follow its effective time.");
        }
        var purpose = purposes.findCurrentActive(command.purposeCode(), now)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PURPOSE_NOT_ACTIVE",
                        "No active purpose version is available for this decision."));
        if (purpose.noticeSha256() == null || !purpose.noticeSha256().equalsIgnoreCase(command.noticeSha256())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOTICE_VERSION_MISMATCH",
                    "The presented notice is not the current approved notice.");
        }
        if (purpose.effectiveTo() != null && !effectiveAt.isBefore(purpose.effectiveTo())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONSENT_AFTER_PURPOSE_END",
                    "Decision effective time falls outside the approved purpose period.");
        }
        var verified = verification.verify(command.subjectService(), command.subjectExternalId(),
                context.actorId(), command.channelCode(), context.correlationId());
        if (!verified.verified()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUBJECT_AUTHORITY_NOT_VERIFIED",
                    "Actor authority for the subject could not be verified.");
        }
        var decision = new ConsentDecision(UUID.randomUUID().toString(), purpose.id(), purpose.code(),
                command.subjectService(), command.subjectExternalId(), sourceConsentId,
                consents.nextDecisionNo(command.subjectService(), command.subjectExternalId(), purpose.id()),
                command.decision(), command.channelCode(), now,
                effectiveAt, command.expiresAt(),
                context.actorId(), verified.authenticationStrength(), purpose.noticeSha256(),
                command.evidenceUri(), command.evidenceSha256(), context.correlationId());
        consents.insert(decision, context.idempotencyKey(), context.actorId());
        audit.write(decision, context);
        outbox.emit("CONSENT", decision.id(), "Consent" + title(decision.decision().name()), event(decision), context);
        return decision;
    }

    /** Records withdrawal against the latest recorded purpose version, even after rotation. */
    @Transactional
    public ConsentDecision withdraw(WithdrawConsent command, RequestContext context) {
        requireIdempotency(context);
        var existing = consents.findByIdempotencyKey(context.idempotencyKey());
        if (existing.isPresent()) {
            var prior = existing.get();
            if (prior.decision() != ConsentDecision.Decision.WITHDRAWN
                    || !prior.subjectService().equals(command.subjectService())
                    || !prior.subjectExternalId().equals(command.subjectExternalId())
                    || !prior.purposeCode().equals(command.purposeCode())) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key belongs to a different consent request.");
            }
            return prior;
        }
        var previous = consents.history(command.subjectService(), command.subjectExternalId(), command.purposeCode())
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "CONSENT_HISTORY_NOT_FOUND", "No consent decision exists for this subject and purpose."));
        var verified = verification.verify(command.subjectService(), command.subjectExternalId(),
                context.actorId(), command.channelCode(), context.correlationId());
        if (!verified.verified()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUBJECT_AUTHORITY_NOT_VERIFIED",
                    "Actor authority for the subject could not be verified.");
        }
        var now = OffsetDateTime.now();
        var decision = new ConsentDecision(UUID.randomUUID().toString(), previous.purposeId(),
                previous.purposeCode(), command.subjectService(), command.subjectExternalId(),
                previous.sourceConsentId(),
                consents.nextDecisionNo(command.subjectService(), command.subjectExternalId(), previous.purposeId()),
                ConsentDecision.Decision.WITHDRAWN, command.channelCode(), now, now, null,
                context.actorId(), verified.authenticationStrength(), previous.noticeSha256(),
                command.evidenceUri(), command.evidenceSha256(), context.correlationId());
        consents.insert(decision, context.idempotencyKey(), context.actorId());
        audit.write(decision, context);
        outbox.emit("CONSENT", decision.id(), "ConsentWithdrawn", event(decision), context);
        return decision;
    }

    /** Returns current eligibility with explicit reason so consumers can fail closed safely. */
    @Transactional(readOnly = true)
    public ConsentEvaluation evaluate(String subjectService, String subjectId, String purposeCode, OffsetDateTime at) {
        var decision = consents.current(subjectService, subjectId, purposeCode, at == null ? OffsetDateTime.now() : at);
        if (decision.isEmpty()) return new ConsentEvaluation(false, "NO_EFFECTIVE_DECISION", null);
        var current = decision.get();
        if (current.expiresAt() != null && !current.expiresAt().isAfter(at == null ? OffsetDateTime.now() : at)) {
            return new ConsentEvaluation(false, "EXPIRED", current);
        }
        return new ConsentEvaluation(current.decision() == ConsentDecision.Decision.GRANTED,
                current.decision().name(), current);
    }

    /** Applies live IAM subject scope before a staff caller sees consent state. */
    @Transactional(readOnly = true)
    public ConsentEvaluation evaluateAuthorized(String subjectService, String subjectId,
                                                String purposeCode, OffsetDateTime at, RequestContext context) {
        authorizeRead(subjectService, subjectId, context);
        return evaluate(subjectService, subjectId, purposeCode, at);
    }

    /** Returns append-only history for authorized customer-service and compliance screens. */
    @Transactional(readOnly = true)
    public List<ConsentDecision> history(String subjectService, String subjectId, String purposeCode) {
        return consents.history(subjectService, subjectId, purposeCode);
    }

    /** Returns history only after the same subject-scope check used for capture. */
    @Transactional(readOnly = true)
    public List<ConsentDecision> historyAuthorized(String subjectService, String subjectId,
                                                   String purposeCode, RequestContext context) {
        authorizeRead(subjectService, subjectId, context);
        return history(subjectService, subjectId, purposeCode);
    }

    private void authorizeRead(String subjectService, String subjectId, RequestContext context) {
        if (context.hasPermission("SCOPE_PRIVACY_INTERNAL")) return;
        var result = verification.verify(subjectService, subjectId, context.actorId(),
                "PRIVACY_READ", context.correlationId());
        if (!result.verified()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUBJECT_READ_NOT_AUTHORIZED",
                    "Actor is not authorized for this subject.");
        }
    }

    private void requireIdempotency(RequestContext context) {
        if (context.idempotencyKey() == null || context.idempotencyKey().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key is required for consent changes.");
        }
    }

    private String title(String value) {
        return value.charAt(0) + value.substring(1).toLowerCase();
    }

    /** Publishes references and ordering only; evidence URI stays in the privacy store. */
    private ConsentChanged event(ConsentDecision value) {
        return new ConsentChanged(value.id(), value.purposeId(), value.purposeCode(),
                value.subjectService(), value.subjectExternalId(), value.sourceConsentId(), value.decisionNo(),
                value.decision(), value.effectiveAt(), value.expiresAt());
    }

    public record ConsentChanged(String decisionId, String purposeId, String purposeCode,
                                 String subjectService, String subjectExternalId, String sourceConsentId,
                                 long decisionNo,
                                 ConsentDecision.Decision decision, OffsetDateTime effectiveAt,
                                 OffsetDateTime expiresAt) {}

    public record DecideConsent(String subjectService, String subjectExternalId, String sourceConsentId, String purposeCode,
                                ConsentDecision.Decision decision, String channelCode, String noticeSha256,
                                OffsetDateTime effectiveAt, OffsetDateTime expiresAt,
                                String evidenceUri, String evidenceSha256) {}
    public record WithdrawConsent(String subjectService, String subjectExternalId, String purposeCode,
                                  String channelCode, String evidenceUri, String evidenceSha256) {}
    public record ConsentEvaluation(boolean allowed, String reason, ConsentDecision decision) {}
}
