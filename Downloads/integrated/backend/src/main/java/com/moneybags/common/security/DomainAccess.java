package com.moneybags.common.security;

import com.moneybags.iam.dto.IamDtos.AuthorizationInput;
import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.iam.service.AccessDecisionService;
import org.springframework.stereotype.Service;

/** Adapter between domain resource context and the existing IAM policy evaluator. */
@Service
public class DomainAccess {

  private final AccessDecisionService access;

  public DomainAccess(AccessDecisionService access) {
    this.access = access;
  }

  private AuthorizationInput context(
    String permission,
    String branch,
    String cif,
    String product,
    String currency,
    String maker
  ) {
    return new AuthorizationInput(
      permission,
      branch,
      null,
      cif,
      product,
      currency,
      null,
      null,
      null,
      maker
    );
  }

  /** Filters directories without generating an audit event for every candidate row. */
  public boolean allowed(
    UserPrincipal user,
    String permission,
    String branch,
    String cif,
    String product,
    String currency
  ) {
    return access.allowed(
      user,
      context(permission, branch, cif, product, currency, null)
    );
  }

  /** Enforces permission, resource scope and independent approval with an audit decision. */
  public void require(
    UserPrincipal user,
    String permission,
    String branch,
    String cif,
    String product,
    String currency,
    String maker
  ) {
    access.require(
      user,
      context(permission, branch, cif, product, currency, maker)
    );
  }
}
