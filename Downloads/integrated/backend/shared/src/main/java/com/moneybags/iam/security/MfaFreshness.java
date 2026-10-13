package com.moneybags.iam.security;

import java.time.Instant;

public final class MfaFreshness {
    private MfaFreshness() {}
    public static boolean fresh(String level, Instant instant) {
        if (level == null || !level.startsWith("MFA@")) return false;
        try {
            long age = instant.getEpochSecond() - Long.parseLong(level.substring(4));
            return age >= 0 && age <= 300;
        } catch (NumberFormatException invalid) { return false; }
    }
}
