package com.moneybags.creditcard;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimulatedCardNumberTest {
    @Test void existingCardsHaveStableNumericDisplayNumbersMatchingTheirEndings() {
        String id="89837fa0-d79a-46e0-9384-fc106c227657";
        String number=SimulatedCardNumber.forCard(id,"5281");
        assertTrue(number.matches("[0-9]{16}"));
        assertTrue(number.endsWith("5281"));
        assertEquals(number,SimulatedCardNumber.forCard(id,"5281"));
        assertNotEquals(number,SimulatedCardNumber.forCard("89837fa0-d79a-46e0-9384-fc106c227658","5281"));
        assertTrue(SimulatedCardNumber.forCard(id,"0001").endsWith("0001"));
    }

    @Test void rejectsInternalReferencesAndInvalidEndings() {
        assertThrows(IllegalArgumentException.class,()->SimulatedCardNumber.forCard("SIM-internal","5281"));
        assertThrows(IllegalArgumentException.class,()->SimulatedCardNumber.forCard("89837fa0-d79a-46e0-9384-fc106c227657","281"));
    }
}
