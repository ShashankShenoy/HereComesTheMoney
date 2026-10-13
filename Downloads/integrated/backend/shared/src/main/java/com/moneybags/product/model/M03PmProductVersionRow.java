package com.moneybags.product.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M03PmProductVersionRow(
    @DbColumn(name="PRODUCT_VERSION_ID", generated=true, identity=true, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="PRODUCT_ID", generated=false, identity=false, nullable=false) BigDecimal productId,
    @DbColumn(name="VERSION_NO", generated=false, identity=false, nullable=false) Long versionNo,
    @DbColumn(name="SOURCE_VERSION_ID", generated=false, identity=false, nullable=true) BigDecimal sourceVersionId,
    @DbColumn(name="VERSION_STATE", generated=false, identity=false, nullable=false) String versionState,
    @DbColumn(name="EFFECTIVE_FROM_AT", generated=false, identity=false, nullable=false) OffsetDateTime effectiveFromAt,
    @DbColumn(name="EFFECTIVE_TO_AT", generated=false, identity=false, nullable=true) OffsetDateTime effectiveToAt,
    @DbColumn(name="DEFAULT_TXN_ACTION", generated=false, identity=false, nullable=false) String defaultTxnAction,
    @DbColumn(name="CHANGE_REASON", generated=false, identity=false, nullable=false) String changeReason,
    @DbColumn(name="RULE_SET_HASH", generated=false, identity=false, nullable=true) String ruleSetHash,
    @DbColumn(name="CREATED_BY_USER_ID", generated=false, identity=false, nullable=false) String createdByUserId,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt,
    @DbColumn(name="UPDATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime updatedAt,
    @DbColumn(name="ROW_VERSION", generated=false, identity=false, nullable=false) Long rowVersion
) {}
