package com.moneybags.payment.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M06PaymentInstructionRow(
    @DbColumn(name="PAYMENT_ID", generated=true, identity=true, nullable=false) BigDecimal paymentId,
    @DbColumn(name="ORIGINATOR_ID", generated=false, identity=false, nullable=false) String originatorId,
    @DbColumn(name="CHANNEL_CODE", generated=false, identity=false, nullable=false) String channelCode,
    @DbColumn(name="REQUEST_KEY", generated=false, identity=false, nullable=false) String requestKey,
    @DbColumn(name="REQUEST_HASH", generated=false, identity=false, nullable=false) byte[] requestHash,
    @DbColumn(name="CORRELATION_ID", generated=false, identity=false, nullable=false) String correlationId,
    @DbColumn(name="END_TO_END_ID", generated=false, identity=false, nullable=false) String endToEndId,
    @DbColumn(name="PAYMENT_DIRECTION", generated=false, identity=false, nullable=false) String paymentDirection,
    @DbColumn(name="PAYMENT_KIND", generated=false, identity=false, nullable=false) String paymentKind,
    @DbColumn(name="ORIGINAL_PAYMENT_ID", generated=false, identity=false, nullable=true) BigDecimal originalPaymentId,
    @DbColumn(name="SOURCE_ACCOUNT_ID", generated=false, identity=false, nullable=true) BigDecimal sourceAccountId,
    @DbColumn(name="SOURCE_CIF_ID", generated=false, identity=false, nullable=true) String sourceCifId,
    @DbColumn(name="DESTINATION_ACCOUNT_ID", generated=false, identity=false, nullable=true) BigDecimal destinationAccountId,
    @DbColumn(name="DESTINATION_CIF_ID", generated=false, identity=false, nullable=true) String destinationCifId,
    @DbColumn(name="BENEFICIARY_TOKEN_REF", generated=false, identity=false, nullable=true) String beneficiaryTokenRef,
    @DbColumn(name="BENEFICIARY_BANK_CODE", generated=false, identity=false, nullable=true) String beneficiaryBankCode,
    @DbColumn(name="RAIL_CODE", generated=false, identity=false, nullable=false) String railCode,
    @DbColumn(name="AMOUNT", generated=false, identity=false, nullable=false) BigDecimal amount,
    @DbColumn(name="CURRENCY_CODE", generated=false, identity=false, nullable=false) String currencyCode,
    @DbColumn(name="REQUESTED_EXECUTION_DATE", generated=false, identity=false, nullable=false) LocalDate requestedExecutionDate,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="VERSION_NO", generated=false, identity=false, nullable=false) Long versionNo,
    @DbColumn(name="HOLD_ID", generated=false, identity=false, nullable=true) BigDecimal holdId,
    @DbColumn(name="CUSTOMER_TXN_ID", generated=false, identity=false, nullable=true) BigDecimal customerTxnId,
    @DbColumn(name="CLEARING_BATCH_ID", generated=false, identity=false, nullable=true) BigDecimal clearingBatchId,
    @DbColumn(name="SETTLEMENT_CYCLE_ID", generated=false, identity=false, nullable=true) BigDecimal settlementCycleId,
    @DbColumn(name="LIQUIDITY_HOLD_ID", generated=false, identity=false, nullable=true) BigDecimal liquidityHoldId,
    @DbColumn(name="RAIL_MESSAGE_ID", generated=false, identity=false, nullable=true) String railMessageId,
    @DbColumn(name="RAIL_REFERENCE", generated=false, identity=false, nullable=true) String railReference,
    @DbColumn(name="REASON_CODE", generated=false, identity=false, nullable=true) String reasonCode,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt,
    @DbColumn(name="UPDATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime updatedAt
) {}
