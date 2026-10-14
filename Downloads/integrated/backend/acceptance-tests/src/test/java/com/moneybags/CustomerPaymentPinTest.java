package com.moneybags;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:paymentpin;MODE=Oracle;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH","moneybags.payment-simulation.poll-ms=3600000"})
@AutoConfigureMockMvc @ActiveProfiles("local")
class CustomerPaymentPinTest {
 @TestConfiguration static class TimeConfig{@Bean @Primary PinClock pinClock(){return new PinClock();}}
 static class PinClock extends Clock{Duration offset=Duration.ZERO;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return Clock.fixed(instant(),z);}public Instant instant(){return Instant.now().plus(offset);}}
 @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired PinClock clock;@Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
 static final String PASSWORD="PinFixture!2026",PIN="134679";String token,user,admin,checker,beneficiary,name;
 @BeforeEach void prepare()throws Exception{
  clock.offset=Duration.ZERO;name="pin_"+UUID.randomUUID().toString().substring(0,8);
  send("/auth/signup",null,Map.of("username",name,"legalName","PIN fixture","dateOfBirth","1990-01-01","password",PASSWORD,"paymentPin",PIN),null).andExpect(status().isOk());
  user=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME=?",String.class,name);token=login(name,PASSWORD);admin=login("admin","LocalBanking!2026");checker=login("checker","LocalBanking!2026");
  db.update("INSERT INTO M01_IAM_CUSTOMER_LINK(LINK_ID,USER_ID,CIF_ID,RELATIONSHIP_TYPE,STATUS,VALID_FROM) VALUES (?,?,'demo-cif-1','SELF','ACTIVE',SYSTIMESTAMP)",UUID.randomUUID().toString(),user);
  token=login(name,PASSWORD);
  beneficiary=value(send("/beneficiaries",token,Map.of("displayName","PIN recipient","accountToken","EXT-"+UUID.randomUUID(),"bankCode","HDFC0000001"),null)).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of(),null).andExpect(status().isOk());
 }
 String login(String name,String password)throws Exception{return value(send("/auth/login",null,Map.of("username",name,"password",password,"clientId","MONEYBAGS_WEB"),null)).path("data").path("accessToken").asText();}
 ResultActions send(String path,String actor,Object body,String pin)throws Exception{var request=post("/api/v1"+path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));if(actor!=null)request.header("Authorization","Bearer "+actor);if(pin!=null)request.header("X-Payment-Pin",pin);return mvc.perform(request);}
 JsonNode value(ResultActions result)throws Exception{return json.readTree(result.andReturn().getResponse().getContentAsString());}
 JsonNode state()throws Exception{return value(mvc.perform(get("/api/v1/auth/payment-pin").header("Authorization","Bearer "+token)).andExpect(status().isOk())).path("data");}
 Map<String,Object> transfer(String key){return Map.of("sourceAccountNumber","MB000000001","targetAccountNumber","MB000000002","amount","1","requestKey",key,"correlationId",key);}
 Map<String,Object> payment(String key){return Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,"railCode","UPI","amount","1","requestKey",key);}
 BigDecimal balance(){return db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=1",BigDecimal.class);}
 @Test void signupRequiresPinAndStoresOnlySaltedHash()throws Exception{
  assertTrue(state().path("configured").asBoolean());assertFalse(state().has("pinHash"));assertFalse(state().has("pin"));
  String hash=db.queryForObject("SELECT PIN_HASH FROM MBX_PAYMENT_PIN WHERE USER_ID=?",String.class,user);assertNotEquals(PIN,hash);assertTrue(encoder.matches(PIN,hash));
  send("/auth/signup",null,Map.of("username","missing_"+UUID.randomUUID(),"legalName","Missing PIN","dateOfBirth","1990-01-01","password",PASSWORD),null).andExpect(status().isBadRequest());
  send("/auth/payment-pin/setup",token,Map.of("pin","684207"),null).andExpect(status().isConflict());
  mvc.perform(get("/api/v1/auth/payment-pin")).andExpect(status().isUnauthorized());
  send("/auth/payment-pin/reset",admin,Map.of("currentPassword","LocalBanking!2026","newPin","684207"),null).andExpect(status().isForbidden());
 }
 @Test void existingCustomerCanEnrollButCannotPayBeforeSetup()throws Exception{
  db.update("DELETE FROM MBX_PAYMENT_PIN WHERE USER_ID=?",user);assertFalse(state().path("configured").asBoolean());
  token=login(name,PASSWORD);assertFalse(state().path("configured").asBoolean());
  send("/transactions/transfers/by-number",token,transfer(UUID.randomUUID().toString()),PIN).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PIN_SETUP_REQUIRED"));
  send("/auth/payment-pin/setup",token,Map.of("pin","000123"),null).andExpect(status().isOk());
  send("/transactions/transfers/by-number",token,transfer(UUID.randomUUID().toString()),"000123").andExpect(status().isOk());assertTrue(state().path("configured").asBoolean());
 }
 @Test void everyTransferAndExternalCreationRouteRequiresPin()throws Exception{
  BigDecimal before=balance();String key=UUID.randomUUID().toString();var request=transfer(key);
  send("/transactions/transfers/by-number",token,request,null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PIN_REQUIRED"));
  send("/transactions/transfers/by-number",token,request,"999999").andExpect(status().isForbidden());assertEquals(0,before.compareTo(balance()));
  send("/transactions/transfers",token,Map.of("sourceAccountId",1,"targetAccountId",2,"amount","1","channelCode","WEB","requestKey",key,"correlationId",key,"valueDate",LocalDate.now().toString()),null).andExpect(status().isForbidden());
  send("/transactions/transfers/by-number",token,request,PIN).andExpect(status().isOk());send("/transactions/transfers/by-number",token,request,PIN).andExpect(status().isOk());assertEquals(0,before.subtract(BigDecimal.ONE).compareTo(balance()));
  var pay=payment(UUID.randomUUID().toString());send("/payments/initiate",token,pay,null).andExpect(status().isForbidden());send("/payments/initiate",token,pay,PIN).andExpect(status().isOk());
  var raw=Map.ofEntries(Map.entry("originatorId",user),Map.entry("channelCode","WEB"),Map.entry("requestKey",UUID.randomUUID().toString()),Map.entry("correlationId","pin-raw"),Map.entry("endToEndId",UUID.randomUUID().toString()),Map.entry("direction","OUTBOUND"),Map.entry("kind","PAYMENT"),Map.entry("sourceAccountId",1),Map.entry("sourceCifId","demo-cif-1"),Map.entry("beneficiaryTokenRef",db.queryForObject("SELECT ACCOUNT_TOKEN FROM MBX_BENEFICIARY WHERE BENEFICIARY_ID=?",String.class,beneficiary)),Map.entry("beneficiaryBankCode","HDFC0000001"),Map.entry("railCode","UPI"),Map.entry("amount","1"),Map.entry("requestedExecutionDate",LocalDate.now().toString()));
  send("/payments",token,raw,null).andExpect(status().isForbidden());send("/payments",token,raw,PIN).andExpect(status().isCreated());
 }
 @Test void wrongAttemptsSurvivePaymentRollbackAndLockAfterFive()throws Exception{
  BigDecimal before=balance();for(int n=1;n<=5;n++){send("/transactions/transfers/by-number",token,transfer(UUID.randomUUID().toString()),"999999").andExpect(status().isForbidden());assertEquals(n,db.queryForObject("SELECT FAILED_ATTEMPTS FROM MBX_PAYMENT_PIN WHERE USER_ID=?",Integer.class,user));}
  assertTrue(state().path("locked").asBoolean());send("/payments/initiate",token,payment(UUID.randomUUID().toString()),PIN).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PIN_LOCKED"));assertEquals(0,before.compareTo(balance()));
  clock.offset=Duration.ofMinutes(16);token=login(name,PASSWORD);send("/payments/initiate",token,payment(UUID.randomUUID().toString()),PIN).andExpect(status().isOk());assertFalse(state().path("locked").asBoolean());
 }
 @Test void passwordVerifiedResetInvalidatesOldPinAndUnlocksPayments()throws Exception{
  send("/auth/payment-pin/reset",token,Map.of("currentPassword","wrong-password","newPin","684207"),null).andExpect(status().isForbidden());
  for(int n=0;n<5;n++)send("/payments/initiate",token,payment(UUID.randomUUID().toString()),"999999").andExpect(status().isForbidden());
  send("/auth/payment-pin/reset",token,Map.of("currentPassword",PASSWORD,"newPin","684207"),null).andExpect(status().isOk());assertFalse(state().path("locked").asBoolean());
  send("/payments/initiate",token,payment(UUID.randomUUID().toString()),PIN).andExpect(status().isForbidden());send("/payments/initiate",token,payment(UUID.randomUUID().toString()),"684207").andExpect(status().isOk());
 }
 @Test void repeatedIncorrectResetPasswordsAreRateLimited()throws Exception{
  for(int n=0;n<5;n++)send("/auth/payment-pin/reset",token,Map.of("currentPassword","wrong-password","newPin","684207"),null).andExpect(status().isForbidden());
  send("/auth/payment-pin/reset",token,Map.of("currentPassword",PASSWORD,"newPin","684207"),null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PIN_RESET_LOCKED"));
  send("/payments/initiate",token,payment(UUID.randomUUID().toString()),PIN).andExpect(status().isOk());
 }
 @Test void ownBankBeneficiaryUsesPinProtectedIdempotentInternalLedger()throws Exception{
  String internal=value(send("/beneficiaries",token,Map.of("displayName","Internal recipient","accountToken","MB000000002","bankCode","MCPB0000002"),null)).path("beneficiaryId").asText();
  send("/beneficiaries/"+internal+"/verify",checker,Map.of(),null).andExpect(status().isOk());
  var rows=value(mvc.perform(get("/api/v1/beneficiaries").header("Authorization","Bearer "+token)).andExpect(status().isOk()));
  assertTrue(rows.toString().contains("INTERNAL"));
  String key=UUID.randomUUID().toString();var body=Map.of("sourceAccountId",1,"beneficiaryId",internal,"amount","1","requestKey",key,"correlationId",key);
  BigDecimal before=balance();BigDecimal target=db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=2",BigDecimal.class);
  int payments=db.queryForObject("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION",Integer.class);
  send("/transactions/transfers/by-beneficiary",token,body,null).andExpect(status().isForbidden());assertEquals(0,before.compareTo(balance()));
  send("/transactions/transfers/by-beneficiary",token,body,PIN).andExpect(status().isOk());send("/transactions/transfers/by-beneficiary",token,body,PIN).andExpect(status().isOk());
  assertEquals(0,before.subtract(BigDecimal.ONE).compareTo(balance()));assertEquals(0,target.add(BigDecimal.ONE).compareTo(db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=2",BigDecimal.class)));
  assertEquals(payments,db.queryForObject("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION",Integer.class));
  send("/transactions/transfers/by-beneficiary",token,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,"amount","1","requestKey",UUID.randomUUID().toString(),"correlationId",key),PIN).andExpect(status().isConflict());
  db.update("UPDATE MBX_BENEFICIARY SET STATUS='DISABLED' WHERE BENEFICIARY_ID=?",internal);
  send("/transactions/transfers/by-beneficiary",token,body,PIN).andExpect(status().isNotFound());
 }
}
