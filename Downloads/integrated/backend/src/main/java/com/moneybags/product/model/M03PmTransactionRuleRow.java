package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmTransactionRuleRow(
    @DbColumn(name="TRANSACTION_RULE_ID", generated=true, identity=true, nullable=false) BigDecimal transactionRuleId,
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="RULE_CODE", generated=false, identity=false, nullable=false) String ruleCode,
    @DbColumn(name="OPERATION_CODE", generated=false, identity=false, nullable=false) String operationCode,
    @DbColumn(name="CHANNEL_CODE", generated=false, identity=false, nullable=true) String channelCode,
    @DbColumn(name="DIRECTION_CODE", generated=false, identity=false, nullable=false) String directionCode,
    @DbColumn(name="COUNTERPARTY_CODE", generated=false, identity=false, nullable=true) String counterpartyCode,
    @DbColumn(name="GEOGRAPHY_CODE", generated=false, identity=false, nullable=true) String geographyCode,
    @DbColumn(name="TIME_WINDOW_CODE", generated=false, identity=false, nullable=true) String timeWindowCode,
    @DbColumn(name="ACTION_CODE", generated=false, identity=false, nullable=false) String actionCode,
    @DbColumn(name="PRIORITY_NO", generated=false, identity=false, nullable=false) Long priorityNo
) {}
