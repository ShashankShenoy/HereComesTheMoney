package com.moneybags.transaction.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M05TxnTransactionLogRow(
    @DbColumn(name="TXN_ID", generated=true, identity=true, nullable=false) BigDecimal txnId,
    @DbColumn(name="ORIGINATOR_ID", generated=false, identity=false, nullable=false) String originatorId,
    @DbColumn(name="CHANNEL_CODE", generated=false, identity=false, nullable=false) String channelCode,
    @DbColumn(name="REQUEST_KEY", generated=false, identity=false, nullable=false) String requestKey,
    @DbColumn(name="REQUEST_HASH", generated=false, identity=false, nullable=false) byte[] requestHash,
    @DbColumn(name="CORRELATION_ID", generated=false, identity=false, nullable=false) String correlationId,
    @DbColumn(name="TXN_TYPE", generated=false, identity=false, nullable=false) String txnType,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="SOURCE_ACCOUNT_ID", generated=false, identity=false, nullable=true) BigDecimal sourceAccountId,
    @DbColumn(name="TARGET_ACCOUNT_ID", generated=false, identity=false, nullable=true) BigDecimal targetAccountId,
    @DbColumn(name="LOAN_FACILITY_ID", generated=false, identity=false, nullable=true) BigDecimal loanFacilityId,
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=true) BigDecimal productVersionId,
    @DbColumn(name="ACCOUNT_RESTRICTION_VERSION", generated=false, identity=false, nullable=true) BigDecimal accountRestrictionVersion,
    @DbColumn(name="AMOUNT", generated=false, identity=false, nullable=false) BigDecimal amount,
    @DbColumn(name="CURRENCY_CODE", generated=false, identity=false, nullable=false) String currencyCode,
    @DbColumn(name="VALUE_DATE", generated=false, identity=false, nullable=false) LocalDate valueDate,
    @DbColumn(name="REVERSAL_OF_TXN_ID", generated=false, identity=false, nullable=true) BigDecimal reversalOfTxnId,
    @DbColumn(name="FAILURE_CODE", generated=false, identity=false, nullable=true) String failureCode,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt,
    @DbColumn(name="UPDATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime updatedAt
) {}
