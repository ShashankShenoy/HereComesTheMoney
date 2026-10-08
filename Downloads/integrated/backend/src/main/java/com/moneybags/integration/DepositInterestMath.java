package com.moneybags.integration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** Exact decimal calculations shared by savings payouts and fixed deposits. */
final class DepositInterestMath {
    private DepositInterestMath() { }

    static BigDecimal daily(BigDecimal balance, BigDecimal annualRatePct, LocalDate date, String basis) {
        if (balance.signum() <= 0 || annualRatePct.signum() == 0) return BigDecimal.ZERO;
        int days = switch (basis) {
            case "ACT_365", "ACT/365" -> 365;
            case "ACT_360", "ACT/360" -> 360;
            case "ACT_ACT", "ACT/ACT" -> date.isLeapYear() ? 366 : 365;
            default -> throw new IllegalArgumentException("Unsupported deposit day-count basis: " + basis);
        };
        return balance.multiply(annualRatePct)
                .divide(BigDecimal.valueOf(100L * days), 12, RoundingMode.HALF_EVEN);
    }

    static BigDecimal fixedTerm(BigDecimal principal, BigDecimal annualRatePct,
                                LocalDate start, LocalDate maturity, String basis, String rounding) {
        if (!maturity.isAfter(start)) throw new IllegalArgumentException("Maturity must follow opening");
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDate day = start; day.isBefore(maturity); day = day.plusDays(1))
            total = total.add(daily(principal, annualRatePct, day, basis));
        return total.setScale(2, rounding(rounding));
    }

    static RoundingMode rounding(String name) {
        return switch (name) {
            case "HALF_UP" -> RoundingMode.HALF_UP;
            case "HALF_EVEN" -> RoundingMode.HALF_EVEN;
            case "DOWN" -> RoundingMode.DOWN;
            case "UP" -> RoundingMode.UP;
            default -> throw new IllegalArgumentException("Unsupported deposit rounding mode: " + name);
        };
    }
}
