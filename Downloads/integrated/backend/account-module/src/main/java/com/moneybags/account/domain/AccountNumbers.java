package com.moneybags.account.domain;

import java.util.Locale;

/** Builds an 18-character account number from bounded prefixes and a database sequence. */
public final class AccountNumbers {
    private AccountNumbers() { }

    /** Produces MB + 2-character branch + product marker + 12 digits + check digit. */
    public static String format(String branch, String productType, long sequence) {
        if (sequence < 0 || sequence > 999_999_999_999L) throw new IllegalArgumentException("Account number sequence exhausted");
        String normalized = branch.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (normalized.length() < 2) throw new IllegalArgumentException("Branch code needs two alphanumeric characters");
        char product = switch (productType) { case "SAVINGS" -> 'S'; case "CURRENT" -> 'C'; default -> throw new IllegalArgumentException("Unsupported account product"); };
        String digits = "%012d".formatted(sequence);
        return "MB" + normalized.substring(0, 2) + product + digits + checkDigit(digits);
    }

    /** Computes a Luhn check digit for the numeric sequence part. */
    static int checkDigit(String digits) {
        int sum = 0; boolean doubleIt = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (doubleIt) { n *= 2; if (n > 9) n -= 9; }
            sum += n; doubleIt = !doubleIt;
        }
        return (10 - sum % 10) % 10;
    }
}
