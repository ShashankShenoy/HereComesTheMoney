package com.moneybags.transaction.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M05VelocityUsageRow(
    @DbColumn(name="SUBJECT_TYPE", generated=false, identity=false, nullable=false) String subjectType,
    @DbColumn(name="SUBJECT_ID", generated=false, identity=false, nullable=false) String subjectId,
    @DbColumn(name="CONTROL_CODE", generated=false, identity=false, nullable=false) String controlCode,
    @DbColumn(name="WINDOW_START", generated=false, identity=false, nullable=false) OffsetDateTime windowStart,
    @DbColumn(name="WINDOW_START_UTC", generated=true, identity=false, nullable=false) LocalDateTime windowStartUtc,
    @DbColumn(name="WINDOW_END", generated=false, identity=false, nullable=false) OffsetDateTime windowEnd,
    @DbColumn(name="TXN_COUNT", generated=false, identity=false, nullable=false) Long txnCount,
    @DbColumn(name="TOTAL_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal totalAmount,
    @DbColumn(name="VERSION_NO", generated=false, identity=false, nullable=false) Long versionNo,
    @DbColumn(name="UPDATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime updatedAt
) {}
