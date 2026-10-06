package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06ConsumerInboxRow(
    @DbColumn(name="CONSUMER_NAME", generated=false, identity=false, nullable=false) String consumerName,
    @DbColumn(name="EVENT_ID", generated=false, identity=false, nullable=false) byte[] eventId,
    @DbColumn(name="EVENT_TYPE", generated=false, identity=false, nullable=false) String eventType,
    @DbColumn(name="RECEIVED_AT", generated=false, identity=false, nullable=false) OffsetDateTime receivedAt,
    @DbColumn(name="PROCESSED_AT", generated=false, identity=false, nullable=true) OffsetDateTime processedAt,
    @DbColumn(name="RESULT_CODE", generated=false, identity=false, nullable=true) String resultCode
) {}
