package com.moneybags.iam.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M01IamUserRow(
    @DbColumn(name="USER_ID", generated=false, identity=false, nullable=false) String userId,
    @DbColumn(name="USERNAME", generated=false, identity=false, nullable=false) String username,
    @DbColumn(name="USER_TYPE", generated=false, identity=false, nullable=false) String userType,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="EMPLOYEE_REF", generated=false, identity=false, nullable=true) String employeeRef,
    @DbColumn(name="ENTITLEMENT_VERSION", generated=false, identity=false, nullable=false) Long entitlementVersion,
    @DbColumn(name="ROW_VERSION", generated=false, identity=false, nullable=false) Long rowVersion,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt,
    @DbColumn(name="UPDATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime updatedAt
) {}
