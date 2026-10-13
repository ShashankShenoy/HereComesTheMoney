package com.moneybags.treasury.config;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Bridges Module 1 permissions to method authorization, with an explicit local-only bypass. */
@Component("treasuryAccess")
public class TreasuryAccess {
    private final TreasuryProperties properties;

    public TreasuryAccess(TreasuryProperties properties) { this.properties = properties; }

    /** Accepts either a direct permission authority or OAuth's conventional SCOPE_ authority. */
    public boolean allowed(Authentication authentication, String permission) {
        return authentication != null && authentication.getAuthorities().stream().anyMatch(authority ->
            authority.getAuthority().equals(permission) || authority.getAuthority().equals("SCOPE_" + permission));
    }
}
