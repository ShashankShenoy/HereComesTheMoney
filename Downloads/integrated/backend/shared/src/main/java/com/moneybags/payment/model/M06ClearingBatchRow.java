package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06ClearingBatchRow(
    @DbColumn(name="CLEARING_BATCH_ID", generated=true, identity=true, nullable=false) BigDecimal clearingBatchId,
    @DbColumn(name="RAIL_CODE", generated=false, identity=false, nullable=false) String railCode,
    @DbColumn(name="BATCH_REFERENCE", generated=false, identity=false, nullable=false) String batchReference,
    @DbColumn(name="SETTLEMENT_CYCLE_ID", generated=false, identity=false, nullable=true) BigDecimal settlementCycleId,
    @DbColumn(name="BATCH_STATUS", generated=false, identity=false, nullable=false) String batchStatus,
    @DbColumn(name="BATCH_DIRECTION", generated=false, identity=false, nullable=false) String batchDirection,
    @DbColumn(name="CURRENCY_CODE", generated=false, identity=false, nullable=false) String currencyCode,
    @DbColumn(name="ITEM_COUNT", generated=false, identity=false, nullable=false) Long itemCount,
    @DbColumn(name="TOTAL_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal totalAmount,
    @DbColumn(name="GROSS_OUT_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal grossOutAmount,
    @DbColumn(name="GROSS_IN_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal grossInAmount,
    @DbColumn(name="NET_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal netAmount,
    @DbColumn(name="NET_MOVEMENT_SIDE", generated=false, identity=false, nullable=true) String netMovementSide,
    @DbColumn(name="OPENED_AT", generated=false, identity=false, nullable=false) OffsetDateTime openedAt,
    @DbColumn(name="CLOSED_AT", generated=false, identity=false, nullable=true) OffsetDateTime closedAt,
    @DbColumn(name="SETTLED_AT", generated=false, identity=false, nullable=true) OffsetDateTime settledAt
) {}
