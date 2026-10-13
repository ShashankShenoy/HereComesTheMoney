package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04AcctOutboxEventRow(
    @DbColumn(name="EVENT_ID", generated=false, identity=false, nullable=false) String eventId,
    @DbColumn(name="EVENT_TYPE", generated=false, identity=false, nullable=false) String eventType,
    @DbColumn(name="PRODUCER", generated=false, identity=false, nullable=false) String producer,
    @DbColumn(name="SCHEMA_VERSION", generated=false, identity=false, nullable=false) Long schemaVersion,
    @DbColumn(name="AGGREGATE_TYPE", generated=false, identity=false, nullable=false) String aggregateType,
    @DbColumn(name="AGGREGATE_ID", generated=false, identity=false, nullable=false) String aggregateId,
    @DbColumn(name="CORRELATION_ID", generated=false, identity=false, nullable=false) String correlationId,
    @DbColumn(name="PAYLOAD", generated=false, identity=false, nullable=false) String payload,
    @DbColumn(name="OCCURRED_AT", generated=false, identity=false, nullable=false) OffsetDateTime occurredAt,
    @DbColumn(name="PUBLISHED_AT", generated=false, identity=false, nullable=true) OffsetDateTime publishedAt,
    @DbColumn(name="ATTEMPT_COUNT", generated=false, identity=false, nullable=false) Long attemptCount
) {}
