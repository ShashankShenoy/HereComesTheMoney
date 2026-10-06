package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06SecurityAuditEventRow(
    @DbColumn(name="AUDIT_EVENT_ID", generated=true, identity=true, nullable=false) BigDecimal auditEventId,
    @DbColumn(name="ACTOR_ID", generated=false, identity=false, nullable=false) String actorId,
    @DbColumn(name="SESSION_ID", generated=false, identity=false, nullable=true) String sessionId,
    @DbColumn(name="CORRELATION_ID", generated=false, identity=false, nullable=true) String correlationId,
    @DbColumn(name="ACTION_CODE", generated=false, identity=false, nullable=false) String actionCode,
    @DbColumn(name="RESOURCE_TYPE", generated=false, identity=false, nullable=false) String resourceType,
    @DbColumn(name="RESOURCE_ID", generated=false, identity=false, nullable=true) String resourceId,
    @DbColumn(name="RESULT_CODE", generated=false, identity=false, nullable=false) String resultCode,
    @DbColumn(name="REASON_CODE", generated=false, identity=false, nullable=true) String reasonCode,
    @DbColumn(name="OCCURRED_AT", generated=false, identity=false, nullable=false) OffsetDateTime occurredAt
) {}
