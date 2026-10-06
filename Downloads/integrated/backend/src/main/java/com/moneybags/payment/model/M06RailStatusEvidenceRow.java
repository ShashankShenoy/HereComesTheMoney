package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06RailStatusEvidenceRow(
    @DbColumn(name="EVIDENCE_ID", generated=true, identity=true, nullable=false) BigDecimal evidenceId,
    @DbColumn(name="PAYMENT_ID", generated=false, identity=false, nullable=false) BigDecimal paymentId,
    @DbColumn(name="RAIL_CODE", generated=false, identity=false, nullable=false) String railCode,
    @DbColumn(name="RAIL_MESSAGE_ID", generated=false, identity=false, nullable=false) String railMessageId,
    @DbColumn(name="EXTERNAL_REFERENCE", generated=false, identity=false, nullable=true) String externalReference,
    @DbColumn(name="EVIDENCE_TYPE", generated=false, identity=false, nullable=false) String evidenceType,
    @DbColumn(name="RAIL_STATUS", generated=false, identity=false, nullable=false) String railStatus,
    @DbColumn(name="REASON_CODE", generated=false, identity=false, nullable=true) String reasonCode,
    @DbColumn(name="EVIDENCE_HASH", generated=false, identity=false, nullable=false) byte[] evidenceHash,
    @DbColumn(name="EVIDENCE_REF", generated=false, identity=false, nullable=true) String evidenceRef,
    @DbColumn(name="OCCURRED_AT", generated=false, identity=false, nullable=true) OffsetDateTime occurredAt,
    @DbColumn(name="RECEIVED_AT", generated=false, identity=false, nullable=false) OffsetDateTime receivedAt
) {}
