package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;

/** Version-bound fixed-deposit limits approved in Product master. */
public record M03PmTermDepositRuleRow(
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="MIN_DEPOSIT_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal minDepositAmount,
    @DbColumn(name="MAX_DEPOSIT_AMOUNT", generated=false, identity=false, nullable=false) BigDecimal maxDepositAmount,
    @DbColumn(name="MIN_TENURE_MONTHS", generated=false, identity=false, nullable=false) Long minTenureMonths,
    @DbColumn(name="MAX_TENURE_MONTHS", generated=false, identity=false, nullable=false) Long maxTenureMonths
) { }
