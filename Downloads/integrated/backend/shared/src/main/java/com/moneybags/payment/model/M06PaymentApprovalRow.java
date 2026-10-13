package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06PaymentApprovalRow(
    @DbColumn(name="APPROVAL_ID", generated=true, identity=true, nullable=false) BigDecimal approvalId,
    @DbColumn(name="APPROVAL_KEY", generated=false, identity=false, nullable=false) String approvalKey,
    @DbColumn(name="PAYMENT_ID", generated=false, identity=false, nullable=false) BigDecimal paymentId,
    @DbColumn(name="EXCEPTION_ID", generated=false, identity=false, nullable=true) BigDecimal exceptionId,
    @DbColumn(name="ACTION_CODE", generated=false, identity=false, nullable=false) String actionCode,
    @DbColumn(name="MAKER_ID", generated=false, identity=false, nullable=false) String makerId,
    @DbColumn(name="CHECKER_ID", generated=false, identity=false, nullable=true) String checkerId,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="REASON_TEXT", generated=false, identity=false, nullable=false) String reasonText,
    @DbColumn(name="REQUESTED_AT", generated=false, identity=false, nullable=false) OffsetDateTime requestedAt,
    @DbColumn(name="DECIDED_AT", generated=false, identity=false, nullable=true) OffsetDateTime decidedAt
) {}
