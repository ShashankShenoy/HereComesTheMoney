package com.moneybags.iam.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M01IamAccessRequestRow(
    @DbColumn(name="REQUEST_ID", generated=false, identity=false, nullable=false) String requestId,
    @DbColumn(name="TARGET_USER_ID", generated=false, identity=false, nullable=false) String targetUserId,
    @DbColumn(name="ROLE_ID", generated=false, identity=false, nullable=false) String roleId,
    @DbColumn(name="CHANGE_TYPE", generated=false, identity=false, nullable=false) String changeType,
    @DbColumn(name="TARGET_ASSIGNMENT_ID", generated=false, identity=false, nullable=true) String targetAssignmentId,
    @DbColumn(name="SCOPE_TYPE", generated=false, identity=false, nullable=false) String scopeType,
    @DbColumn(name="SCOPE_REF", generated=false, identity=false, nullable=true) String scopeRef,
    @DbColumn(name="VALID_FROM", generated=false, identity=false, nullable=false) OffsetDateTime validFrom,
    @DbColumn(name="VALID_TO", generated=false, identity=false, nullable=true) OffsetDateTime validTo,
    @DbColumn(name="REASON", generated=false, identity=false, nullable=false) String reason,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="MAKER_USER_ID", generated=false, identity=false, nullable=false) String makerUserId,
    @DbColumn(name="MAKER_SESSION_ID", generated=false, identity=false, nullable=false) String makerSessionId,
    @DbColumn(name="CHECKER_USER_ID", generated=false, identity=false, nullable=true) String checkerUserId,
    @DbColumn(name="CHECKER_SESSION_ID", generated=false, identity=false, nullable=true) String checkerSessionId,
    @DbColumn(name="DECIDED_AT", generated=false, identity=false, nullable=true) OffsetDateTime decidedAt,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt,
    @DbColumn(name="CORRELATION_ID", generated=false, identity=false, nullable=false) String correlationId,
    @DbColumn(name="ROW_VERSION", generated=false, identity=false, nullable=false) Long rowVersion
) {}
