package com.moneybags.iam.security;

import com.moneybags.common.api.BusinessException;
import com.moneybags.iam.service.BankRolePolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.regex.Pattern;

/** A checker can inspect records and make named decisions, never invoke an
 * unrelated write route even when an older permission protects both verbs. */
@Component
public class BankCheckerWriteBoundary implements HandlerInterceptor, WebMvcConfigurer {
    private static final List<Pattern> DECISIONS = List.of(
        "/iam/access-requests/[^/]+/decision",
        "/products/approvals/[^/]+/decision",
        "/credit-cards/products/[^/]+/decision",
        "/credit-cards/applications/[^/]+/decision",
        "/transactions/[^/]+/reversal-decision",
        "/period-closes/[^/]+/decision",
        "/payments/approvals/[^/]+/decision",
        "/payments/[^/]+/authorize-simulation",
        "/treasury/work-items/[^/]+/decision",
        "/accounts/[^/]+/closures/[^/]+/(?:approve|reject)",
        "/accounts/[^/]+/majority-reviews/decisions",
        "/accounts/[^/]+/override-approvals/[^/]+/decision",
        "/cif/cases/[^/]+/review",
        "/cif/documents/[^/]+/review",
        "/loans/applications/[^/]+/decisions",
        "/loans/applications/[^/]+/documents/[^/]+/verify",
        "/loans/disbursements/[^/]+/approve",
        "/teller/tills/[^/]+/close",
        "/beneficiaries/[^/]+/verify",
        "/fx/rates/[^/]+/decision",
        "/privacy/purposes/[^/]+/approval",
        "/privacy/holds/[^/]+/(?:activation|release-approval)",
        "/privacy/evidence-exports/[^/]+/approval",
        "/catalog/masking-profiles/[^/]+/approve",
        "/catalog/narrations/[^/]+/approve"
    ).stream().map(Pattern::compile).toList();

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**");
    }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !(authentication.getPrincipal() instanceof UserPrincipal user)
            || !user.roles().contains(BankRolePolicy.CHECKER) || user.roles().contains("BANK_ADMIN")) return true;
        String method=request.getMethod();
        if(method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) return true;
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(path.equals("/api/v1/auth/logout") || path.equals("/api/v1/auth/refresh")
            || path.startsWith("/api/v1/auth/sessions/") || path.equals("/api/v1/iam/password")
            || path.equals("/api/v1/iam/step-up") || path.startsWith("/api/v1/iam/factors/")) return true;
        String businessPath=path.startsWith("/api/v1")?path.substring(7):path;
        if(method.equals("POST") && DECISIONS.stream().anyMatch(p->p.matcher(businessPath).matches())) return true;
        throw new BusinessException(HttpStatus.FORBIDDEN,"CHECKER_APPROVAL_ONLY",
            "Bank Checker may read records and submit independent decisions only");
    }
}
