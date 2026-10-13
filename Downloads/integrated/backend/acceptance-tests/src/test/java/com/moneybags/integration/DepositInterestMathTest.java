package com.moneybags.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DepositInterestMathTest {
    @Test void actualActualUsesLeapYearDenominator() {
        BigDecimal earned=DepositInterestMath.fixedTerm(new BigDecimal("36600"),new BigDecimal("10"),
                LocalDate.of(2024,1,1),LocalDate.of(2025,1,1),"ACT_ACT","HALF_EVEN");
        assertEquals(new BigDecimal("3660.00"),earned);
    }

    @Test void termDoesNotCompoundBeforeMaturity() {
        BigDecimal earned=DepositInterestMath.fixedTerm(new BigDecimal("36500"),new BigDecimal("10"),
                LocalDate.of(2025,1,1),LocalDate.of(2026,1,1),"ACT_365","HALF_EVEN");
        assertEquals(new BigDecimal("3650.00"),earned);
    }
}
