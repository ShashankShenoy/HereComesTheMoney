package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06ReconciliationExceptionRow(
    @DbColumn(name="EXCEPTION_ID", generated=true, identity=true, nullable=false) BigDecimal exceptionId,
    @DbColumn(name="MISMATCH_KEY", generated=false, identity=false, nullable=false) String mismatchKey,
    @DbColumn(name="PAYMENT_ID", generated=false, identity=false, nullable=true) BigDecimal paymentId,
    @DbColumn(name="CLEARING_BATCH_ID", generated=false, identity=false, nullable=true) BigDecimal clearingBatchId,
    @DbColumn(name="EXCEPTION_TYPE", generated=false, identity=false, nullable=false) String exceptionType,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="SEVERITY", generated=false, identity=false, nullable=false) String severity,
    @DbColumn(name="OWNER_ID", generated=false, identity=false, nullable=true) String ownerId,
    @DbColumn(name="EVIDENCE_JSON", generated=false, identity=false, nullable=true) String evidenceJson,
    @DbColumn(name="RESOLUTION_CODE", generated=false, identity=false, nullable=true) String resolutionCode,
    @DbColumn(name="RESOLUTION_TEXT", generated=false, identity=false, nullable=true) String resolutionText,
    @DbColumn(name="OPENED_AT", generated=false, identity=false, nullable=false) OffsetDateTime openedAt,
    @DbColumn(name="RESOLVED_AT", generated=false, identity=false, nullable=true) OffsetDateTime resolvedAt
) {}
