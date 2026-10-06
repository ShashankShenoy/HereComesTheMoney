package com.moneybags.iam.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M01IamRoleRow(
    @DbColumn(name="ROLE_ID", generated=false, identity=false, nullable=false) String roleId,
    @DbColumn(name="ROLE_CODE", generated=false, identity=false, nullable=false) String roleCode,
    @DbColumn(name="DISPLAY_NAME", generated=false, identity=false, nullable=false) String displayName,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="SENSITIVE_FLAG", generated=false, identity=false, nullable=false) String sensitiveFlag,
    @DbColumn(name="ROW_VERSION", generated=false, identity=false, nullable=false) Long rowVersion
) {}
