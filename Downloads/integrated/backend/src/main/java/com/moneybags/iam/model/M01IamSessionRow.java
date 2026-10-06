package com.moneybags.iam.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M01IamSessionRow(
    @DbColumn(name="SESSION_ID", generated=false, identity=false, nullable=false) String sessionId,
    @DbColumn(name="USER_ID", generated=false, identity=false, nullable=false) String userId,
    @DbColumn(name="CLIENT_ID", generated=false, identity=false, nullable=false) String clientId,
    @DbColumn(name="DEVICE_REF", generated=false, identity=false, nullable=true) String deviceRef,
    @DbColumn(name="AUTH_LEVEL", generated=false, identity=false, nullable=false) String authLevel,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt,
    @DbColumn(name="LAST_ACTIVITY_AT", generated=false, identity=false, nullable=false) OffsetDateTime lastActivityAt,
    @DbColumn(name="IDLE_EXPIRES_AT", generated=false, identity=false, nullable=false) OffsetDateTime idleExpiresAt,
    @DbColumn(name="ABSOLUTE_EXPIRES_AT", generated=false, identity=false, nullable=false) OffsetDateTime absoluteExpiresAt,
    @DbColumn(name="ENDED_AT", generated=false, identity=false, nullable=true) OffsetDateTime endedAt
) {}
