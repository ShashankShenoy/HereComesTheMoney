package com.moneybags.cif.service;

import static com.moneybags.common.database.BusinessRepository.*;
import static com.moneybags.iam.repository.IamRepository.map;

import com.moneybags.cif.dto.CifDtos.DocumentReview;
import com.moneybags.common.api.*;
import com.moneybags.common.database.*;
import com.moneybags.common.security.PrivateData;
import com.moneybags.iam.security.UserPrincipal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/** Encrypted local evidence adapter. Storage can be replaced without changing the document API. */
@Service
public class KycDocumentService {

  public record Download(byte[] bytes, String mime, String filename) {}

  private final BusinessRepository db;
  private final CifService cif;
  private final PrivateData crypto;
  private final Path root;

  public KycDocumentService(
    BusinessRepository db,
    CifService cif,
    PrivateData crypto,
    @Value("${moneybags.documents.root:uploads/kyc}") String root
  ) {
    this.db = db;
    this.cif = cif;
    this.crypto = crypto;
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  /** Validates actual signatures, versions under a case lock, and compensates rolled-back writes. */
  @Transactional
  public Map<String, Object> upload(
    UserPrincipal user,
    String caseId,
    String type,
    MultipartFile file
  ) {
    var k = cif.caseAccess(user, caseId, "KYC_UPLOAD", true);
    CifService.draft(k, user);
    if (type == null || !type.matches("[A-Z][A-Z0-9_]{0,39}")) throw error(
      "INVALID_DOCUMENT_TYPE",
      "Use an uppercase document type"
    );
    if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) throw error(
      "INVALID_FILE_SIZE",
      "Upload between 1 byte and 5 MiB"
    );
    try {
      byte[] bytes = file.getBytes();
      String mime = mime(bytes);
      String id = UUID.randomUUID().toString();
      Files.createDirectories(root);
      Path path = root.resolve(id + ".enc");
      Files.write(path, crypto.encrypt(bytes), StandardOpenOption.CREATE_NEW);
      TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) try {
              Files.deleteIfExists(path);
            } catch (Exception ignored) {}
          }
        }
      );
      long version =
        db.count(
          "SELECT NVL(MAX(VERSION_NO),0) FROM M02_KYC_DOCUMENT WHERE CASE_ID=? AND DOCUMENT_TYPE=?",
          caseId,
          type
        ) +
        1;
      db.insert(
        SchemaTable.M02_KYC_DOCUMENT,
        map(
          "DOCUMENT_ID",
          id,
          "CASE_ID",
          caseId,
          "DOCUMENT_TYPE",
          type,
          "VERSION_NO",
          version,
          "STORAGE_REF",
          id + ".enc",
          "SHA256_HEX",
          PrivateData.hash(bytes),
          "MIME_TYPE",
          mime,
          "BYTE_SIZE",
          bytes.length,
          "UPLOADED_BY_USER_ID",
          user.userId()
        )
      );
      db
        .jdbc()
        .update(
          "UPDATE M02_KYC_CASE SET STATUS='DOCUMENTS_PENDING',ROW_VERSION=ROW_VERSION+1 WHERE CASE_ID=?",
          caseId
        );
      cif.event(user, "KycDocumentUploaded", str(k, "CIF_ID"), caseId, null);
      return map(
        "documentId",
        id,
        "versionNo",
        version,
        "scanStatus",
        "PENDING",
        "sha256",
        PrivateData.hash(bytes)
      );
    } catch (java.io.IOException e) {
      throw new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "DOCUMENT_STORAGE_UNAVAILABLE",
        "Check local evidence storage"
      );
    }
  }

  /** This explicit checker attestation is a college adapter, not an automated antivirus claim. */
  @Transactional
  public void verify(UserPrincipal user, String documentId, DocumentReview in) {
    var initial = db.one(
      "SELECT * FROM M02_KYC_DOCUMENT WHERE DOCUMENT_ID=?",
      documentId
    );
    var k = cif.caseAccess(user, str(initial, "CASE_ID"), "KYC_REVIEW", true);
    var d = db.one(
      "SELECT * FROM M02_KYC_DOCUMENT WHERE DOCUMENT_ID=? FOR UPDATE",
      documentId
    );
    if (
      user.userId().equals(str(k, "MAKER_USER_ID")) ||
      user.userId().equals(str(d, "UPLOADED_BY_USER_ID"))
    ) throw new BusinessException(
      HttpStatus.FORBIDDEN,
      "MAKER_CHECKER_SEPARATION",
      "An independent reviewer must attest evidence"
    );
    if (
      !Set.of("SUBMITTED", "UNDER_REVIEW").contains(str(k, "STATUS"))
    ) throw error(
      "INVALID_STATE",
      "Submit the case before independent evidence review"
    );
    if (
      k.get("ASSIGNED_OFFICER_USER_ID") != null &&
      !user.userId().equals(str(k, "ASSIGNED_OFFICER_USER_ID"))
    ) throw new BusinessException(
      HttpStatus.FORBIDDEN,
      "OFFICER_MISMATCH",
      "Only the assigned officer may review evidence"
    );
    if (
      !"CLEAN".equals(in.scanStatus()) &&
      "VERIFIED".equals(in.verificationStatus())
    ) throw error(
      "UNSAFE_DOCUMENT",
      "Quarantined or failed evidence cannot be verified"
    );
    db
      .jdbc()
      .update(
        "UPDATE M02_KYC_DOCUMENT SET SCAN_STATUS=?,VERIFICATION_STATUS=? WHERE DOCUMENT_ID=?",
        in.scanStatus(),
        in.verificationStatus(),
        documentId
      );
    cif.event(
      user,
      "KycDocumentReviewed",
      str(k, "CIF_ID"),
      str(k, "CASE_ID"),
      in.reason()
    );
  }

  /** Audits authorized downloads; ciphertext paths never come from the caller. */
  @Transactional
  public Download download(UserPrincipal user, String documentId) {
    var d = db.one(
      "SELECT * FROM M02_KYC_DOCUMENT WHERE DOCUMENT_ID=?",
      documentId
    );
    var k = cif.caseAccess(user, str(d, "CASE_ID"), "CIF_READ", false);
    boolean reviewer = false;
    if ("PENDING".equals(str(d, "SCAN_STATUS"))) {
      cif.caseAccess(user, str(d, "CASE_ID"), "KYC_REVIEW", false);
      reviewer = true;
    }
    if (
      !"CLEAN".equals(str(d, "SCAN_STATUS")) &&
      !("PENDING".equals(str(d, "SCAN_STATUS")) && reviewer)
    ) throw error(
      "DOCUMENT_NOT_CLEAN",
      "Evidence is quarantined, failed or awaiting reviewer attestation"
    );
    try {
      Path path = root.resolve(str(d, "STORAGE_REF")).normalize();
      if (!path.startsWith(root)) throw error(
        "INVALID_STORAGE_REF",
        "Evidence reference is invalid"
      );
      byte[] bytes = crypto.decrypt(Files.readAllBytes(path));
      if (!PrivateData.hash(bytes).equals(str(d, "SHA256_HEX"))) throw error(
        "DOCUMENT_INTEGRITY_FAILED",
        "Evidence content does not match its stored hash"
      );
      cif.event(
        user,
        "KycDocumentDownloaded",
        str(k, "CIF_ID"),
        str(k, "CASE_ID"),
        null
      );
      String mime = str(d, "MIME_TYPE");
      return new Download(
        bytes,
        mime,
        documentId +
          (mime.equals("application/pdf")
            ? ".pdf"
            : mime.equals("image/png")
              ? ".png"
              : ".jpg")
      );
    } catch (java.io.IOException e) {
      throw new BusinessException(
        HttpStatus.NOT_FOUND,
        "DOCUMENT_UNAVAILABLE",
        "Evidence file is unavailable"
      );
    }
  }

  /** Magic-byte validation prevents trusting an extension or submitted Content-Type. */
  public static String mime(byte[] b) {
    if (
      b.length > 5 &&
      b[0] == '%' &&
      b[1] == 'P' &&
      b[2] == 'D' &&
      b[3] == 'F' &&
      b[4] == '-'
    ) return "application/pdf";
    if (
      b.length > 8 &&
      Arrays.equals(
        Arrays.copyOf(b, 8),
        new byte[] { (byte) 137, 80, 78, 71, 13, 10, 26, 10 }
      )
    ) return "image/png";
    if (
      b.length > 3 &&
      b[0] == (byte) 255 &&
      b[1] == (byte) 216 &&
      b[2] == (byte) 255
    ) return "image/jpeg";
    throw error(
      "UNSUPPORTED_FILE",
      "Use PDF, PNG or JPEG evidence with a valid signature"
    );
  }

  private static BusinessException error(String code, String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
  }
}
