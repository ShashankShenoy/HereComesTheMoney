package com.moneybags.cif.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M02KycCaseRow(
    @DbColumn(name="CASE_ID", generated=false, identity=false, nullable=false) String caseId,
    @DbColumn(name="CIF_ID", generated=false, identity=false, nullable=false) String cifId,
    @DbColumn(name="CASE_TYPE", generated=false, identity=false, nullable=false) String caseType,
    @DbColumn(name="STATUS", generated=false, identity=false, nullable=false) String status,
    @DbColumn(name="MAKER_USER_ID", generated=false, identity=false, nullable=false) String makerUserId,
    @DbColumn(name="ASSIGNED_OFFICER_USER_ID", generated=false, identity=false, nullable=true) String assignedOfficerUserId,
    @DbColumn(name="RISK_LEVEL", generated=false, identity=false, nullable=true) String riskLevel,
    @DbColumn(name="SUBMITTED_AT", generated=false, identity=false, nullable=true) OffsetDateTime submittedAt,
    @DbColumn(name="DECIDED_AT", generated=false, identity=false, nullable=true) OffsetDateTime decidedAt,
    @DbColumn(name="REVIEW_DUE_AT", generated=false, identity=false, nullable=true) OffsetDateTime reviewDueAt,
    @DbColumn(name="CREATED_AT", generated=false, identity=false, nullable=false) OffsetDateTime createdAt,
    @DbColumn(name="ROW_VERSION", generated=false, identity=false, nullable=false) Long rowVersion
) {}
