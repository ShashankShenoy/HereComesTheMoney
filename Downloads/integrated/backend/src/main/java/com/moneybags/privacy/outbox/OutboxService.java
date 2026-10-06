package com.moneybags.privacy.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.privacy.common.RequestContext;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Creates a consistent event envelope for every externally visible state change. */
@Service
public class OutboxService {
    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Serializes and appends an event; callers invoke this inside their command transaction. */
    public void emit(String aggregateType, String aggregateId, String eventType,
                     Object eventPayload, RequestContext context) {
        try {
            var eventId = UUID.randomUUID().toString();
            var payload = objectMapper.writeValueAsString(new EventEnvelope(
                    eventId, eventType, 1, aggregateType, aggregateId,
                    context.correlationId(), OffsetDateTime.now(), eventPayload));
            // The event ID is unique even when one HTTP request changes several aggregates.
            var idempotency = eventId;
            repository.append(new OutboxEventRepository.OutboxEvent(
                    eventId, aggregateType, aggregateId, eventType, 1, payload,
                    idempotency, context.correlationId(), OffsetDateTime.now(), context.actorId()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize outbox event", exception);
        }
    }

    /** Versioned payload understood by all Moneybags event consumers. */
    public record EventEnvelope(String eventId, String eventType, int eventVersion,
                                String aggregateType, String aggregateId, String correlationId,
                                OffsetDateTime occurredAt, Object data) {}
}
