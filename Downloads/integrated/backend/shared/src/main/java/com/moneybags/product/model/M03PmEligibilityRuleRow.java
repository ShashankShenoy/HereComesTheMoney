package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmEligibilityRuleRow(
    @DbColumn(name="ELIGIBILITY_RULE_ID", generated=true, identity=true, nullable=false) BigDecimal eligibilityRuleId,
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="RULE_CODE", generated=false, identity=false, nullable=false) String ruleCode,
    @DbColumn(name="ATTRIBUTE_CODE", generated=false, identity=false, nullable=false) String attributeCode,
    @DbColumn(name="OPERATOR_CODE", generated=false, identity=false, nullable=false) String operatorCode,
    @DbColumn(name="VALUE_TYPE", generated=false, identity=false, nullable=false) String valueType,
    @DbColumn(name="VALUE_TEXT", generated=false, identity=false, nullable=true) String valueText,
    @DbColumn(name="VALUE_NUMBER", generated=false, identity=false, nullable=true) BigDecimal valueNumber,
    @DbColumn(name="VALUE_DATE", generated=false, identity=false, nullable=true) LocalDate valueDate,
    @DbColumn(name="VALUE_BOOLEAN", generated=false, identity=false, nullable=true) String valueBoolean,
    @DbColumn(name="FAILURE_REASON_CODE", generated=false, identity=false, nullable=false) String failureReasonCode,
    @DbColumn(name="SEQUENCE_NO", generated=false, identity=false, nullable=false) Long sequenceNo
) {}
