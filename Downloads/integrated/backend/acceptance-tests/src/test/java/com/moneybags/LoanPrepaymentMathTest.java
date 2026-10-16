package com.moneybags;
import com.moneybags.integration.LoanPrepaymentMath;
import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LoanPrepaymentMathTest {
    @Test void actual365ChargesOnlyElapsedDays() {
        assertEquals(new BigDecimal("39.45"),LoanPrepaymentMath.interest(new BigDecimal("12000"),new BigDecimal("12"),LocalDate.of(2026,10,5),LocalDate.of(2026,10,15),"ACT_365",LocalDate.of(2026,10,5),LocalDate.of(2026,11,5)));
    }
    @Test void monthlyThirty360SplitsWithoutChangingContractualMonthlyCharge() {
        var from=LocalDate.of(2026,1,31);var end=LocalDate.of(2026,2,28);var split=LocalDate.of(2026,2,14);
        var principal=new BigDecimal("12000");var rate=new BigDecimal("12");
        assertEquals(new BigDecimal("120.00"),LoanPrepaymentMath.interest(principal,rate,from,end,"30/360",from,end));
        assertEquals(new BigDecimal("60.00"),LoanPrepaymentMath.interest(principal,rate,from,split,"30/360",from,end));
        assertEquals(new BigDecimal("60.00"),LoanPrepaymentMath.interest(principal,rate,split,end,"30/360",from,end));
    }
    @Test void monthEndAndLeapYearUseOriginalAnchor() {
        var start=LocalDate.of(2024,1,31);assertEquals(start,LoanPrepaymentMath.periodStart(start,LocalDate.of(2024,2,29)));
        assertEquals(LocalDate.of(2024,2,29),LoanPrepaymentMath.periodStart(start,LocalDate.of(2024,3,31)));
    }
    @Test void reschedulingPreservesDatesAndEveryPaiseOfPrincipal() {
        var start=LocalDate.of(2026,1,31);var dates=List.of(LocalDate.of(2026,2,28),LocalDate.of(2026,3,31),LocalDate.of(2026,4,30));
        var rows=LoanPrepaymentMath.reschedule(new BigDecimal("1000.01"),new BigDecimal("12"),dates,start.plusDays(10),start,"ACT/365");
        assertEquals(dates,rows.stream().map(r->r.due()).toList());assertEquals(new BigDecimal("1000.01"),rows.stream().map(r->r.principal()).reduce(BigDecimal.ZERO,BigDecimal::add));
        assertEquals(new BigDecimal("333.33"),rows.get(0).principal());assertEquals(new BigDecimal("333.35"),rows.get(2).principal());
    }
    @Test void repeatedPartialPaymentDoesNotReaccrueThePreviousSegment() {
        var start=LocalDate.of(2026,10,1);var end=LocalDate.of(2026,11,1);
        var later=LoanPrepaymentMath.interest(new BigDecimal("10000"),new BigDecimal("12"),start.plusDays(10),start.plusDays(20),"ACT/365",start,end);
        assertEquals(new BigDecimal("32.88"),later);
        assertEquals(new BigDecimal("0.00"),LoanPrepaymentMath.interest(new BigDecimal("10000"),new BigDecimal("12"),start.plusDays(10),start.plusDays(10),"ACT/365",start,end));
    }
    @Test void invalidSegmentsCannotGenerateInterestOrSchedules() {
        var start=LocalDate.of(2026,10,1);var end=start.plusMonths(1);
        assertThrows(IllegalArgumentException.class,()->LoanPrepaymentMath.interest(BigDecimal.ONE,BigDecimal.TEN,start,start.minusDays(1),"ACT/365",start,end));
        assertThrows(IllegalArgumentException.class,()->LoanPrepaymentMath.periodStart(start,end.plusDays(1)));
        assertThrows(IllegalArgumentException.class,()->LoanPrepaymentMath.reschedule(BigDecimal.ONE,BigDecimal.TEN,List.of(start),start,start,"ACT/365"));
    }
}
