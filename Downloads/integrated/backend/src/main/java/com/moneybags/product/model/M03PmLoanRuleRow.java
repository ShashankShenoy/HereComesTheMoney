package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmLoanRuleRow(
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="LOAN_CATEGORY", generated=false, identity=false, nullable=false) String loanCategory,
    @DbColumn(name="MIN_LOAN_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal minLoanAmount,
    @DbColumn(name="MAX_LOAN_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal maxLoanAmount,
    @DbColumn(name="MIN_TENURE_MONTHS", generated=false, identity=false, nullable=false) Long minTenureMonths,
    @DbColumn(name="MAX_TENURE_MONTHS", generated=false, identity=false, nullable=false) Long maxTenureMonths,
    @DbColumn(name="ALLOWED_INTEREST_TYPE", generated=false, identity=false, nullable=false) String allowedInterestType,
    @DbColumn(name="REPAYMENT_FREQUENCY", generated=false, identity=false, nullable=false) String repaymentFrequency,
    @DbColumn(name="AMORTIZATION_METHOD", generated=false, identity=false, nullable=false) String amortizationMethod,
    @DbColumn(name="DISBURSEMENT_MODE", generated=false, identity=false, nullable=false) String disbursementMode,
    @DbColumn(name="MAX_MORATORIUM_MONTHS", generated=false, identity=false, nullable=false) Long maxMoratoriumMonths,
    @DbColumn(name="GRACE_DAYS", generated=false, identity=false, nullable=false) Long graceDays,
    @DbColumn(name="COLLATERAL_REQUIRED", generated=false, identity=false, nullable=false) String collateralRequired,
    @DbColumn(name="GUARANTOR_REQUIRED", generated=false, identity=false, nullable=false) String guarantorRequired,
    @DbColumn(name="MAX_CO_BORROWERS", generated=false, identity=false, nullable=false) Long maxCoBorrowers,
    @DbColumn(name="PREPAYMENT_ALLOWED", generated=false, identity=false, nullable=false) String prepaymentAllowed,
    @DbColumn(name="PARTIAL_PREPAYMENT_ALLOWED", generated=false, identity=false, nullable=false) String partialPrepaymentAllowed,
    @DbColumn(name="FORECLOSURE_ALLOWED", generated=false, identity=false, nullable=false) String foreclosureAllowed
) {}
