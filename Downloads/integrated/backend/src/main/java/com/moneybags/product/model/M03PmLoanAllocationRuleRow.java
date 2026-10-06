package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmLoanAllocationRuleRow(
    @DbColumn(name="ALLOCATION_RULE_ID", generated=true, identity=true, nullable=false) BigDecimal allocationRuleId,
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="ALLOCATION_FLOW", generated=false, identity=false, nullable=false) String allocationFlow,
    @DbColumn(name="PAYMENT_KIND", generated=false, identity=false, nullable=false) String paymentKind,
    @DbColumn(name="PRIORITY_NO", generated=false, identity=false, nullable=false) Long priorityNo,
    @DbColumn(name="COMPONENT_CODE", generated=false, identity=false, nullable=false) String componentCode,
    @DbColumn(name="FUTURE_INSTALLMENT_RULE", generated=false, identity=false, nullable=false) String futureInstallmentRule
) {}
