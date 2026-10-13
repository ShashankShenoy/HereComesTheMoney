package com.moneybags.transaction.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M05GlPeriodCloseRow(
    @DbColumn(name="CLOSE_ID", generated=true, identity=true, nullable=false) BigDecimal closeId,
    @DbColumn(name="REQUEST_KEY", generated=false, identity=false, nullable=false) String requestKey,
    @DbColumn(name="CLOSED_THROUGH_DATE", generated=false, identity=false, nullable=false) LocalDate closedThroughDate,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="MAKER_USER_ID", generated=false, identity=false, nullable=false) String makerUserId,
    @DbColumn(name="CHECKER_USER_ID", generated=false, identity=false, nullable=true) String checkerUserId,
    @DbColumn(name="REASON_TEXT", generated=false, identity=false, nullable=false) String reasonText,
    @DbColumn(name="REQUESTED_AT", generated=false, identity=false, nullable=false) OffsetDateTime requestedAt,
    @DbColumn(name="DECIDED_AT", generated=false, identity=false, nullable=true) OffsetDateTime decidedAt
) {}
