package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06ClearingBatchItemRow(
    @DbColumn(name="CLEARING_BATCH_ITEM_ID", generated=true, identity=true, nullable=false) BigDecimal clearingBatchItemId,
    @DbColumn(name="CLEARING_BATCH_ID", generated=false, identity=false, nullable=false) BigDecimal clearingBatchId,
    @DbColumn(name="PAYMENT_ID", generated=false, identity=false, nullable=false) BigDecimal paymentId,
    @DbColumn(name="MOVEMENT_SIDE", generated=false, identity=false, nullable=false) String movementSide,
    @DbColumn(name="ITEM_STATUS", generated=false, identity=false, nullable=false) String itemStatus,
    @DbColumn(name="AMOUNT", generated=false, identity=false, nullable=false) BigDecimal amount,
    @DbColumn(name="CURRENCY_CODE", generated=false, identity=false, nullable=false) String currencyCode,
    @DbColumn(name="EXTERNAL_ITEM_REF", generated=false, identity=false, nullable=true) String externalItemRef,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt
) {}
