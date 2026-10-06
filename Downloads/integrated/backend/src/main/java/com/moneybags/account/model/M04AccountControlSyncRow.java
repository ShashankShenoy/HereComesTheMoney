package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04AccountControlSyncRow(
    @DbColumn(name="CONTROL_SYNC_ID", generated=true, identity=true, nullable=false) BigDecimal controlSyncId,
    @DbColumn(name="ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal accountId,
    @DbColumn(name="RESTRICTION_ID", generated=false, identity=false, nullable=true) BigDecimal restrictionId,
    @DbColumn(name="REQUEST_ID", generated=false, identity=false, nullable=false) String requestId,
    @DbColumn(name="CONTROL_VERSION", generated=false, identity=false, nullable=false) BigDecimal controlVersion,
    @DbColumn(name="TARGET_ACTION", generated=false, identity=false, nullable=false) String targetAction,
    @DbColumn(name="CONTROL_TYPE", generated=false, identity=false, nullable=false) String controlType,
    @DbColumn(name="CONTROL_AMOUNT", generated=false, identity=false, nullable=true) BigDecimal controlAmount,
    @DbColumn(name="SYNC_STATUS", generated=false, identity=false, nullable=false) String syncStatus,
    @DbColumn(name="SOURCE_EVENT_ID", generated=false, identity=false, nullable=false) byte[] sourceEventId,
    @DbColumn(name="MODULE5_ACK_EVENT_ID", generated=false, identity=false, nullable=true) byte[] module5AckEventId,
    @DbColumn(name="MODULE5_FENCE_VERSION", generated=false, identity=false, nullable=true) BigDecimal module5FenceVersion,
    @DbColumn(name="REQUESTED_AT", generated=false, identity=false, nullable=false) OffsetDateTime requestedAt,
    @DbColumn(name="ACKNOWLEDGED_AT", generated=false, identity=false, nullable=true) OffsetDateTime acknowledgedAt,
    @DbColumn(name="FAILURE_CODE", generated=false, identity=false, nullable=true) String failureCode
) {}
