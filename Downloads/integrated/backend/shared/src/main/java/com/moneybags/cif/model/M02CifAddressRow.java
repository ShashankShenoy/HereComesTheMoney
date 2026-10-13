package com.moneybags.cif.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M02CifAddressRow(
    @DbColumn(name="ADDRESS_ID", generated=false, identity=false, nullable=false) String addressId,
    @DbColumn(name="PARTY_ID", generated=false, identity=false, nullable=false) String partyId,
    @DbColumn(name="ADDRESS_TYPE", generated=false, identity=false, nullable=false) String addressType,
    @DbColumn(name="LINE1", generated=false, identity=false, nullable=false) String line1,
    @DbColumn(name="LINE2", generated=false, identity=false, nullable=true) String line2,
    @DbColumn(name="CITY", generated=false, identity=false, nullable=false) String city,
    @DbColumn(name="REGION", generated=false, identity=false, nullable=true) String region,
    @DbColumn(name="POSTAL_CODE", generated=false, identity=false, nullable=true) String postalCode,
    @DbColumn(name="COUNTRY_CODE", generated=false, identity=false, nullable=false) String countryCode,
    @DbColumn(name="VERIFICATION_STATUS", generated=false, identity=false, nullable=false) String verificationStatus,
    @DbColumn(name="EVIDENCE_DOCUMENT_ID", generated=false, identity=false, nullable=true) String evidenceDocumentId,
    @DbColumn(name="VALID_FROM", generated=false, identity=false, nullable=false) OffsetDateTime validFrom,
    @DbColumn(name="VALID_TO", generated=false, identity=false, nullable=true) OffsetDateTime validTo
) {}
