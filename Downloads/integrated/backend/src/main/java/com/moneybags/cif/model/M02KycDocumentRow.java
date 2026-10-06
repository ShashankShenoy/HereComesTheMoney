package com.moneybags.cif.model;

import com.moneybags.common.database.DbColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Generated from the supplied Oracle schema, including ALTER TABLE additions.
 * Persistence model only; expose a separate DTO to avoid returning sensitive fields. */
public record M02KycDocumentRow(
    @DbColumn(name="DOCUMENT_ID", generated=false, identity=false, nullable=false) String documentId,
    @DbColumn(name="CASE_ID", generated=false, identity=false, nullable=false) String caseId,
    @DbColumn(name="DOCUMENT_TYPE", generated=false, identity=false, nullable=false) String documentType,
    @DbColumn(name="VERSION_NO", generated=false, identity=false, nullable=false) Long versionNo,
    @DbColumn(name="STORAGE_REF", generated=false, identity=false, nullable=false) String storageRef,
    @DbColumn(name="SHA256_HEX", generated=false, identity=false, nullable=false) String sha256Hex,
    @DbColumn(name="MIME_TYPE", generated=false, identity=false, nullable=false) String mimeType,
    @DbColumn(name="BYTE_SIZE", generated=false, identity=false, nullable=false) Long byteSize,
    @DbColumn(name="SCAN_STATUS", generated=false, identity=false, nullable=false) String scanStatus,
    @DbColumn(name="VERIFICATION_STATUS", generated=false, identity=false, nullable=false) String verificationStatus,
    @DbColumn(name="UPLOADED_BY_USER_ID", generated=false, identity=false, nullable=false) String uploadedByUserId,
    @DbColumn(name="UPLOADED_AT", generated=false, identity=false, nullable=false) OffsetDateTime uploadedAt
) {}
