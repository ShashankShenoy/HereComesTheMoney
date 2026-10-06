package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06OutboxEventRow(
    @DbColumn(name="EVENT_ID", generated=false, identity=false, nullable=false) byte[] eventId,
    @DbColumn(name="EVENT_TYPE", generated=false, identity=false, nullable=false) String eventType,
    @DbColumn(name="SCHEMA_VERSION", generated=false, identity=false, nullable=false) Long schemaVersion,
    @DbColumn(name="AGGREGATE_TYPE", generated=false, identity=false, nullable=false) String aggregateType,
    @DbColumn(name="AGGREGATE_ID", generated=false, identity=false, nullable=false) String aggregateId,
    @DbColumn(name="CORRELATION_ID", generated=false, identity=false, nullable=true) String correlationId,
    @DbColumn(name="CAUSATION_ID", generated=false, identity=false, nullable=true) String causationId,
    @DbColumn(name="PARTITION_KEY", generated=false, identity=false, nullable=false) String partitionKey,
    @DbColumn(name="PAYLOAD_JSON", generated=false, identity=false, nullable=false) String payloadJson,
    @DbColumn(name="OCCURRED_AT", generated=false, identity=false, nullable=false) OffsetDateTime occurredAt,
    @DbColumn(name="AVAILABLE_AT", generated=false, identity=false, nullable=false) OffsetDateTime availableAt,
    @DbColumn(name="PUBLISH_STATUS", generated=false, identity=false, nullable=false) String publishStatus,
    @DbColumn(name="PUBLISHED_AT", generated=false, identity=false, nullable=true) OffsetDateTime publishedAt,
    @DbColumn(name="PUBLISH_ATTEMPTS", generated=false, identity=false, nullable=false) Long publishAttempts,
    @DbColumn(name="LOCKED_BY", generated=false, identity=false, nullable=true) String lockedBy,
    @DbColumn(name="LOCKED_UNTIL", generated=false, identity=false, nullable=true) OffsetDateTime lockedUntil,
    @DbColumn(name="LAST_ERROR", generated=false, identity=false, nullable=true) String lastError
) {}
