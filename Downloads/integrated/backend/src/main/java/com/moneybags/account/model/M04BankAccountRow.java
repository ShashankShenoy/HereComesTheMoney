package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04BankAccountRow(
    @DbColumn(name="ACCOUNT_ID", generated=true, identity=true, nullable=false) BigDecimal accountId,
    @DbColumn(name="ACCOUNT_NUMBER", generated=false, identity=false, nullable=false) String accountNumber,
    @DbColumn(name="PRIMARY_CIF_ID", generated=false, identity=false, nullable=false) String primaryCifId,
    @DbColumn(name="PRODUCT_ID", generated=false, identity=false, nullable=false) BigDecimal productId,
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="BRANCH_CODE", generated=false, identity=false, nullable=false) String branchCode,
    @DbColumn(name="CURRENCY_CODE", generated=false, identity=false, nullable=false) String currencyCode,
    @DbColumn(name="LIFECYCLE_STATUS", generated=false, identity=false, nullable=false) String lifecycleStatus,
    @DbColumn(name="ACCOUNT_STATUS", generated=false, identity=false, nullable=false) String accountStatus,
    @DbColumn(name="ACCOUNT_OPERATION_MODE", generated=false, identity=false, nullable=false) String accountOperationMode,
    @DbColumn(name="MAJORITY_REVIEW_STATUS", generated=false, identity=false, nullable=false) String majorityReviewStatus,
    @DbColumn(name="LEDGER_BALANCE", generated=false, identity=false, nullable=false) BigDecimal ledgerBalance,
    @DbColumn(name="BLOCKED_BALANCE", generated=false, identity=false, nullable=false) BigDecimal blockedBalance,
    @DbColumn(name="LIEN_BALANCE", generated=false, identity=false, nullable=false) BigDecimal lienBalance,
    @DbColumn(name="OVERDRAFT_LIMIT", generated=false, identity=false, nullable=false) BigDecimal overdraftLimit,
    @DbColumn(name="AVAILABLE_BALANCE", generated=true, identity=false, nullable=true) BigDecimal availableBalance,
    @DbColumn(name="BALANCE_AS_OF", generated=false, identity=false, nullable=true) OffsetDateTime balanceAsOf,
    @DbColumn(name="BALANCE_SOURCE_JOURNAL_ID", generated=false, identity=false, nullable=true) BigDecimal balanceSourceJournalId,
    @DbColumn(name="BALANCE_SOURCE_EVENT_ID", generated=false, identity=false, nullable=true) byte[] balanceSourceEventId,
    @DbColumn(name="BALANCE_SOURCE_VERSION", generated=false, identity=false, nullable=false) BigDecimal balanceSourceVersion,
    @DbColumn(name="FINANCIAL_CONTROL_VERSION", generated=false, identity=false, nullable=false) BigDecimal financialControlVersion,
    @DbColumn(name="INTEREST_ACCRUED", generated=false, identity=false, nullable=false) BigDecimal interestAccrued,
    @DbColumn(name="LAST_INTEREST_CALCULATION_AT", generated=false, identity=false, nullable=true) OffsetDateTime lastInterestCalculationAt,
    @DbColumn(name="LAST_INTEREST_POSTING_AT", generated=false, identity=false, nullable=true) OffsetDateTime lastInterestPostingAt,
    @DbColumn(name="OPENED_AT", generated=false, identity=false, nullable=false) OffsetDateTime openedAt,
    @DbColumn(name="ACTIVATED_AT", generated=false, identity=false, nullable=true) OffsetDateTime activatedAt,
    @DbColumn(name="CANCELLED_AT", generated=false, identity=false, nullable=true) OffsetDateTime cancelledAt,
    @DbColumn(name="CLOSED_AT", generated=false, identity=false, nullable=true) OffsetDateTime closedAt,
    @DbColumn(name="LAST_TRANSACTION_AT", generated=false, identity=false, nullable=true) OffsetDateTime lastTransactionAt,
    @DbColumn(name="OPEN_REQUEST_ID", generated=false, identity=false, nullable=false) String openRequestId,
    @DbColumn(name="CREATED_BY_USER_ID", generated=false, identity=false, nullable=false) String createdByUserId,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt,
    @DbColumn(name="UPDATED_BY_USER_ID", generated=false, identity=false, nullable=true) String updatedByUserId,
    @DbColumn(name="UPDATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime updatedAt,
    @DbColumn(name="ROW_VERSION", generated=false, identity=false, nullable=false) Long rowVersion
) {}
