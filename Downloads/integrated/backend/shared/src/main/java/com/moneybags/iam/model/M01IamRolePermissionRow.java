package com.moneybags.iam.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M01IamRolePermissionRow(
    @DbColumn(name="ROLE_ID", generated=false, identity=false, nullable=false) String roleId,
    @DbColumn(name="PERMISSION_ID", generated=false, identity=false, nullable=false) String permissionId
) {}
