package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06RailDispatchRow(
    @DbColumn(name="DISPATCH_ID", generated=true, identity=true, nullable=false) BigDecimal dispatchId,
    @DbColumn(name="PAYMENT_ID", generated=false, identity=false, nullable=false) BigDecimal paymentId,
    @DbColumn(name="RAIL_CODE", generated=false, identity=false, nullable=false) String railCode,
    @DbColumn(name="RAIL_MESSAGE_ID", generated=false, identity=false, nullable=false) String railMessageId,
    @DbColumn(name="PAYLOAD_REF", generated=false, identity=false, nullable=false) String payloadRef,
    @DbColumn(name="DISPATCH_STATUS", generated=false, identity=false, nullable=false) String dispatchStatus,
    @DbColumn(name="ATTEMPT_COUNT", generated=false, identity=false, nullable=false) Long attemptCount,
    @DbColumn(name="FIRST_DISPATCHED_AT", generated=false, identity=false, nullable=true) OffsetDateTime firstDispatchedAt,
    @DbColumn(name="LAST_ATTEMPT_AT", generated=false, identity=false, nullable=true) OffsetDateTime lastAttemptAt,
    @DbColumn(name="LAST_ERROR_CODE", generated=false, identity=false, nullable=true) String lastErrorCode,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt
) {}
