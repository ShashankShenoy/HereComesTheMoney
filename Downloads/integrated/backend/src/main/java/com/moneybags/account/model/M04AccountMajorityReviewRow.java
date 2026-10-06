package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04AccountMajorityReviewRow(
    @DbColumn(name="REVIEW_ID", generated=true, identity=true, nullable=false) BigDecimal reviewId,
    @DbColumn(name="ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal accountId,
    @DbColumn(name="REVIEW_REQUEST_ID", generated=false, identity=false, nullable=false) String reviewRequestId,
    @DbColumn(name="REVIEW_STATUS", generated=false, identity=false, nullable=false) String reviewStatus,
    @DbColumn(name="DUE_AT", generated=false, identity=false, nullable=false) OffsetDateTime dueAt,
    @DbColumn(name="STARTED_AT", generated=false, identity=false, nullable=false) OffsetDateTime startedAt,
    @DbColumn(name="DECIDED_BY_USER_ID", generated=false, identity=false, nullable=true) String decidedByUserId,
    @DbColumn(name="DECIDED_AT", generated=false, identity=false, nullable=true) OffsetDateTime decidedAt,
    @DbColumn(name="DECISION_REASON", generated=false, identity=false, nullable=true) String decisionReason
) {}
