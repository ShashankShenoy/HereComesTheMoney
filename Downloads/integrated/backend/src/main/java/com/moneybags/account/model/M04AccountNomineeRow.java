package com.moneybags.account.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M04AccountNomineeRow(
    @DbColumn(name="NOMINEE_ID", generated=true, identity=true, nullable=false) BigDecimal nomineeId,
    @DbColumn(name="ACCOUNT_ID", generated=false, identity=false, nullable=false) BigDecimal accountId,
    @DbColumn(name="NOMINEE_CIF_ID", generated=false, identity=false, nullable=true) String nomineeCifId,
    @DbColumn(name="NOMINEE_NAME", generated=false, identity=false, nullable=false) String nomineeName,
    @DbColumn(name="RELATIONSHIP", generated=false, identity=false, nullable=false) String relationship,
    @DbColumn(name="DATE_OF_BIRTH", generated=false, identity=false, nullable=true) LocalDate dateOfBirth,
    @DbColumn(name="MOBILE_NUMBER", generated=false, identity=false, nullable=true) String mobileNumber,
    @DbColumn(name="ADDRESS_LINE1", generated=false, identity=false, nullable=true) String addressLine1,
    @DbColumn(name="ADDRESS_LINE2", generated=false, identity=false, nullable=true) String addressLine2,
    @DbColumn(name="CITY", generated=false, identity=false, nullable=true) String city,
    @DbColumn(name="STATE", generated=false, identity=false, nullable=true) String state,
    @DbColumn(name="POSTAL_CODE", generated=false, identity=false, nullable=true) String postalCode,
    @DbColumn(name="SHARE_PERCENTAGE", generated=false, identity=false, nullable=false) BigDecimal sharePercentage,
    @DbColumn(name="GUARDIAN_NAME", generated=false, identity=false, nullable=true) String guardianName,
    @DbColumn(name="GUARDIAN_RELATIONSHIP", generated=false, identity=false, nullable=true) String guardianRelationship,
    @DbColumn(name="IS_ACTIVE", generated=false, identity=false, nullable=false) String isActive,
    @DbColumn(name="CREATED_BY_USER_ID", generated=false, identity=false, nullable=false) String createdByUserId,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt
) {}
