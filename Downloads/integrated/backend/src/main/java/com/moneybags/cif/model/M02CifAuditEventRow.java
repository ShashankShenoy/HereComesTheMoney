package com.moneybags.cif.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M02CifAuditEventRow(
    @DbColumn(name="AUDIT_ID", generated=false, identity=false, nullable=false) String auditId,
    @DbColumn(name="EVENT_TYPE", generated=false, identity=false, nullable=false) String eventType,
    @DbColumn(name="ACTOR_USER_ID", generated=false, identity=false, nullable=true) String actorUserId,
    @DbColumn(name="SESSION_ID", generated=false, identity=false, nullable=true) String sessionId,
    @DbColumn(name="CIF_ID", generated=false, identity=false, nullable=true) String cifId,
    @DbColumn(name="CASE_ID", generated=false, identity=false, nullable=true) String caseId,
    @DbColumn(name="RESULT", generated=false, identity=false, nullable=false) String result,
    @DbColumn(name="REASON_CODE", generated=false, identity=false, nullable=true) String reasonCode,
    @DbColumn(name="MAKER_USER_ID", generated=false, identity=false, nullable=true) String makerUserId,
    @DbColumn(name="CHECKER_USER_ID", generated=false, identity=false, nullable=true) String checkerUserId,
    @DbColumn(name="BEFORE_STATE_HASH", generated=false, identity=false, nullable=true) String beforeStateHash,
    @DbColumn(name="AFTER_STATE_HASH", generated=false, identity=false, nullable=true) String afterStateHash,
    @DbColumn(name="CORRELATION_ID", generated=false, identity=false, nullable=false) String correlationId,
    @DbColumn(name="OCCURRED_AT", generated=false, identity=false, nullable=false) OffsetDateTime occurredAt
) {}
