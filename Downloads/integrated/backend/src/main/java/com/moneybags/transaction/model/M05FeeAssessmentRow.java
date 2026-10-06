package com.moneybags.transaction.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M05FeeAssessmentRow(
    @DbColumn(name="FEE_ASSESSMENT_ID", generated=true, identity=true, nullable=false) BigDecimal feeAssessmentId,
    @DbColumn(name="FEE_KEY", generated=false, identity=false, nullable=false) String feeKey,
    @DbColumn(name="BANK_ACCOUNT_ID", generated=false, identity=false, nullable=true) BigDecimal bankAccountId,
    @DbColumn(name="LOAN_FACILITY_ID", generated=false, identity=false, nullable=true) BigDecimal loanFacilityId,
    @DbColumn(name="PRODUCT_FEE_RULE_ID", generated=false, identity=false, nullable=false) BigDecimal productFeeRuleId,
    @DbColumn(name="ASSESSED_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal assessedAmount,
    @DbColumn(name="PAID_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal paidAmount,
    @DbColumn(name="WAIVED_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal waivedAmount,
    @DbColumn(name="DUE_DATE", generated=false, identity=false, nullable=false) LocalDate dueDate,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="ASSESSMENT_JOURNAL_ID", generated=false, identity=false, nullable=true) BigDecimal assessmentJournalId,
    @DbColumn(name="LAST_JOURNAL_ID", generated=false, identity=false, nullable=true) BigDecimal lastJournalId,
    @DbColumn(name="ASSESSED_AT", generated=false, identity=false, nullable=false) OffsetDateTime assessedAt
) {}
