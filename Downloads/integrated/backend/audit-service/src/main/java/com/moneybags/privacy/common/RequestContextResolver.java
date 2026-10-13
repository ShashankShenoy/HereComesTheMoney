package com.moneybags.privacy.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Converts HTTP and IAM authentication data into the context used by commands. */
@Component
public class RequestContextResolver {

    /** Resolves actor, session, correlation and idempotency values for one request. */
    public RequestContext resolve(HttpServletRequest request, Authentication authentication) {
        var actorId = authentication == null ? "anonymous" : authentication.getName();
        var sessionId = authentication != null && authentication.getPrincipal() instanceof com.moneybags.iam.security.UserPrincipal user ? user.sessionId() : null;
        var correlation = headerOrDefault(request, "X-Correlation-Id", UUID.randomUUID().toString());
        var idempotency = request.getHeader("Idempotency-Key");
        if (correlation.length() > 100 || (idempotency != null && idempotency.length() > 160)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_HEADER_TOO_LONG",
                    "Correlation or idempotency header exceeds its schema limit.");
        }
        Set<String> permissions = authentication == null ? Set.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet());
        return new RequestContext(actorId, sessionId, correlation, idempotency, permissions);
    }

    private String headerOrDefault(HttpServletRequest request, String name, String fallback) {
        var value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
