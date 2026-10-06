package com.moneybags.cif.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M02KycReviewRow(
    @DbColumn(name="REVIEW_ID", generated=false, identity=false, nullable=false) String reviewId,
    @DbColumn(name="CASE_ID", generated=false, identity=false, nullable=false) String caseId,
    @DbColumn(name="MAKER_USER_ID", generated=false, identity=false, nullable=false) String makerUserId,
    @DbColumn(name="REVIEWER_USER_ID", generated=false, identity=false, nullable=false) String reviewerUserId,
    @DbColumn(name="REVIEWER_SESSION_ID", generated=false, identity=false, nullable=false) String reviewerSessionId,
    @DbColumn(name="OUTCOME", generated=false, identity=false, nullable=false) String outcome,
    @DbColumn(name="REASON_CODE", generated=false, identity=false, nullable=true) String reasonCode,
    @DbColumn(name="COMMENTS", generated=false, identity=false, nullable=true) String comments,
    @DbColumn(name="REVIEWED_AT", generated=false, identity=false, nullable=false) OffsetDateTime reviewedAt,
    @DbColumn(name="CORRELATION_ID", generated=false, identity=false, nullable=false) String correlationId
) {}
