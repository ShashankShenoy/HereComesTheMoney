package com.moneybags.cif.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M02CifPartyRow(
    @DbColumn(name="PARTY_ID", generated=false, identity=false, nullable=false) String partyId,
    @DbColumn(name="PARTY_TYPE", generated=false, identity=false, nullable=false) String partyType,
    @DbColumn(name="DATE_OF_BIRTH", generated=false, identity=false, nullable=true) LocalDate dateOfBirth,
    @DbColumn(name="INCORPORATED_ON", generated=false, identity=false, nullable=true) LocalDate incorporatedOn,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt,
    @DbColumn(name="ROW_VERSION", generated=false, identity=false, nullable=false) Long rowVersion
) {}
