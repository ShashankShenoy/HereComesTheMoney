package com.moneybags.payments.core;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Resolves the verified Module 1 identity for immutable histories and audit events. */
public final class Actor {
    private Actor() { }

    /** Returns the IAM actor identifier, never a client-supplied body field. */
    public static String id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "SYSTEM" : auth.getName();
    }

    /** Maps a service scope to the history actor classification. */
    public static String type() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return "SERVICE";
        if (has(auth, "SCOPE_M06_RAIL")) return "RAIL";
        if (has(auth, "SCOPE_M06_TREASURY")) return "TREASURY";
        if (has(auth, "SCOPE_M06_OPERATIONS")) return "OPERATIONS";
        return "USER";
    }

    /** Checks one authority without assuming a specific IAM token format. */
    private static boolean has(Authentication auth, String authority) {
        return auth.getAuthorities().stream().anyMatch(item -> authority.equals(item.getAuthority()));
    }
}

