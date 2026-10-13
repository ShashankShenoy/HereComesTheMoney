package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06PaymentReconciliationRunRow(
    @DbColumn(name="RECONCILIATION_RUN_ID", generated=true, identity=true, nullable=false) BigDecimal reconciliationRunId,
    @DbColumn(name="PAYMENT_ID", generated=false, identity=false, nullable=false) BigDecimal paymentId,
    @DbColumn(name="CHECK_VERSION", generated=false, identity=false, nullable=false) BigDecimal checkVersion,
    @DbColumn(name="RESULT_STATUS", generated=false, identity=false, nullable=false) String resultStatus,
    @DbColumn(name="CUSTOMER_POSTING_MATCH", generated=false, identity=false, nullable=false) String customerPostingMatch,
    @DbColumn(name="SUSPENSE_MATCH", generated=false, identity=false, nullable=false) String suspenseMatch,
    @DbColumn(name="RAIL_EVIDENCE_MATCH", generated=false, identity=false, nullable=false) String railEvidenceMatch,
    @DbColumn(name="RESERVE_MATCH", generated=false, identity=false, nullable=false) String reserveMatch,
    @DbColumn(name="DETAIL_JSON", generated=false, identity=false, nullable=true) String detailJson,
    @DbColumn(name="CHECKED_AT", generated=false, identity=false, nullable=false) OffsetDateTime checkedAt
) {}
