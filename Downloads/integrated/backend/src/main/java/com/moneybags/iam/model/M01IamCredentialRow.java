package com.moneybags.iam.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M01IamCredentialRow(
    @DbColumn(name="CREDENTIAL_ID", generated=false, identity=false, nullable=false) String credentialId,
    @DbColumn(name="USER_ID", generated=false, identity=false, nullable=false) String userId,
    @DbColumn(name="PASSWORD_HASH", generated=false, identity=false, nullable=false) String passwordHash,
    @DbColumn(name="HASH_SCHEME", generated=false, identity=false, nullable=false) String hashScheme,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="FAILED_ATTEMPTS", generated=false, identity=false, nullable=false) Long failedAttempts,
    @DbColumn(name="LOCKED_UNTIL", generated=false, identity=false, nullable=true) OffsetDateTime lockedUntil,
    @DbColumn(name="CHANGED_AT", generated=false, identity=false, nullable=false) OffsetDateTime changedAt
) {}
