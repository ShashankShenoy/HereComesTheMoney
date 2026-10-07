package com.moneybags.creditcard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Disclosed demo policy: simple ACT/365 interest, no compounding or purchase grace period. */
public final class CreditCardMath {
    private CreditCardMath() {}
    public static BigDecimal interest(BigDecimal principal, BigDecimal apr, LocalDate from, LocalDate to) {
        long days=ChronoUnit.DAYS.between(from,to);
        if(days<0) throw new IllegalArgumentException("Interest dates must be chronological");
        return principal.multiply(apr).multiply(BigDecimal.valueOf(days))
            .divide(new BigDecimal("36500"),8,RoundingMode.HALF_EVEN);
    }
    public static BigDecimal cents(BigDecimal value) { return value.setScale(2,RoundingMode.HALF_EVEN); }
    public static BigDecimal minimum(BigDecimal balance, BigDecimal pct, BigDecimal floor) {
        return cents(balance.multiply(pct).divide(new BigDecimal("100"),8,RoundingMode.HALF_EVEN).max(floor).min(balance));
    }
    public static LocalDate nextBilling(LocalDate after, int day) {
        LocalDate next=after.withDayOfMonth(day);
        return next.isAfter(after)?next:next.plusMonths(1);
    }
}
