package com.moneybags.cif.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M02CifNameRow(
    @DbColumn(name="NAME_ID", generated=false, identity=false, nullable=false) String nameId,
    @DbColumn(name="PARTY_ID", generated=false, identity=false, nullable=false) String partyId,
    @DbColumn(name="NAME_TYPE", generated=false, identity=false, nullable=false) String nameType,
    @DbColumn(name="FULL_NAME", generated=false, identity=false, nullable=false) String fullName,
    @DbColumn(name="VERIFICATION_STATUS", generated=false, identity=false, nullable=false) String verificationStatus,
    @DbColumn(name="VALID_FROM", generated=false, identity=false, nullable=false) OffsetDateTime validFrom,
    @DbColumn(name="VALID_TO", generated=false, identity=false, nullable=true) OffsetDateTime validTo
) {}
