package com.moneybags.cif.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M02CifIdentifierRow(
    @DbColumn(name="IDENTIFIER_ID", generated=false, identity=false, nullable=false) String identifierId,
    @DbColumn(name="PARTY_ID", generated=false, identity=false, nullable=false) String partyId,
    @DbColumn(name="IDENTIFIER_TYPE", generated=false, identity=false, nullable=false) String identifierType,
    @DbColumn(name="VALUE_CIPHERTEXT", generated=false, identity=false, nullable=false) byte[] valueCiphertext,
    @DbColumn(name="DISPLAY_HINT", generated=false, identity=false, nullable=false) String displayHint,
    @DbColumn(name="LOOKUP_HMAC", generated=false, identity=false, nullable=false) String lookupHmac,
    @DbColumn(name="ISSUER_CODE", generated=false, identity=false, nullable=true) String issuerCode,
    @DbColumn(name="EXPIRES_ON", generated=false, identity=false, nullable=true) LocalDate expiresOn,
    @DbColumn(name="VERIFICATION_STATUS", generated=false, identity=false, nullable=false) String verificationStatus,
    @DbColumn(name="VALID_FROM", generated=false, identity=false, nullable=false) OffsetDateTime validFrom,
    @DbColumn(name="VALID_TO", generated=false, identity=false, nullable=true) OffsetDateTime validTo
) {}
