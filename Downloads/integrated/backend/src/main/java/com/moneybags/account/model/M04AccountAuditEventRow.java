package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04AccountAuditEventRow(
    @DbColumn(name="AUDIT_ID", generated=false, identity=false, nullable=false) String auditId,
    @DbColumn(name="ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal accountId,
    @DbColumn(name="ACTION_TYPE", generated=false, identity=false, nullable=false) String actionType,
    @DbColumn(name="ACTOR_USER_ID", generated=false, identity=false, nullable=false) String actorUserId,
    @DbColumn(name="SESSION_ID", generated=false, identity=false, nullable=true) String sessionId,
    @DbColumn(name="RESULT", generated=false, identity=false, nullable=false) String result,
    @DbColumn(name="REASON_CODE", generated=false, identity=false, nullable=true) String reasonCode,
    @DbColumn(name="BEFORE_STATE_HASH", generated=false, identity=false, nullable=true) String beforeStateHash,
    @DbColumn(name="AFTER_STATE_HASH", generated=false, identity=false, nullable=true) String afterStateHash,
    @DbColumn(name="DETAILS_JSON", generated=false, identity=false, nullable=true) String detailsJson,
    @DbColumn(name="CORRELATION_ID", generated=false, identity=false, nullable=false) String correlationId,
    @DbColumn(name="OCCURRED_AT", generated=false, identity=false, nullable=false) OffsetDateTime occurredAt
) {}
