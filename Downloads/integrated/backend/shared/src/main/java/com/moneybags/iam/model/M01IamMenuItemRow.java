package com.moneybags.iam.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M01IamMenuItemRow(
    @DbColumn(name="MENU_ID", generated=false, identity=false, nullable=false) String menuId,
    @DbColumn(name="CLIENT_ID", generated=false, identity=false, nullable=false) String clientId,
    @DbColumn(name="PARENT_MENU_ID", generated=false, identity=false, nullable=true) String parentMenuId,
    @DbColumn(name="ROUTE_PATH", generated=false, identity=false, nullable=false) String routePath,
    @DbColumn(name="LABEL_KEY", generated=false, identity=false, nullable=false) String labelKey,
    @DbColumn(name="SORT_ORDER", generated=false, identity=false, nullable=false) Long sortOrder,
    @DbColumn(name="REQUIRED_PERMISSION_ID", generated=false, identity=false, nullable=true) String requiredPermissionId,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status
) {}
