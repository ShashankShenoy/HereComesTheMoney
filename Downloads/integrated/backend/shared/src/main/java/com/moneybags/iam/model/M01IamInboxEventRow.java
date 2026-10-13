package com.moneybags.iam.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M01IamInboxEventRow(
    @DbColumn(name="EVENT_ID", generated=false, identity=false, nullable=false) String eventId,
    @DbColumn(name="EVENT_TYPE", generated=false, identity=false, nullable=false) String eventType,
    @DbColumn(name="PRODUCER", generated=false, identity=false, nullable=false) String producer,
    @DbColumn(name="AGGREGATE_ID", generated=false, identity=false, nullable=false) String aggregateId,
    @DbColumn(name="RECEIVED_AT", generated=false, identity=false, nullable=false) OffsetDateTime receivedAt,
    @DbColumn(name="PROCESSED_AT", generated=false, identity=false, nullable=true) OffsetDateTime processedAt
) {}
