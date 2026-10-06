package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04AccountStatusHistoryRow(
    @DbColumn(name="STATUS_HISTORY_ID", generated=true, identity=true, nullable=false) BigDecimal statusHistoryId,
    @DbColumn(name="ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal accountId,
    @DbColumn(name="CHANGE_KIND", generated=false, identity=false, nullable=false) String changeKind,
    @DbColumn(name="OLD_STATUS", generated=false, identity=false, nullable=true) String oldStatus,
    @DbColumn(name="NEW_STATUS", generated=false, identity=false, nullable=false) String newStatus,
    @DbColumn(name="REASON_CODE", generated=false, identity=false, nullable=false) String reasonCode,
    @DbColumn(name="REASON_REMARKS", generated=false, identity=false, nullable=true) String reasonRemarks,
    @DbColumn(name="CHANGED_BY_USER_ID", generated=false, identity=false, nullable=false) String changedByUserId,
    @DbColumn(name="CHANGED_AT", generated=false, identity=false, nullable=false) OffsetDateTime changedAt,
    @DbColumn(name="CORRELATION_ID", generated=false, identity=false, nullable=false) String correlationId
) {}
