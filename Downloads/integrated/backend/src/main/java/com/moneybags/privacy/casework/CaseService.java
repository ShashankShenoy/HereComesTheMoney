package com.moneybags.privacy.casework;

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

/** Coordinates identity verification and task fan-out without modifying domain-owned records. */
@Service
public class CaseService {
    private final CaseRepository repository;
    private final CaseRoutingPolicy routing;
    private final OutboxService outbox;

    public CaseService(CaseRepository repository, CaseRoutingPolicy routing, OutboxService outbox) {
        this.repository = repository;
        this.routing = routing;
        this.outbox = outbox;
    }

    /** Opens a case and creates one idempotent task per authoritative data owner. */
    @Transactional
    public ComplianceCase create(CreateCase command, RequestContext context) {
        requireIdempotency(context);
        var routes = routing.routes(command.type(), command.targetService(), command.targetServices());
        if (routes.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CASE_OWNER_REQUIRED",
                    "Identify at least one record-owning service for case tasks.");
        }
        var existing = repository.findByIdempotencyKey(context.idempotencyKey());
        if (existing.isPresent()) {
            var prior = existing.get();
            if (prior.type() != command.type()
                    || !Objects.equals(prior.subjectService(), command.subjectService())
                    || !Objects.equals(prior.subjectExternalId(), command.subjectExternalId())
                    || !Objects.equals(prior.reason(), command.reason())) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key belongs to a different case request.");
            }
            return prior;
        }
        var id = UUID.randomUUID().toString();
        var reference = "PRIV-" + OffsetDateTime.now().getYear() + "-" + id.substring(0, 8).toUpperCase();
        var value = new ComplianceCase(id, reference, command.type(), ComplianceCase.CaseStatus.IDENTITY_PENDING,
                command.priority(), command.requesterService(), command.requesterExternalId(),
                command.requesterAuthorityRef(), command.subjectService(), command.subjectExternalId(),
                command.purposeId(), command.targetService(), command.targetResourceType(), command.targetExternalId(),
                command.reason(), null, command.dueAt(),
                null, null, 1, java.util.List.of());
        repository.insert(value, context.idempotencyKey(), context.correlationId(), context.actorId());
        for (var route : routes) {
            var taskId = UUID.randomUUID().toString();
            repository.insertTask(new ComplianceCase.CaseTask(taskId, id, route.service(), route.actionCode(),
                    ComplianceCase.TaskStatus.PENDING, command.targetResourceType(), command.targetExternalId(),
                    null, null, null, 1), taskId, context.actorId());
        }
        var created = get(id);
        outbox.emit("COMPLIANCE_CASE", id, "ComplianceCaseOpened", event(created), context);
        return created;
    }

    /** Lists the operations queue. */
    @Transactional(readOnly = true)
    public PageResponse<ComplianceCase> list(String status, int page, int size) {
        return new PageResponse<>(repository.findAll(status, page * size, size), page, size, repository.count(status));
    }

    /** Returns a case with all service tasks. */
    @Transactional(readOnly = true)
    public ComplianceCase get(String id) {
        return repository.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND", "Compliance case was not found."));
    }

    /** Returns only the task owned by the authenticated downstream service. */
    @Transactional(readOnly = true)
    public AssignedTask getTask(String caseId, String taskId, RequestContext context) {
        var parent = get(caseId);
        var task = parent.tasks().stream().filter(value -> value.id().equals(taskId))
                .findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "CASE_TASK_NOT_FOUND", "Task does not belong to this case."));
        if (!task.targetService().equals(context.actorId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TASK_OWNER_MISMATCH",
                    "Caller does not own this task.");
        }
        if (parent.identityVerifiedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "CASE_IDENTITY_PENDING",
                    "Assigned task is unavailable until identity review is complete.");
        }
        return new AssignedTask(parent.id(), parent.type(), parent.requesterService(),
                parent.requesterExternalId(), parent.requesterAuthorityRef(),
                parent.subjectService(), parent.subjectExternalId(), parent.reason(), task);
    }

    /** Records an independent identity-review decision before any service receives the case. */
    @Transactional
    public ComplianceCase verifyIdentity(String id, long expectedVersion, RequestContext context) {
        if (repository.verifyIdentity(id, expectedVersion, context.actorId()) != 1) conflict();
        var updated = get(id);
        outbox.emit("COMPLIANCE_CASE", id, "ComplianceCaseIdentityVerified", event(updated), context);
        return updated;
    }

    /** Accepts a service callback without allowing that service to alter the parent case directly. */
    @Transactional
    public ComplianceCase completeTask(String caseId, String taskId, CompleteTask command,
                                       long expectedVersion, RequestContext context) {
        var parent = get(caseId);
        var task = parent.tasks().stream().filter(t -> t.id().equals(taskId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CASE_TASK_NOT_FOUND", "Task does not belong to this case."));
        if (command.status() != ComplianceCase.TaskStatus.COMPLETED
                && command.status() != ComplianceCase.TaskStatus.FAILED
                && command.status() != ComplianceCase.TaskStatus.EXEMPTED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TASK_TERMINAL_STATUS_REQUIRED", "Completion requires a terminal status.");
        }
        if (command.status() == ComplianceCase.TaskStatus.EXEMPTED
                && (!context.hasPermission("PRIVACY_CASE_EXEMPT")
                    || command.failureReason() == null || command.failureReason().isBlank())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CASE_EXEMPTION_NOT_APPROVED",
                    "An exemption requires separate authority and a recorded reason.");
        }
        if (context.hasPermission("SCOPE_PRIVACY_INTERNAL")
                && !context.actorId().equals(task.targetService())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TASK_OWNER_MISMATCH", "Caller does not own this task.");
        }
        if (repository.completeTask(taskId, expectedVersion, command.status(), command.responseReference(),
                command.failureReason(), context.actorId()) != 1) conflict();
        var updated = get(caseId);
        outbox.emit("COMPLIANCE_CASE", caseId, "ComplianceCaseTaskCompleted", event(updated), context);
        return updated;
    }

    /** Closes a case only after every owner task completed or recorded an approved exemption. */
    @Transactional
    public ComplianceCase close(String id, long expectedVersion, RequestContext context) {
        if (repository.close(id, expectedVersion, context.actorId()) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "CASE_NOT_READY_TO_CLOSE",
                    "The case version changed or one or more tasks are not complete.");
        }
        var updated = get(id);
        outbox.emit("COMPLIANCE_CASE", id, "ComplianceCaseClosed", event(updated), context);
        return updated;
    }

    private void requireIdempotency(RequestContext context) {
        if (context.idempotencyKey() == null || context.idempotencyKey().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.");
        }
    }

    private void conflict() {
        throw new ApiException(HttpStatus.CONFLICT, "CASE_VERSION_CONFLICT", "Case state or version changed.");
    }

    /** Exposes routing references without broadcasting the request reason. */
    private CaseChanged event(ComplianceCase value) {
        return new CaseChanged(value.id(), value.type(), value.status(),
                value.subjectService(), value.subjectExternalId(), value.tasks().stream()
                .map(task -> new TaskReference(task.id(), task.targetService(), task.actionCode(), task.status()))
                .toList());
    }

    public record TaskReference(String id, String targetService, String actionCode,
                                ComplianceCase.TaskStatus status) {}
    public record CaseChanged(String caseId, ComplianceCase.CaseType type,
                              ComplianceCase.CaseStatus status, String subjectService,
                              String subjectExternalId, java.util.List<TaskReference> tasks) {}
    public record AssignedTask(String caseId, ComplianceCase.CaseType type,
                               String requesterService, String requesterExternalId,
                               String requesterAuthorityRef, String subjectService,
                               String subjectExternalId, String reason,
                               ComplianceCase.CaseTask task) {}

    public record CreateCase(ComplianceCase.CaseType type, ComplianceCase.Priority priority,
                             String requesterService, String requesterExternalId, String requesterAuthorityRef,
                             String subjectService, String subjectExternalId, String purposeId,
                             String targetService, java.util.List<String> targetServices,
                             String targetResourceType, String targetExternalId,
                             String reason, OffsetDateTime dueAt) {}
    public record CompleteTask(ComplianceCase.TaskStatus status, String responseReference, String failureReason) {}
}
