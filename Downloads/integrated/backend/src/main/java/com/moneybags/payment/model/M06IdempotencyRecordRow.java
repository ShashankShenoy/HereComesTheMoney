package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06IdempotencyRecordRow(
    @DbColumn(name="IDEMPOTENCY_ID", generated=true, identity=true, nullable=false) BigDecimal idempotencyId,
    @DbColumn(name="ORIGINATOR_ID", generated=false, identity=false, nullable=false) String originatorId,
    @DbColumn(name="CHANNEL_CODE", generated=false, identity=false, nullable=false) String channelCode,
    @DbColumn(name="REQUEST_KEY", generated=false, identity=false, nullable=false) String requestKey,
    @DbColumn(name="REQUEST_HASH", generated=false, identity=false, nullable=false) byte[] requestHash,
    @DbColumn(name="PAYMENT_ID", generated=false, identity=false, nullable=true) BigDecimal paymentId,
    @DbColumn(name="HTTP_STATUS", generated=false, identity=false, nullable=true) Long httpStatus,
    @DbColumn(name="RESPONSE_REF", generated=false, identity=false, nullable=true) String responseRef,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt,
    @DbColumn(name="EXPIRES_AT", generated=false, identity=false, nullable=true) OffsetDateTime expiresAt
) {}
