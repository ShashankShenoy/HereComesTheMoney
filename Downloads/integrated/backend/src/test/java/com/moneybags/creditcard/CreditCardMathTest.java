package com.moneybags.creditcard;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
class CreditCardMathTest {
    @Test void dailyInterestCarriesFractionsAndUsesActualDays(){
        var start=LocalDate.of(2024,2,1);var end=LocalDate.of(2024,3,1);
        assertEquals(new BigDecimal("19.06849315"),CreditCardMath.interest(new BigDecimal("1000"),new BigDecimal("24"),start,end));
        assertEquals(new BigDecimal("0.00000000"),CreditCardMath.interest(new BigDecimal("1000"),new BigDecimal("24"),start,start));
        assertThrows(IllegalArgumentException.class,()->CreditCardMath.interest(BigDecimal.ONE,BigDecimal.ONE,end,start));
    }
    @Test void minimumNeverExceedsDebtAndBillingNeverSkipsFebruary(){
        assertEquals(new BigDecimal("20.00"),CreditCardMath.minimum(new BigDecimal("20"),new BigDecimal("5"),new BigDecimal("200")));
        assertEquals(new BigDecimal("500.00"),CreditCardMath.minimum(new BigDecimal("10000"),new BigDecimal("5"),new BigDecimal("200")));
        assertEquals(new BigDecimal("0.00"),CreditCardMath.minimum(BigDecimal.ZERO,new BigDecimal("5"),new BigDecimal("200")));
        assertEquals(LocalDate.of(2025,2,28),CreditCardMath.nextBilling(LocalDate.of(2025,1,28),28));
        assertEquals(LocalDate.of(2025,1,28),CreditCardMath.nextBilling(LocalDate.of(2025,1,27),28));
    }
}
