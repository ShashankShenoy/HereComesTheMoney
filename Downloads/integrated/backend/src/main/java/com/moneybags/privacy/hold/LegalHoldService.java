package com.moneybags.privacy.hold;

import com.moneybags.privacy.common.ApiException;
import com.moneybags.privacy.common.PageResponse;
import com.moneybags.privacy.common.RequestContext;
import com.moneybags.privacy.outbox.OutboxService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Objects;

/** Applies preservation rules before any data owner performs disposition. */
@Service
public class LegalHoldService {
    private final LegalHoldRepository repository;
    private final OutboxService outbox;

    public LegalHoldService(LegalHoldRepository repository, OutboxService outbox) {
        this.repository = repository;
        this.outbox = outbox;
    }

    /** Creates a scoped hold pending an independent checker. */
    @Transactional
    public LegalHold create(CreateHold command, RequestContext context) {
        if (context.idempotencyKey() == null || context.idempotencyKey().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.");
        }
        var existing = repository.findByIdempotencyKey(context.idempotencyKey());
        if (existing.isPresent()) {
            var prior = existing.get();
            if (!Objects.equals(prior.authorityReference(), command.authorityReference())
                    || !Objects.equals(prior.subjectExternalId(), command.subjectExternalId())
                    || !Objects.equals(prior.targetExternalId(), command.targetExternalId())
                    || !Objects.equals(prior.recordCategory(), command.recordCategory())) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key belongs to a different hold request.");
            }
            return prior;
        }
        if (command.subjectExternalId() == null && command.targetExternalId() == null
                && command.recordCategory() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "HOLD_SCOPE_REQUIRED", "Specify subject, resource or category.");
        }
        var id = UUID.randomUUID().toString();
        var hold = new LegalHold(id, "HOLD-" + id.substring(0, 8).toUpperCase(),
                LegalHold.HoldStatus.PENDING, command.authorityReference(), command.reason(),
                command.subjectService(), command.subjectExternalId(), command.targetService(),
                command.targetResourceType(), command.targetExternalId(), command.recordCategory(),
                command.recordFromAt(), command.recordToAt(), null, command.reviewDueAt(),
                null, context.actorId(), null, null, null, null);
        repository.insert(hold, context.correlationId(), context.idempotencyKey());
        outbox.emit("LEGAL_HOLD", id, "LegalHoldRequested", event(hold), context);
        return hold;
    }

    /** Activates the hold after maker-checker validation. */
    @Transactional
    public LegalHold activate(String id, RequestContext context) {
        if (repository.activate(id, context.actorId()) != 1) conflict();
        var hold = get(id);
        outbox.emit("LEGAL_HOLD", id, "LegalHoldActivated", event(hold), context);
        return hold;
    }

    /** Requests release; preservation stays active until the checker approves. */
    @Transactional
    public LegalHold requestRelease(String id, String authorityReference, RequestContext context) {
        if (repository.requestRelease(id, context.actorId(), authorityReference) != 1) conflict();
        var hold = get(id);
        outbox.emit("LEGAL_HOLD", id, "LegalHoldReleaseRequested", event(hold), context);
        return hold;
    }

    /** Completes release using the authenticated approver's identity. */
    @Transactional
    public LegalHold approveRelease(String id, RequestContext context) {
        if (repository.approveRelease(id, context.actorId()) != 1) conflict();
        var hold = get(id);
        outbox.emit("LEGAL_HOLD", id, "LegalHoldReleased", event(hold), context);
        return hold;
    }

    /** Returns an authoritative yes/no hold result; consumers must defer deletion on failure. */
    @Transactional(readOnly = true)
    public HoldEvaluation evaluate(LegalHoldRepository.HoldTarget target) {
        var blocked = repository.hasActiveMatch(target);
        return new HoldEvaluation(blocked, blocked ? "ACTIVE_HOLD" : "NO_MATCHING_HOLD", OffsetDateTime.now());
    }

    /** Returns the hold detail for authorized legal or compliance staff. */
    @Transactional(readOnly = true)
    public LegalHold get(String id) {
        return repository.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "Legal hold was not found."));
    }

    /** Lists the hold approval and review queue. */
    @Transactional(readOnly = true)
    public PageResponse<LegalHold> list(String status, int page, int size) {
        return new PageResponse<>(repository.findAll(status, page * size, size), page, size, repository.count(status));
    }

    private void conflict() {
        throw new ApiException(HttpStatus.CONFLICT, "HOLD_STATE_CONFLICT", "Hold state or maker-checker rule prevents this action.");
    }

    /** Sends hold scope and state, excluding legal reason and authority detail. */
    private HoldChanged event(LegalHold hold) {
        return new HoldChanged(hold.id(), hold.status(), hold.subjectService(),
                hold.subjectExternalId(), hold.targetService(), hold.targetResourceType(),
                hold.targetExternalId(), hold.recordCategory(), hold.recordFromAt(), hold.recordToAt());
    }

    public record HoldChanged(String id, LegalHold.HoldStatus status,
                              String subjectService, String subjectExternalId, String targetService,
                              String targetResourceType, String targetExternalId, String recordCategory,
                              OffsetDateTime recordFromAt, OffsetDateTime recordToAt) {}

    public record CreateHold(String authorityReference, String reason,
                             String subjectService, String subjectExternalId,
                             String targetService, String targetResourceType, String targetExternalId,
                             String recordCategory, OffsetDateTime recordFromAt,
                             OffsetDateTime recordToAt, OffsetDateTime reviewDueAt) {}
    public record HoldEvaluation(boolean dispositionBlocked, String reason, OffsetDateTime checkedAt) {}
}
