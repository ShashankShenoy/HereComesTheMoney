package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmProductRow(
    @DbColumn(name="PRODUCT_ID", generated=true, identity=true, nullable=false) BigDecimal productId,
    @DbColumn(name="PRODUCT_CODE", generated=false, identity=false, nullable=false) String productCode,
    @DbColumn(name="PRODUCT_NAME", generated=false, identity=false, nullable=false) String productName,
    @DbColumn(name="PRODUCT_TYPE", generated=false, identity=false, nullable=false) String productType,
    @DbColumn(name="CURRENCY_CODE", generated=false, identity=false, nullable=false) String currencyCode,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="BUSINESS_OWNER_REF", generated=false, identity=false, nullable=false) String businessOwnerRef,
    @DbColumn(name="PRODUCT_MANAGER_REF", generated=false, identity=false, nullable=true) String productManagerRef,
    @DbColumn(name="SUPPORT_REF", generated=false, identity=false, nullable=true) String supportRef,
    @DbColumn(name="DESCRIPTION", generated=false, identity=false, nullable=true) String description,
    @DbColumn(name="SALES_START_AT", generated=false, identity=false, nullable=false) OffsetDateTime salesStartAt,
    @DbColumn(name="SALES_END_AT", generated=false, identity=false, nullable=true) OffsetDateTime salesEndAt,
    @DbColumn(name="CREATED_BY_USER_ID", generated=false, identity=false, nullable=false) String createdByUserId,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt,
    @DbColumn(name="UPDATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime updatedAt,
    @DbColumn(name="ROW_VERSION", generated=false, identity=false, nullable=false) Long rowVersion
) {}
