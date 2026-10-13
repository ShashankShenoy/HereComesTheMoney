package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06RailDispatchAttemptRow(
    @DbColumn(name="DISPATCH_ATTEMPT_ID", generated=true, identity=true, nullable=false) BigDecimal dispatchAttemptId,
    @DbColumn(name="DISPATCH_ID", generated=false, identity=false, nullable=false) BigDecimal dispatchId,
    @DbColumn(name="ATTEMPT_NO", generated=false, identity=false, nullable=false) Long attemptNo,
    @DbColumn(name="ATTEMPT_TYPE", generated=false, identity=false, nullable=false) String attemptType,
    @DbColumn(name="OUTCOME_CODE", generated=false, identity=false, nullable=false) String outcomeCode,
    @DbColumn(name="TRANSPORT_REFERENCE", generated=false, identity=false, nullable=true) String transportReference,
    @DbColumn(name="ERROR_CODE", generated=false, identity=false, nullable=true) String errorCode,
    @DbColumn(name="STARTED_AT", generated=false, identity=false, nullable=false) OffsetDateTime startedAt,
    @DbColumn(name="COMPLETED_AT", generated=false, identity=false, nullable=true) OffsetDateTime completedAt
) {}
