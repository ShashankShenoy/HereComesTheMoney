package com.moneybags;

import com.fasterxml.jackson.databind.*;
import com.moneybags.integration.DemoFundingController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(classes=MoneybagsApplication.class,properties={
 "spring.datasource.url=jdbc:h2:mem:demofunding;MODE=Oracle;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH",
 "moneybags.deposit-interest.enabled=false","moneybags.payment-simulation.clearing-enabled=false",
 "moneybags.cif.key-file=target/demo-funding/cif.key","moneybags.auth.mfa-key-file=target/demo-funding/mfa.key",
 "moneybags.documents.root=target/demo-funding/documents"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("local") @Transactional
class DemoFundingTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired DemoFundingController controller;String admin;
 String login(String name)throws Exception{
  var result=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",name,"password","LocalBanking!2026","clientId","DEMO_FUNDING_QA")))).andReturn();
  assertEquals(200,result.getResponse().getStatus());return json.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
 }
 @BeforeEach void setup()throws Exception{admin=login("admin");}
 MvcResult send(String path,String token,Object body)throws Exception{return mvc.perform(post("/api/v1"+path).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn();}
 Map<String,Object> funding(long account,String amount){return Map.of("accountId",account,"amount",amount,"requestKey",UUID.randomUUID().toString());}
 JsonNode successful(MvcResult result)throws Exception{assertEquals(200,result.getResponse().getStatus(),result.getResponse().getContentAsString());return json.readTree(result.getResponse().getContentAsString());}
 BigDecimal balance(long account){return db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=?",BigDecimal.class,account);}
 @Test void addsMoneyWithNoTillOrVaultAndBalancedLedger()throws Exception{
  db.update("DELETE FROM MBX_BRANCH_VAULT");assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM MBX_TELLER_TILL",Integer.class));
  BigDecimal before=balance(1),reserve=db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class);
  var receipt=successful(send("/accounts/demo-funding",admin,funding(1,"123.45")));
  assertTrue(receipt.path("simulated").asBoolean());assertEquals(0,before.add(new BigDecimal("123.45")).compareTo(balance(1)));
  long journal=receipt.path("transaction").path("journalId").asLong();assertTrue(journal>0);
  assertEquals(0,db.queryForObject("SELECT DEBIT_TOTAL-CREDIT_TOTAL FROM M05_V_GL_JOURNAL_CONTROL WHERE JOURNAL_ID=?",BigDecimal.class,journal).signum());
  assertEquals(0,reserve.compareTo(db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class)));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM MBX_BRANCH_VAULT",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM MBX_TELLER_TILL",Integer.class));
 }
 @Test void retryReturnsSameTransactionAndChangedDetailsConflict()throws Exception{
  var body=funding(1,"10.00");BigDecimal before=balance(1);var first=successful(send("/accounts/demo-funding",admin,body));
  var retry=successful(send("/accounts/demo-funding",admin,body));assertEquals(first.path("transaction").path("transactionId"),retry.path("transaction").path("transactionId"));
  assertEquals(0,before.add(BigDecimal.TEN).compareTo(balance(1)));
  var changed=new HashMap<>(body);changed.put("amount","11.00");assertEquals(409,send("/accounts/demo-funding",admin,changed).getResponse().getStatus());
  changed.put("accountId",2);assertEquals(409,send("/accounts/demo-funding",admin,changed).getResponse().getStatus());
 }
 @Test void pendingAccountGetsOpeningFundsWithoutBypassingActivation()throws Exception{
  db.update("UPDATE M03_PM_ACCOUNT_RULE SET MIN_OPENING_BALANCE=1000 WHERE PRODUCT_VERSION_ID=1");
  var opened=send("/accounts",admin,Map.of("requestId",UUID.randomUUID().toString(),"primaryCifId","demo-cif-1","productId",1,"productVersionId",1,"branchCode","MUM001","operationMode","SELF_OPERATED","additionalParties",List.of()));
  assertEquals(201,opened.getResponse().getStatus());long id=json.readTree(opened.getResponse().getContentAsString()).path("id").asLong();
  var body=funding(id,"1000.00");var receipt=successful(send("/accounts/demo-funding",admin,body));
  assertEquals("PENDING_OPEN",db.queryForObject("SELECT LIFECYCLE_STATUS FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?",String.class,id));
  assertEquals(0,new BigDecimal("1000").compareTo(balance(id)));
  assertEquals("OPENING_FUNDING",db.queryForObject("SELECT TXN_TYPE FROM M05_TXN_TRANSACTION_LOG WHERE TXN_ID=?",String.class,receipt.path("transaction").path("transactionId").asLong()));
  assertEquals(202,send("/accounts/"+id+"/activate",admin,Map.of("requestId",UUID.randomUUID().toString())).getResponse().getStatus());
  successful(send("/accounts/demo-funding",admin,body));assertEquals(0,new BigDecimal("1000").compareTo(balance(id)));
 }
 @Test void customerAndOutOfScopeStaffCannotFund()throws Exception{
  assertEquals(403,send("/accounts/demo-funding",login("customer"),funding(1,"1")).getResponse().getStatus());
  String id=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='admin'",String.class);
  db.update("UPDATE M01_IAM_USER_ROLE SET SCOPE_TYPE='BRANCH',SCOPE_REF='OTHER' WHERE USER_ID=?",id);
  assertEquals(403,send("/accounts/demo-funding",admin,funding(1,"1")).getResponse().getStatus());
 }
 @Test void invalidAmountsAndClosedAccountsAreRejected()throws Exception{
  BigDecimal before=balance(1);
  for(String amount:List.of("0","-1","1.001","10000000.01"))assertEquals(400,send("/accounts/demo-funding",admin,funding(1,amount)).getResponse().getStatus());
  db.update("UPDATE M04_BANK_ACCOUNT SET LIFECYCLE_STATUS='CLOSED',ACCOUNT_STATUS='CLOSED',CLOSED_AT=SYSTIMESTAMP WHERE ACCOUNT_ID=1");
  assertEquals(409,send("/accounts/demo-funding",admin,funding(1,"1")).getResponse().getStatus());assertEquals(0,before.compareTo(balance(1)));
 }
 @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
 void creditBlockRollsBackTheWholeDeposit()throws Exception{
  db.update("UPDATE M05_POSTING_FENCE SET CREDIT_STATUS='CLOSED' WHERE BANK_ACCOUNT_ID=1");BigDecimal before=balance(1);
  int count=db.queryForObject("SELECT COUNT(*) FROM M05_TXN_TRANSACTION_LOG",Integer.class);
  try{
   assertEquals(409,send("/accounts/demo-funding",admin,funding(1,"10")).getResponse().getStatus());assertEquals(0,before.compareTo(balance(1)));
   assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM M05_TXN_TRANSACTION_LOG",Integer.class));
  }finally{db.update("UPDATE M05_POSTING_FENCE SET CREDIT_STATUS='OPEN' WHERE BANK_ACCOUNT_ID=1");}
 }
 @Test void disabledFeatureCannotCreateMoney()throws Exception{
  org.springframework.test.util.ReflectionTestUtils.setField(controller,"enabled",false);
  try{assertEquals(409,send("/accounts/demo-funding",admin,funding(1,"10")).getResponse().getStatus());}
  finally{org.springframework.test.util.ReflectionTestUtils.setField(controller,"enabled",true);}
 }
}
