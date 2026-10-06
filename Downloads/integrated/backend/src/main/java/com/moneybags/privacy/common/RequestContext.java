package com.moneybags.privacy.common;

import java.util.Set;

/** Immutable IAM and tracing context passed into application services. */
public record RequestContext(
        String actorId,
        String sessionId,
        String correlationId,
        String idempotencyKey,
        Set<String> permissions) {

    /** Checks a permission at the application boundary for defense in depth. */
    public boolean hasPermission(String permission) {
        return permissions != null && permissions.contains(permission);
    }
}
