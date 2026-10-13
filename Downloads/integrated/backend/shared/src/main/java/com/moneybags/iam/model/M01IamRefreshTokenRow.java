package com.moneybags.iam.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M01IamRefreshTokenRow(
    @DbColumn(name="TOKEN_ID", generated=false, identity=false, nullable=false) String tokenId,
    @DbColumn(name="SESSION_ID", generated=false, identity=false, nullable=false) String sessionId,
    @DbColumn(name="FAMILY_ID", generated=false, identity=false, nullable=false) String familyId,
    @DbColumn(name="TOKEN_HASH", generated=false, identity=false, nullable=false) String tokenHash,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="ISSUED_AT", generated=false, identity=false, nullable=false) OffsetDateTime issuedAt,
    @DbColumn(name="EXPIRES_AT", generated=false, identity=false, nullable=false) OffsetDateTime expiresAt,
    @DbColumn(name="USED_AT", generated=false, identity=false, nullable=true) OffsetDateTime usedAt,
    @DbColumn(name="REPLACED_BY_TOKEN_ID", generated=false, identity=false, nullable=true) String replacedByTokenId
) {}
