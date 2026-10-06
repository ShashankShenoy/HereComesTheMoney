package com.moneybags.transaction.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M05GlProductMappingRow(
    @DbColumn(name="MAPPING_ID", generated=true, identity=true, nullable=false) BigDecimal mappingId,
    @DbColumn(name="PRODUCT_VERSION_ID", generated=false, identity=false, nullable=false) BigDecimal productVersionId,
    @DbColumn(name="POSTING_TYPE", generated=false, identity=false, nullable=false) String postingType,
    @DbColumn(name="GL_ROLE_CODE", generated=false, identity=false, nullable=false) String glRoleCode,
    @DbColumn(name="GL_ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal glAccountId,
    @DbColumn(name="EFFECTIVE_FROM", generated=false, identity=false, nullable=false) LocalDate effectiveFrom,
    @DbColumn(name="EFFECTIVE_TO", generated=false, identity=false, nullable=true) LocalDate effectiveTo,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt,
    @DbColumn(name="CREATED_BY", generated=false, identity=false, nullable=false) String createdBy
) {}
