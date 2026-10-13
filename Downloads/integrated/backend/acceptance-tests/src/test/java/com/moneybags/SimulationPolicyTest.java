package com.moneybags;
import com.moneybags.payments.core.SimulationPolicy;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SimulationPolicyTest {
 @Test void approvalBoundaries(){
  for(String rail:new String[]{"IMPS","NEFT"}){assertFalse(SimulationPolicy.approvalRequired(rail,new BigDecimal("100000")));assertTrue(SimulationPolicy.approvalRequired(rail,new BigDecimal("100000.01")));}
  assertFalse(SimulationPolicy.approvalRequired("UPI",new BigDecimal("100000")));
  assertFalse(SimulationPolicy.approvalRequired("RTGS",new BigDecimal("1000000")));assertTrue(SimulationPolicy.approvalRequired("RTGS",new BigDecimal("1000000.01")));
  assertDoesNotThrow(()->SimulationPolicy.validate("IMPS",new BigDecimal("500000")));assertThrows(IllegalArgumentException.class,()->SimulationPolicy.validate("IMPS",new BigDecimal("500000.01")));
  assertThrows(IllegalArgumentException.class,()->SimulationPolicy.validate("RTGS",new BigDecimal("199999.99")));assertDoesNotThrow(()->SimulationPolicy.validate("RTGS",new BigDecimal("200000")));
 }
 @Test void windowsAlignToKolkataMidnight(){
  var instant=Instant.parse("2026-10-13T20:15:00Z");
  var upi=SimulationPolicy.window("UPI",instant);assertEquals("2026-10-14T00:00+05:30",upi.start().toString());assertEquals("2026-10-14T02:00+05:30",upi.end().toString());
  var neft=SimulationPolicy.window("NEFT",instant);assertEquals("2026-10-14T01:30+05:30",neft.start().toString());assertEquals("2026-10-14T02:00+05:30",neft.end().toString());
  assertThrows(IllegalArgumentException.class,()->SimulationPolicy.window("RTGS",instant));
 }
}
