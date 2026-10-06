package com.moneybags.privacy.purpose;

import com.moneybags.privacy.common.ApiException;
import com.moneybags.privacy.common.PageResponse;
import com.moneybags.privacy.common.RequestContext;
import com.moneybags.privacy.outbox.OutboxService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Applies versioning and maker-checker rules to the purpose catalogue. */
@Service
public class PurposeService {
    private final PurposeRepository repository;
    private final OutboxService outbox;

    public PurposeService(PurposeRepository repository, OutboxService outbox) {
        this.repository = repository;
        this.outbox = outbox;
    }

    /** Retrieves a filtered page without exposing persistence details. */
    @Transactional(readOnly = true)
    public PageResponse<ProcessingPurpose> list(String status, int page, int size) {
        return new PageResponse<>(repository.findAll(status, page * size, size), page, size, repository.count(status));
    }

    /** Returns an exact purpose version for notice display or review. */
    @Transactional(readOnly = true)
    public ProcessingPurpose get(String id) {
        return repository.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "PURPOSE_NOT_FOUND", "Purpose version was not found."));
    }

    /** Creates a new draft version and emits a reviewable change event atomically. */
    @Transactional
    public ProcessingPurpose create(CreatePurpose command, RequestContext context) {
        var purpose = new ProcessingPurpose(UUID.randomUUID().toString(), command.code(), command.version(),
                command.name(), command.ownerService(), command.legalBasisCode(), command.noticeUri(),
                command.noticeSha256(), ProcessingPurpose.PurposeStatus.DRAFT, command.effectiveFrom(),
                command.effectiveTo(), command.reviewDueAt(), null, null, 1);
        repository.insert(purpose, context.actorId());
        outbox.emit("PROCESSING_PURPOSE", purpose.id(), "PrivacyPurposeDrafted", purpose, context);
        return purpose;
    }

    /** Approves a purpose only when checker, state and expected version are all valid. */
    @Transactional
    public ProcessingPurpose approve(String id, long expectedVersion, RequestContext context) {
        if (repository.approve(id, expectedVersion, context.actorId()) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "PURPOSE_APPROVAL_CONFLICT",
                    "Purpose cannot be approved; verify its state, version and maker-checker rule.");
        }
        var approved = repository.findById(id).orElseThrow();
        outbox.emit("PROCESSING_PURPOSE", id, "PrivacyPurposeActivated", approved, context);
        return approved;
    }

    /** Input for a new immutable purpose version. */
    public record CreatePurpose(String code, int version, String name, String ownerService,
                                String legalBasisCode, String noticeUri, String noticeSha256,
                                OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                OffsetDateTime reviewDueAt) {}
}
