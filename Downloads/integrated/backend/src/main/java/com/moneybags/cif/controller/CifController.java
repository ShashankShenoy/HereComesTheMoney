package com.moneybags.cif.controller;

import com.moneybags.cif.dto.CifDtos.*;
import com.moneybags.cif.service.*;
import com.moneybags.common.api.*;
import com.moneybags.iam.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Versioned boundary shared by staff and customer clients; services enforce resource scope. */
@RestController
@RequestMapping("/api/v1/cif")
public class CifController {

  private final CifService cif;
  private final KycDocumentService docs;

  public CifController(CifService cif, KycDocumentService docs) {
    this.cif = cif;
    this.docs = docs;
  }

  @PostMapping("/reviews/expire-due")
  public ApiResponse<?> expire(@AuthenticationPrincipal UserPrincipal u) {
    return ApiResponse.of(cif.expireDue(u));
  }

  @GetMapping("/customers")
  public ApiResponse<?> list(
    @AuthenticationPrincipal UserPrincipal u,
    @RequestParam(defaultValue = "") String search,
    @RequestParam(defaultValue = "") String status
  ) {
    return ApiResponse.of(cif.list(u, search, status));
  }

  @PostMapping("/customers")
  public ApiResponse<?> create(
    @AuthenticationPrincipal UserPrincipal u,
    @Valid @RequestBody CustomerInput in
  ) {
    return ApiResponse.of(cif.create(u, in));
  }

  @GetMapping("/customers/{id}")
  public ApiResponse<?> detail(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id
  ) {
    return ApiResponse.of(cif.detail(u, id));
  }

  @PutMapping("/customers/{id}/profile")
  public ApiResponse<?> profile(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @Valid @RequestBody ProfileInput in
  ) {
    return ApiResponse.of(cif.profile(u, id, in));
  }

  @PostMapping("/customers/{id}/contacts")
  public ApiResponse<?> contact(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @Valid @RequestBody ContactInput in
  ) {
    cif.contact(u, id, in);
    return ApiResponse.of("Saved");
  }

  @PostMapping("/customers/{id}/identifiers")
  public ApiResponse<?> identifier(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @Valid @RequestBody IdentifierInput in
  ) {
    cif.identifier(u, id, in);
    return ApiResponse.of("Saved");
  }

  @PostMapping("/customers/{id}/addresses")
  public ApiResponse<?> address(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @Valid @RequestBody AddressInput in
  ) {
    cif.address(u, id, in);
    return ApiResponse.of("Saved");
  }

  @PatchMapping("/customers/{id}/status")
  public ApiResponse<?> status(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @Valid @RequestBody StatusInput in
  ) {
    return ApiResponse.of(cif.status(u, id, in));
  }

  @PostMapping("/customers/{id}/cases")
  public ApiResponse<?> open(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @Valid @RequestBody CaseInput in
  ) {
    return ApiResponse.of(cif.openCase(u, id, in));
  }

  @GetMapping("/cases/{id}")
  public ApiResponse<?> caseDetail(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id
  ) {
    return ApiResponse.of(cif.caseDetail(u, id));
  }

  @PostMapping("/cases/{id}/submit")
  public ApiResponse<?> submit(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @Valid @RequestBody Revision in
  ) {
    return ApiResponse.of(cif.submit(u, id, in));
  }

  @PostMapping("/cases/{id}/assign")
  public ApiResponse<?> assign(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @Valid @RequestBody Assignment in
  ) {
    return ApiResponse.of(cif.assign(u, id, in));
  }

  @PostMapping("/cases/{id}/review")
  public ApiResponse<?> review(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @Valid @RequestBody ReviewInput in
  ) {
    return ApiResponse.of(cif.review(u, id, in));
  }

  @PostMapping(
    value = "/cases/{id}/documents",
    consumes = MediaType.MULTIPART_FORM_DATA_VALUE
  )
  public ApiResponse<?> upload(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @RequestParam String documentType,
    @RequestPart MultipartFile file
  ) {
    return ApiResponse.of(docs.upload(u, id, documentType, file));
  }

  @PostMapping("/documents/{id}/review")
  public ApiResponse<?> verify(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @Valid @RequestBody DocumentReview in
  ) {
    docs.verify(u, id, in);
    return ApiResponse.of("Reviewed");
  }

  @GetMapping("/documents/{id}/content")
  public ResponseEntity<byte[]> download(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id
  ) {
    var file = docs.download(u, id);
    return ResponseEntity.ok()
      .contentType(MediaType.parseMediaType(file.mime()))
      .header(
        "Content-Disposition",
        "attachment; filename=\"" + file.filename() + "\""
      )
      .header("Cache-Control", "no-store")
      .body(file.bytes());
  }

  @PostMapping("/customers/{id}/consents")
  public ApiResponse<?> consent(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @Valid @RequestBody ConsentInput in
  ) {
    cif.consent(u, id, in);
    return ApiResponse.of("Captured");
  }

  @DeleteMapping("/customers/{id}/consents/{consent}")
  public ApiResponse<?> withdraw(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @PathVariable String consent
  ) {
    cif.withdrawConsent(u, id, consent);
    return ApiResponse.of("Withdrawn");
  }

  @PostMapping("/customers/{id}/relationships")
  public ApiResponse<?> relation(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @Valid @RequestBody RelationshipInput in
  ) {
    cif.relationship(u, id, in);
    return ApiResponse.of("Created");
  }

  @DeleteMapping("/customers/{id}/relationships/{relation}")
  public ApiResponse<?> end(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id,
    @PathVariable String relation
  ) {
    cif.endRelationship(u, id, relation);
    return ApiResponse.of("Ended");
  }

  @GetMapping("/customers/{id}/audit")
  public ApiResponse<?> audit(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id
  ) {
    return ApiResponse.of(cif.audit(u, id));
  }

  @GetMapping("/customers/{id}/eligibility-facts")
  public ApiResponse<?> facts(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable String id
  ) {
    return ApiResponse.of(cif.factsFor(u, id));
  }
}
