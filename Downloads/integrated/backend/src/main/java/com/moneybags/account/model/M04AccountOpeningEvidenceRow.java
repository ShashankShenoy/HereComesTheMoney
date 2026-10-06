package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04AccountOpeningEvidenceRow(
    @DbColumn(name="EVIDENCE_ID", generated=true, identity=true, nullable=false) BigDecimal evidenceId,
    @DbColumn(name="ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal accountId,
    @DbColumn(name="ATTEMPT_ID", generated=false, identity=false, nullable=false) String attemptId,
    @DbColumn(name="CIF_DECISION_REF", generated=false, identity=false, nullable=false) String cifDecisionRef,
    @DbColumn(name="PRODUCT_DECISION_REF", generated=false, identity=false, nullable=false) String productDecisionRef,
    @DbColumn(name="IAM_DECISION_REF", generated=false, identity=false, nullable=false) String iamDecisionRef,
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="PRODUCT_RULE_SET_HASH", generated=false, identity=false, nullable=false) String productRuleSetHash,
    @DbColumn(name="DECISION_RESULT", generated=false, identity=false, nullable=false) String decisionResult,
    @DbColumn(name="FAILURE_REASON_CODE", generated=false, identity=false, nullable=true) String failureReasonCode,
    @DbColumn(name="EVALUATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime evaluatedAt
) {}
