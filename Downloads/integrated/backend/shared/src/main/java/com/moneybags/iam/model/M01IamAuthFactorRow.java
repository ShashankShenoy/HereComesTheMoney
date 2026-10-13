package com.moneybags.iam.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M01IamAuthFactorRow(
    @DbColumn(name="FACTOR_ID", generated=false, identity=false, nullable=false) String factorId,
    @DbColumn(name="USER_ID", generated=false, identity=false, nullable=false) String userId,
    @DbColumn(name="FACTOR_TYPE", generated=false, identity=false, nullable=false) String factorType,
    @DbColumn(name="FACTOR_REF", generated=false, identity=false, nullable=false) String factorRef,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="ENROLLED_AT", generated=false, identity=false, nullable=false) OffsetDateTime enrolledAt,
    @DbColumn(name="VERIFIED_AT", generated=false, identity=false, nullable=true) OffsetDateTime verifiedAt,
    @DbColumn(name="REVOKED_AT", generated=false, identity=false, nullable=true) OffsetDateTime revokedAt
) {}
