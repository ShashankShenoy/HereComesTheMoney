package com.moneybags.iam.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M01IamUserRoleRow(
    @DbColumn(name="ASSIGNMENT_ID", generated=false, identity=false, nullable=false) String assignmentId,
    @DbColumn(name="USER_ID", generated=false, identity=false, nullable=false) String userId,
    @DbColumn(name="ROLE_ID", generated=false, identity=false, nullable=false) String roleId,
    @DbColumn(name="SCOPE_TYPE", generated=false, identity=false, nullable=false) String scopeType,
    @DbColumn(name="SCOPE_REF", generated=false, identity=false, nullable=true) String scopeRef,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="VALID_FROM", generated=false, identity=false, nullable=false) OffsetDateTime validFrom,
    @DbColumn(name="VALID_TO", generated=false, identity=false, nullable=true) OffsetDateTime validTo,
    @DbColumn(name="APPROVED_REQUEST_ID", generated=false, identity=false, nullable=true) String approvedRequestId,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt
) {}
