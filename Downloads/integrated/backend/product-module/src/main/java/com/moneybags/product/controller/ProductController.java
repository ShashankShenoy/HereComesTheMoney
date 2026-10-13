package com.moneybags.product.controller;

import com.moneybags.common.api.*;
import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.product.dto.ProductDtos.*;
import com.moneybags.product.service.*;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Product administration and read-only adoption APIs; money calculation remains downstream. */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

  private final ProductService products;

  public ProductController(ProductService products) {
    this.products = products;
  }

  @GetMapping
  public ApiResponse<?> list(
    @AuthenticationPrincipal UserPrincipal u,
    @RequestParam(defaultValue = "") String search,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.list(u, search, branch));
  }

  @GetMapping("/rule-schema")
  public ApiResponse<?> schema() {
    return ApiResponse.of(ProductRuleCatalog.FAMILIES.values());
  }

  @GetMapping("/customer-offers")
  public ApiResponse<?> customerOffers(
    @AuthenticationPrincipal UserPrincipal u,
    @RequestParam String cifId,
    @RequestParam(defaultValue = "BRANCH") String channel,
    @RequestParam(defaultValue = "INR") String currency
  ) {
    return ApiResponse.of(products.customerOffers(u, cifId, channel, currency));
  }

  @PostMapping
  public ApiResponse<?> create(
    @AuthenticationPrincipal UserPrincipal u,
    @Valid @RequestBody ProductInput in,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.create(u, in, branch));
  }

  @GetMapping("/{id}")
  public ApiResponse<?> detail(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.detail(u, id, branch));
  }

  @PostMapping("/{id}/versions")
  public ApiResponse<?> draft(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @Valid @RequestBody DraftInput in,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.draft(u, id, in, branch));
  }

  @GetMapping("/versions/{id}")
  public ApiResponse<?> version(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.versionDetail(u, id, branch));
  }

  @PutMapping("/versions/{id}")
  public ApiResponse<?> edit(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @Valid @RequestBody DraftEdit in,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.edit(u, id, in, branch));
  }

  @PostMapping("/versions/{id}/rules/{family}")
  public ApiResponse<?> rule(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @PathVariable String family,
    @Valid @RequestBody RuleInput in,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.addRule(u, id, family, in, branch));
  }

  @DeleteMapping("/versions/{id}/rules/{family}/{key}")
  public ApiResponse<?> remove(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @PathVariable String family,
    @PathVariable BigDecimal key,
    @RequestParam long rowVersion,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(
      products.deleteRule(u, id, family, key, rowVersion, branch)
    );
  }

  @PutMapping("/versions/{id}/rules/{family}/{key}")
  public ApiResponse<?> update(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @PathVariable String family,
    @PathVariable BigDecimal key,
    @Valid @RequestBody RuleInput in,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.updateRule(u, id, family, key, in, branch));
  }

  @PostMapping("/versions/{id}/submit")
  public ApiResponse<?> submit(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @Valid @RequestBody Revision in,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.submit(u, id, in, branch));
  }

  @PostMapping("/approvals/{id}/decision")
  public ApiResponse<?> decision(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @Valid @RequestBody Decision in,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.decide(u, id, in, branch));
  }

  @DeleteMapping("/approvals/{id}")
  public ApiResponse<?> withdraw(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @RequestParam long rowVersion,
    @RequestParam(required = false) String branch
  ) {
    products.withdraw(u, id, rowVersion, branch);
    return ApiResponse.of("Withdrawn");
  }

  @PostMapping("/versions/{id}/activate")
  public ApiResponse<?> activate(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @Valid @RequestBody Revision in,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.activate(u, id, in, branch));
  }

  @PostMapping("/{id}/lifecycle")
  public ApiResponse<?> lifecycle(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @RequestParam(required = false) BigDecimal versionId,
    @Valid @RequestBody Lifecycle in,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.lifecycle(u, id, versionId, in, branch));
  }

  @PostMapping("/{id}/treatments")
  public ApiResponse<?> treatment(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @Valid @RequestBody Treatment in,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.treatment(u, id, in, branch));
  }

  @GetMapping("/discover")
  public ApiResponse<?> discover(
    @AuthenticationPrincipal UserPrincipal u,
    @RequestParam String branch,
    @RequestParam(required = false) String segment,
    @RequestParam String channel,
    @RequestParam String currency,
    @RequestParam(required = false) OffsetDateTime at
  ) {
    return ApiResponse.of(
      products.discovery(
        u,
        branch,
        segment,
        channel,
        currency,
        at == null ? OffsetDateTime.now() : at
      )
    );
  }

  @PostMapping("/{id}/eligibility")
  public ApiResponse<?> eligibility(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @Valid @RequestBody Eligibility in
  ) {
    return ApiResponse.of(products.eligibility(u, id, in));
  }

  @GetMapping("/compare")
  public ApiResponse<?> compare(
    @AuthenticationPrincipal UserPrincipal u,
    @RequestParam BigDecimal left,
    @RequestParam BigDecimal right,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.compare(u, left, right, branch));
  }

  @GetMapping("/{id}/audit")
  public ApiResponse<?> audit(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @RequestParam(required = false) String branch
  ) {
    return ApiResponse.of(products.audit(u, id, branch));
  }

  @GetMapping("/{id}/effective")
  public ApiResponse<?> effective(
    @AuthenticationPrincipal UserPrincipal u,
    @PathVariable BigDecimal id,
    @RequestParam String branch,
    @RequestParam(required = false) String segment,
    @RequestParam String channel,
    @RequestParam String currency,
    @RequestParam(required = false) OffsetDateTime at
  ) {
    return ApiResponse.of(
      products.effectiveFor(
        u,
        id,
        branch,
        segment,
        channel,
        currency,
        at == null ? OffsetDateTime.now() : at
      )
    );
  }
}
