package com.moneybags.integration;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Keeps contractual due dates while reducing future equal-principal installments. */
public final class LoanPrepaymentMath {
    private LoanPrepaymentMath() {}

    public static LocalDate periodStart(LocalDate originalStart,LocalDate due) {
        long month=ChronoUnit.MONTHS.between(YearMonth.from(originalStart),YearMonth.from(due));
        if(month<1 || !originalStart.plusMonths(month).equals(due))
            throw new IllegalArgumentException("Installment date does not match the contractual monthly schedule");
        return originalStart.plusMonths(month-1);
    }

    /** The existing 30/360 contract charges 30 days per monthly installment.
     * Split that same charge across actual days in its contractual month. */
    public static BigDecimal interest(BigDecimal principal,BigDecimal rate,LocalDate from,LocalDate through,
                                      String basis,LocalDate contractFrom,LocalDate contractThrough) {
        if(principal.signum()<0 || rate.signum()<0 || through.isBefore(from)
            || from.isBefore(contractFrom) || through.isAfter(contractThrough))
            throw new IllegalArgumentException("Invalid interest segment");
        long elapsed=ChronoUnit.DAYS.between(from,through);
        BigDecimal numerator=principal.multiply(rate).multiply(BigDecimal.valueOf(elapsed));
        BigDecimal denominator;
        if("30/360".equals(basis)) {
            long days=ChronoUnit.DAYS.between(contractFrom,contractThrough);
            if(days<=0)throw new IllegalArgumentException("Invalid interest period");
            numerator=numerator.multiply(BigDecimal.valueOf(30));
            denominator=BigDecimal.valueOf(36000).multiply(BigDecimal.valueOf(days));
        } else if(Set.of("ACT/365","ACT_365","ACTUAL_365").contains(basis))
            denominator=BigDecimal.valueOf(36500);
        else throw new IllegalArgumentException("Unsupported day-count basis");
        return numerator.divide(denominator,2,RoundingMode.HALF_EVEN);
    }

    public static List<LoanScheduleMath.Installment> reschedule(BigDecimal principal,BigDecimal rate,
            List<LocalDate> dates,LocalDate effective,LocalDate originalStart,String basis) {
        if(principal.signum()<=0 || dates.isEmpty())throw new IllegalArgumentException("A remaining principal and schedule are required");
        List<LoanScheduleMath.Installment> result=new ArrayList<>();
        BigDecimal regular=principal.divide(BigDecimal.valueOf(dates.size()),2,RoundingMode.DOWN),remaining=principal;
        LocalDate from=effective;
        for(int i=0;i<dates.size();i++) {
            LocalDate due=dates.get(i),contractFrom=periodStart(originalStart,due);
            if(!due.isAfter(from))throw new IllegalArgumentException("Due dates must increase after the payment date");
            BigDecimal paid=i==dates.size()-1?remaining:regular;
            result.add(new LoanScheduleMath.Installment(i+1,due,remaining,paid,
                interest(remaining,rate,from,due,basis,contractFrom,due)));
            remaining=remaining.subtract(paid);from=due;
        }
        return List.copyOf(result);
    }
}
