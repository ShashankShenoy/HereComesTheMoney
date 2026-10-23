package com.moneybags.creditcard;

import java.math.BigInteger;
import java.util.Locale;
import java.util.UUID;

/** Stable display number for local simulated cards, not an issuer-assigned payment PAN. */
public final class SimulatedCardNumber {
    private SimulatedCardNumber() {}

    public static String forCard(String cardId, String ending) {
        UUID.fromString(cardId);
        if (ending == null || !ending.matches("[0-9]{4}")) {
            throw new IllegalArgumentException("A four-digit card ending is required");
        }
        var digits = new BigInteger(cardId.replace("-", ""), 16)
            .mod(BigInteger.valueOf(100_000_000L));
        // Derive from the persisted identity so existing cards need no data migration.
        return "9999" + String.format(Locale.ROOT, "%08d", digits) + ending;
    }
}
