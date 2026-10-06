package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06PaymentDirectionTransitionRow(
    @DbColumn(name="PAYMENT_DIRECTION", generated=false, identity=false, nullable=false) String paymentDirection,
    @DbColumn(name="FROM_STATUS", generated=false, identity=false, nullable=false) String fromStatus,
    @DbColumn(name="TO_STATUS", generated=false, identity=false, nullable=false) String toStatus,
    @DbColumn(name="REQUIRES_RAIL_EVIDENCE", generated=false, identity=false, nullable=false) String requiresRailEvidence,
    @DbColumn(name="REQUIRES_LEDGER_EVIDENCE", generated=false, identity=false, nullable=false) String requiresLedgerEvidence
) {}
