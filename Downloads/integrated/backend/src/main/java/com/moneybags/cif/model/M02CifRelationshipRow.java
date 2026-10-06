package com.moneybags.cif.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M02CifRelationshipRow(
    @DbColumn(name="RELATIONSHIP_ID", generated=false, identity=false, nullable=false) String relationshipId,
    @DbColumn(name="SOURCE_PARTY_ID", generated=false, identity=false, nullable=false) String sourcePartyId,
    @DbColumn(name="TARGET_PARTY_ID", generated=false, identity=false, nullable=false) String targetPartyId,
    @DbColumn(name="RELATIONSHIP_TYPE", generated=false, identity=false, nullable=false) String relationshipType,
    @DbColumn(name="OPERATING_AUTHORITY", generated=false, identity=false, nullable=false) String operatingAuthority,
    @DbColumn(name="VALID_FROM", generated=false, identity=false, nullable=false) OffsetDateTime validFrom,
    @DbColumn(name="VALID_TO", generated=false, identity=false, nullable=true) OffsetDateTime validTo
) {}
