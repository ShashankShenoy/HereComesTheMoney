package com.moneybags.cif.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M02CifContactRow(
    @DbColumn(name="CONTACT_ID", generated=false, identity=false, nullable=false) String contactId,
    @DbColumn(name="PARTY_ID", generated=false, identity=false, nullable=false) String partyId,
    @DbColumn(name="CONTACT_TYPE", generated=false, identity=false, nullable=false) String contactType,
    @DbColumn(name="VALUE_CIPHERTEXT", generated=false, identity=false, nullable=false) byte[] valueCiphertext,
    @DbColumn(name="DISPLAY_HINT", generated=false, identity=false, nullable=false) String displayHint,
    @DbColumn(name="VERIFICATION_STATUS", generated=false, identity=false, nullable=false) String verificationStatus,
    @DbColumn(name="VERIFIED_AT", generated=false, identity=false, nullable=true) OffsetDateTime verifiedAt,
    @DbColumn(name="VALID_FROM", generated=false, identity=false, nullable=false) OffsetDateTime validFrom,
    @DbColumn(name="VALID_TO", generated=false, identity=false, nullable=true) OffsetDateTime validTo
) {}
