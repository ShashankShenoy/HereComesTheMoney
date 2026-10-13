package com.moneybags.payments.core;

import java.math.BigDecimal;
import java.time.*;
import java.util.Set;

/** Agreed demonstration policies, independent of actual NPCI schedules. */
public final class SimulationPolicy {
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private SimulationPolicy() {}
    public static void validate(String rail, BigDecimal amount) {
        if (!Set.of("UPI","IMPS","NEFT","RTGS").contains(rail) || amount == null
                || amount.signum() <= 0 || amount.scale() > 2) throw new IllegalArgumentException("Invalid rail or amount");
        if ("UPI".equals(rail) && amount.compareTo(new BigDecimal("100000")) > 0)
            throw new IllegalArgumentException("UPI maximum is INR 100000");
        if ("IMPS".equals(rail) && amount.compareTo(new BigDecimal("500000")) > 0)
            throw new IllegalArgumentException("IMPS maximum is INR 500000");
        if ("RTGS".equals(rail) && amount.compareTo(new BigDecimal("200000")) < 0)
            throw new IllegalArgumentException("RTGS minimum is INR 200000");
    }
    public static boolean approvalRequired(String rail, BigDecimal amount) {
        validate(rail, amount);
        return !"UPI".equals(rail) && amount.compareTo(new BigDecimal("RTGS".equals(rail) ? "1000000" : "100000")) > 0;
    }
    public static boolean immediateCredit(String rail) { return Set.of("UPI","IMPS").contains(rail); }
    public record Window(OffsetDateTime start, OffsetDateTime end) {}
    public static Window window(String rail, Instant instant) {
        if ("RTGS".equals(rail)) throw new IllegalArgumentException("RTGS settles individually");
        if (!Set.of("UPI","IMPS","NEFT").contains(rail)) throw new IllegalArgumentException("Unknown rail");
        int minutes = "NEFT".equals(rail) ? 30 : 120;
        ZonedDateTime now = instant.atZone(ZONE);
        ZonedDateTime start = now.toLocalDate().atStartOfDay(ZONE).plusMinutes((now.getHour()*60+now.getMinute())/minutes*minutes);
        return new Window(start.toOffsetDateTime(), start.plusMinutes(minutes).toOffsetDateTime());
    }
}
