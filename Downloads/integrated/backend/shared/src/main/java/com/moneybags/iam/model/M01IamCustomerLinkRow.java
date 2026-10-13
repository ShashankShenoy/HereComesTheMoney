package com.moneybags.iam.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M01IamCustomerLinkRow(
    @DbColumn(name="LINK_ID", generated=false, identity=false, nullable=false) String linkId,
    @DbColumn(name="USER_ID", generated=false, identity=false, nullable=false) String userId,
    @DbColumn(name="CIF_ID", generated=false, identity=false, nullable=false) String cifId,
    @DbColumn(name="RELATIONSHIP_TYPE", generated=false, identity=false, nullable=false) String relationshipType,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="VALID_FROM", generated=false, identity=false, nullable=false) OffsetDateTime validFrom,
    @DbColumn(name="VALID_FROM_UTC", generated=true, identity=false, nullable=true) LocalDateTime validFromUtc,
    @DbColumn(name="VALID_TO", generated=false, identity=false, nullable=true) OffsetDateTime validTo
) {}
