package com.moneybags.common.security;

import com.moneybags.iam.security.UserPrincipal;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.slf4j.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Audit hook writes only actor, route and status, never submitted personal data or file content. */
public class DomainDeniedAuditFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(
    DomainDeniedAuditFilter.class
  );
  private final DomainDeniedAudit audit;
  private final boolean enabled;

  public DomainDeniedAuditFilter(DomainDeniedAudit audit, boolean enabled) {
    this.audit = audit;
    this.enabled = enabled;
  }

  @Override
  protected void doFilterInternal(
    HttpServletRequest r,
    HttpServletResponse s,
    FilterChain chain
  ) throws ServletException, IOException {
    chain.doFilter(r, s);
    if (
      !enabled ||
      s.getStatus() < 400 ||
      (!r.getRequestURI().startsWith("/api/v1/cif/") &&
        !r.getRequestURI().startsWith("/api/v1/products"))
    ) return;
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof UserPrincipal user) try {
      audit.record(user, r.getRequestURI(), s.getStatus());
    } catch (Exception e) {
      LOG.warn(
        "Domain rejection audit unavailable: {}",
        e.getClass().getSimpleName()
      );
    }
  }
}
