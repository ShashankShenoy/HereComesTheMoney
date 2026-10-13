package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06ReconciliationExceptionHistoryRow(
    @DbColumn(name="EXCEPTION_HISTORY_ID", generated=true, identity=true, nullable=false) BigDecimal exceptionHistoryId,
    @DbColumn(name="EXCEPTION_ID", generated=false, identity=false, nullable=false) BigDecimal exceptionId,
    @DbColumn(name="FROM_STATUS", generated=false, identity=false, nullable=true) String fromStatus,
    @DbColumn(name="TO_STATUS", generated=false, identity=false, nullable=false) String toStatus,
    @DbColumn(name="ACTOR_ID", generated=false, identity=false, nullable=false) String actorId,
    @DbColumn(name="ACTION_CODE", generated=false, identity=false, nullable=false) String actionCode,
    @DbColumn(name="REASON_TEXT", generated=false, identity=false, nullable=true) String reasonText,
    @DbColumn(name="CHANGED_AT", generated=false, identity=false, nullable=false) OffsetDateTime changedAt
) {}
