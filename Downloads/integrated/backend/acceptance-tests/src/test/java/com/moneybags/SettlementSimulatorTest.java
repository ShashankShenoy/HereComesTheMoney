package com.moneybags;
import com.fasterxml.jackson.databind.*;
import com.moneybags.payments.core.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:settlement;MODE=Oracle;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH","moneybags.payment-simulation.clearing-enabled=true","moneybags.payment-simulation.poll-ms=3600000"})
@AutoConfigureMockMvc @ActiveProfiles("local") @Transactional
class SettlementSimulatorTest {
 @TestConfiguration static class TimeConfig {@Bean @Primary TestClock simulatorClock(){return new TestClock();}}
 static class TestClock extends Clock {Instant time=Instant.now();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return Clock.fixed(time,z);}public Instant instant(){return time;}}
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired TestClock clock;@Autowired SimulationScheduler scheduler;
 String admin,checker,customer,beneficiary;
 @BeforeEach void setup()throws Exception{
  clock.time=Instant.now();admin=login("admin");checker=login("checker");customer=login("customer");
  beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","HDFC recipient","accountToken","EXT-"+UUID.randomUUID(),"bankCode","HDFC0000001")).andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
 }
 String login(String name)throws Exception{return value(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",name,"password","LocalBanking!2026","clientId","MONEYBAGS_WEB")))).andExpect(status().isOk())).path("data").path("accessToken").asText();}
 ResultActions send(String path,String token,Object body)throws Exception{return mvc.perform(post("/api/v1"+path).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));}
 JsonNode value(ResultActions r)throws Exception{return json.readTree(r.andReturn().getResponse().getContentAsString());}
 BigDecimal balance(){return db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=1",BigDecimal.class);}
 BigDecimal reserve(){return db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class);}
 Map<String,Object> incoming(String rail,String amount,String reference){return Map.of("sendingBank","SBI","destinationAccountNumber","MB000000001","destinationBranch","MUM001","destinationIfsc","MCPB0000002","railCode",rail,"amount",amount,"externalReference",reference);}
 JsonNode outgoing(String rail,String amount)throws Exception{return value(send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,"railCode",rail,"amount",amount,"requestKey",UUID.randomUUID().toString())).andExpect(status().isOk()));}
 void settle(long cycle)throws Exception{send("/payments/simulator/cycles/"+cycle+"/settle",admin,Map.of()).andExpect(status().isOk());}
 void fund()throws Exception{send("/payments/simulator/incoming",admin,incoming("RTGS","3000000",UUID.randomUUID().toString())).andExpect(status().isOk());}
 @Test void automaticOutgoingAndCycleRetryHaveOneDebitAndBalancedReserves()throws Exception{
  BigDecimal before=balance(),r=reserve();var p=outgoing("UPI","10");long id=p.path("paymentId").asLong(),cycle=p.path("settlementCycleId").asLong();
  assertEquals("SETTLEMENT_PENDING",p.path("status").asText());assertEquals(0,before.subtract(new BigDecimal("10")).compareTo(balance()));assertEquals(0,r.compareTo(reserve()));
  assertTrue(cycle>0);settle(cycle);settle(cycle);
  assertEquals(0,r.subtract(new BigDecimal("10")).compareTo(reserve()));
  assertEquals("SETTLED",db.queryForObject("SELECT STATUS FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",String.class,id));
  assertEquals(0,db.queryForObject("SELECT SUM(NET_AMOUNT) FROM MBX_SIM_MOVEMENT WHERE SETTLEMENT_REFERENCE=?",BigDecimal.class,"SIM-C-"+cycle).signum());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM MBX_SIM_SETTLEMENT WHERE SETTLEMENT_CYCLE_ID=?",Integer.class,cycle));
  send("/payments/"+id+"/simulate-outcome",admin,Map.of("outcome","SETTLED")).andExpect(status().isConflict());
  var next=outgoing("UPI","1");assertNotEquals(cycle,next.path("settlementCycleId").asLong());
 }
 @Test void approvalBoundariesAndRejectionKeepUnapprovedPaymentsOutsideClearing()throws Exception{
  fund();for(String rail:List.of("IMPS","NEFT","RTGS")){
   String auto=rail.equals("RTGS")?"1000000":"100000",pending=rail.equals("RTGS")?"1000000.01":"100000.01";
   var accepted=outgoing(rail,auto);assertNotEquals("RECEIVED",accepted.path("status").asText());
   var review=outgoing(rail,pending);long id=review.path("paymentId").asLong();assertEquals("RECEIVED",review.path("status").asText());assertTrue(review.path("clearingBatchId").isNull());
   send("/payments/"+id+"/authorize-simulation",customer,Map.of("reserveAccountId",1)).andExpect(status().isForbidden());
   send("/payments/simulator/payments/"+id+"/reject",checker,Map.of()).andExpect(status().isOk());
   assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M06_CLEARING_BATCH_ITEM WHERE PAYMENT_ID=?",Integer.class,id));
  }
  var review=outgoing("IMPS","100000.01");long id=review.path("paymentId").asLong();
  var result=value(send("/payments/"+id+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isOk()));assertEquals("SETTLEMENT_PENDING",result.path("status").asText());assertTrue(result.path("clearingBatchId").asLong()>0);
 }
 @Test void incomingTimingDuplicateAndZeroNetConfirmation()throws Exception{
  BigDecimal before=balance(),r=reserve();String ref=UUID.randomUUID().toString();
  var p=value(send("/payments/simulator/incoming",admin,incoming("NEFT","100",ref)).andExpect(status().isOk()));assertEquals(0,before.compareTo(balance()));settle(p.path("settlementCycleId").asLong());assertEquals(0,before.add(new BigDecimal("100")).compareTo(balance()));
  assertEquals(p.path("paymentId").asLong(),value(send("/payments/simulator/incoming",admin,incoming("NEFT","100",ref)).andExpect(status().isOk())).path("paymentId").asLong());
  send("/payments/simulator/incoming",admin,incoming("NEFT","101",ref)).andExpect(status().isConflict());
  r=reserve();before=balance();var out=outgoing("IMPS","25");var in=value(send("/payments/simulator/incoming",admin,incoming("IMPS","25",UUID.randomUUID().toString())).andExpect(status().isOk()));
  assertEquals(out.path("settlementCycleId").asLong(),in.path("settlementCycleId").asLong());assertEquals(0,before.compareTo(balance()));
  settle(out.path("settlementCycleId").asLong());assertEquals(0,r.compareTo(reserve()));
  assertEquals(0,db.queryForObject("SELECT ACTIVE_HOLD_AMOUNT FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class).signum());
  assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM MBX_SIM_MOVEMENT WHERE SETTLEMENT_REFERENCE=?",Integer.class,"SIM-C-"+out.path("settlementCycleId").asLong()));
 }
 @Test void scheduledSettlementRoutingFundsAndAccessControls()throws Exception{
  send("/payments/simulator/incoming",checker,incoming("UPI","1",UUID.randomUUID().toString())).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/payments/simulator").header("Authorization","Bearer "+customer)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/payments/simulator").header("Authorization","Bearer "+checker)).andExpect(status().isOk());
  var bad=new HashMap<>(incoming("NEFT","10",UUID.randomUUID().toString()));bad.put("destinationBranch","WRONG");send("/payments/simulator/incoming",admin,bad).andExpect(status().isConflict());
  bad.put("destinationBranch","MUM001");bad.put("destinationAccountNumber","NO_SUCH_ACCOUNT");send("/payments/simulator/incoming",admin,bad).andExpect(status().isConflict());
  send("/payments/simulator/incoming",admin,incoming("NEFT","10000001",UUID.randomUUID().toString())).andExpect(status().isConflict());
  send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,"railCode","NEFT","amount","1000000","requestKey",UUID.randomUUID().toString())).andExpect(status().isConflict());
  BigDecimal before=balance();var p=value(send("/payments/simulator/incoming",admin,incoming("UPI","1",UUID.randomUUID().toString())).andExpect(status().isOk()));assertEquals(0,before.add(BigDecimal.ONE).compareTo(balance()));
  long cycle=p.path("settlementCycleId").asLong();clock.time=SimulationPolicy.window("UPI",clock.instant()).end().toInstant().plusSeconds(1);scheduler.run();scheduler.run();
  assertEquals("SETTLED",db.queryForObject("SELECT STATUS FROM M07_SETTLEMENT_CYCLE WHERE SETTLEMENT_CYCLE_ID=?",String.class,cycle));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM MBX_SIM_SETTLEMENT WHERE SETTLEMENT_CYCLE_ID=?",Integer.class,cycle));
 }
 @Test void approvalUsesCurrentCycleAndRejectsUnknownOrMixedRouting()throws Exception{
  fund();var pending=outgoing("NEFT","100000.01");var first=outgoing("NEFT","1");settle(first.path("settlementCycleId").asLong());
  var approved=value(send("/payments/"+pending.path("paymentId").asLong()+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isOk()));
  assertNotEquals(first.path("settlementCycleId").asLong(),approved.path("settlementCycleId").asLong());
  send("/payments/"+pending.path("paymentId").asLong()+"/authorize-simulation",checker,Map.of("reserveAccountId",999)).andExpect(status().isConflict());
  send("/treasury/settlement-evidence",admin,Map.ofEntries(Map.entry("reserveAccountId",1),Map.entry("paymentId",approved.path("paymentId").asLong()),Map.entry("railCode","NEFT"),Map.entry("evidenceType","RAIL_CONFIRMATION"),Map.entry("evidenceStatus","VERIFIED"),Map.entry("movementSide","OUT"),Map.entry("amount","100000.01"),Map.entry("externalSettlementRef",UUID.randomUUID().toString()),Map.entry("evidencePayload","{}"),Map.entry("occurredAt",OffsetDateTime.now().toString()))).andExpect(status().isConflict());
 }
 @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
 void unknownRouteRollsBackTheWholeRequest()throws Exception{
  String unknown=value(send("/beneficiaries",customer,Map.of("displayName","Unknown route","accountToken","UNKNOWN001","bankCode","TEST0123456")).andExpect(status().isOk())).path("beneficiaryId").asText();send("/beneficiaries/"+unknown+"/verify",checker,Map.of()).andExpect(status().isOk());
  String key=UUID.randomUUID().toString();send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",unknown,"railCode","UPI","amount","1","requestKey",key)).andExpect(status().isConflict());
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION WHERE REQUEST_KEY=?",Integer.class,key));
 }
}
