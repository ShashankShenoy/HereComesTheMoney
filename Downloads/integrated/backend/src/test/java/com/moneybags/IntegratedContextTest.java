package com.moneybags;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.math.BigDecimal;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "moneybags.auth.allowed-origins=http://localhost:5173")
@AutoConfigureMockMvc @ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegratedContextTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired com.moneybags.txn.core.LedgerService ledger;
 @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
 static final String HASH=("customer-demo-hash-"+"x".repeat(43)).substring(0,43);
 String login(String user)throws Exception{
  var r=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",user,"password","LocalBanking!2026","clientId","MONEYBAGS_WEB")))).andExpect(status().isOk()).andReturn();
  return json.readTree(r.getResponse().getContentAsString()).path("data").path("accessToken").asText();
 }
 ResultActions send(String path,String token,Object body)throws Exception{return mvc.perform(post("/api/v1"+path).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));}
 JsonNode value(ResultActions result)throws Exception{return json.readTree(result.andReturn().getResponse().getContentAsString());}
 BigDecimal balance(long id){return db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=?",BigDecimal.class,id);}
 Map<String,Object> transfer(long from,long to,String amount,String key){return Map.of("sourceAccountId",from,"targetAccountId",to,"amount",amount,"channelCode","BRANCH","requestKey",key,"correlationId","test-"+key,"valueDate",java.time.LocalDate.now().toString());}
 String nonAdminViewer()throws Exception{
  String role=UUID.randomUUID().toString(),user=UUID.randomUUID().toString(),name="txn_viewer_"+UUID.randomUUID().toString().substring(0,8);
  db.update("INSERT INTO M01_IAM_ROLE(ROLE_ID,ROLE_CODE,DISPLAY_NAME,STATUS,SENSITIVE_FLAG) VALUES (?,?,'Transaction viewer','ACTIVE','N')",role,"TXN_VIEWER_"+name);
  db.update("INSERT INTO M01_IAM_USER(USER_ID,USERNAME,USER_TYPE,STATUS,EMPLOYEE_REF) VALUES (?,?,'EMPLOYEE','ACTIVE','TEST')",user,name);
  db.update("INSERT INTO M01_IAM_CREDENTIAL(CREDENTIAL_ID,USER_ID,PASSWORD_HASH,HASH_SCHEME,STATUS) VALUES (?,?,?,'BCRYPT','ACTIVE')",UUID.randomUUID().toString(),user,passwords.encode("LocalBanking!2026"));
  for(String permission:List.of("ACCOUNT_READ","TXN_READ"))db.update("INSERT INTO M01_IAM_ROLE_PERMISSION(ROLE_ID,PERMISSION_ID) SELECT ?,PERMISSION_ID FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE=?",role,permission);
  db.update("INSERT INTO M01_IAM_USER_ROLE(ASSIGNMENT_ID,USER_ID,ROLE_ID,SCOPE_TYPE,STATUS,VALID_FROM) VALUES (?,?,?,'GLOBAL','ACTIVE',SYSTIMESTAMP)",UUID.randomUUID().toString(),user,role);
  return login(name);
 }
 @Test @Order(99) @Transactional void branchScopedAccountDirectoryScansPastOtherBranches()throws Exception{
  String role=UUID.randomUUID().toString(),user=UUID.randomUUID().toString(),name="branch_viewer_"+UUID.randomUUID().toString().substring(0,8);
  db.update("INSERT INTO M01_IAM_ROLE(ROLE_ID,ROLE_CODE,DISPLAY_NAME,STATUS,SENSITIVE_FLAG) VALUES (?,?,'Branch account viewer','ACTIVE','N')",role,"BRANCH_VIEWER_"+name);
  db.update("INSERT INTO M01_IAM_USER(USER_ID,USERNAME,USER_TYPE,STATUS,EMPLOYEE_REF) VALUES (?,?,'EMPLOYEE','ACTIVE','TEST')",user,name);
  db.update("INSERT INTO M01_IAM_CREDENTIAL(CREDENTIAL_ID,USER_ID,PASSWORD_HASH,HASH_SCHEME,STATUS) VALUES (?,?,?,'BCRYPT','ACTIVE')",UUID.randomUUID().toString(),user,passwords.encode("LocalBanking!2026"));
  db.update("INSERT INTO M01_IAM_ROLE_PERMISSION(ROLE_ID,PERMISSION_ID) SELECT ?,PERMISSION_ID FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE='ACCOUNT_READ'",role);
  db.update("INSERT INTO M01_IAM_USER_ROLE(ASSIGNMENT_ID,USER_ID,ROLE_ID,SCOPE_TYPE,SCOPE_REF,STATUS,VALID_FROM) VALUES (?,?,?,'BRANCH','TEST001','ACTIVE',SYSTIMESTAMP)",UUID.randomUUID().toString(),user,role);
  for(int i=0;i<200;i++)db.update("INSERT INTO M04_BANK_ACCOUNT(ACCOUNT_NUMBER,PRIMARY_CIF_ID,PRODUCT_ID,PRODUCT_VERSION_ID,BRANCH_CODE,OPEN_REQUEST_ID,CREATED_BY_USER_ID) VALUES (?,?,1,1,'MUM001',?,?)",
      "TEST"+UUID.randomUUID().toString().substring(0,12),"demo-cif-1",UUID.randomUUID().toString(),user);
  db.update("INSERT INTO M04_BANK_ACCOUNT(ACCOUNT_NUMBER,PRIMARY_CIF_ID,PRODUCT_ID,PRODUCT_VERSION_ID,BRANCH_CODE,OPEN_REQUEST_ID,CREATED_BY_USER_ID) VALUES (?,?,1,1,'TEST001',?,?)",
      "TEST"+UUID.randomUUID().toString().substring(0,12),"demo-cif-1",UUID.randomUUID().toString(),user);
  String token=login(name);
  var accounts=value(mvc.perform(get("/api/v1/banking/accounts").header("Authorization","Bearer "+token)).andExpect(status().isOk()));
  assertEquals(1,accounts.size());
  assertEquals("TEST001",accounts.get(0).path("BRANCH_CODE").asText());
 }
 @Test @Order(1) void unifiedRoutesAndAuthentication()throws Exception{
  mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  mvc.perform(get("/api/v1/banking/accounts")).andExpect(status().isUnauthorized());
  mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/treasury/reserve-accounts']").exists()).andExpect(jsonPath("$.paths['/api/v1/loans/applications']").exists());
  mvc.perform(options("/api/v1/transactions/transfers").header("Origin","http://localhost:5173").header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","authorization,x-customer-hash,idempotency-key")).andExpect(status().isOk());
 }
 @Test @Order(2) void customerSessionRevealsOnlyOwnedFinancialDetails()throws Exception{
  String customer=login("customer"),other=login("customer2"),officer=login("admin");
  mvc.perform(get("/api/v1/accounts/1/position").header("Authorization","Bearer "+customer)).andExpect(status().isOk()).andExpect(jsonPath("$.posted").isNumber());
  mvc.perform(get("/api/v1/accounts/1/position").header("Authorization","Bearer "+other).header("X-Customer-Hash",HASH)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/accounts/1/position").header("Authorization","Bearer "+officer)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/accounts/1/position").header("Authorization","Bearer "+officer).header("X-Customer-Hash",HASH)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/transactions/1").header("Authorization","Bearer "+customer)).andExpect(status().isOk()).andExpect(jsonPath("$.transactionId").exists()).andExpect(jsonPath("$.amount").isNumber());
  mvc.perform(get("/api/v1/transactions/1").header("Authorization","Bearer "+other)).andExpect(status().isOk()).andExpect(jsonPath("$.amount").doesNotExist());
  mvc.perform(get("/api/v1/banking/transactions").header("Authorization","Bearer "+customer)).andExpect(status().isBadRequest());
  mvc.perform(get("/api/v1/banking/my-dashboard").header("Authorization","Bearer "+customer)).andExpect(status().isOk()).andExpect(jsonPath("$.accounts[0].accountId").value(1)).andExpect(jsonPath("$.accounts[0].posted").isNumber());
  mvc.perform(get("/api/v1/banking/my-dashboard").header("Authorization","Bearer "+other)).andExpect(status().isOk()).andExpect(jsonPath("$.accounts[0].accountId").value(2));
 }
 @Test @Order(3) void balancedTransferReplayConflictAndAccessControl()throws Exception{
  String customer=login("customer");BigDecimal before=balance(1);String key=UUID.randomUUID().toString();
  var request=transfer(1,2,"125.50",key);
  long txn=value(send("/transactions/transfers",customer,request).andExpect(status().isOk()).andExpect(jsonPath("$.amount").isNumber())).path("transactionId").asLong();
  assertEquals(0,before.subtract(new BigDecimal("125.50")).compareTo(balance(1)));
  assertEquals(txn,value(send("/transactions/transfers",customer,request).andExpect(status().isOk())).path("transactionId").asLong());
  assertEquals(0,before.subtract(new BigDecimal("125.50")).compareTo(balance(1)));
  send("/transactions/transfers",customer,transfer(1,2,"125.51",key)).andExpect(status().isConflict());
  send("/transactions/transfers",customer,transfer(2,1,"1.00",UUID.randomUUID().toString())).andExpect(status().isForbidden());
  send("/transactions/transfers",customer,transfer(1,2,"90000.00",UUID.randomUUID().toString())).andExpect(status().isConflict());
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M05_V_GL_JOURNAL_CONTROL WHERE IS_BALANCED='N'",Integer.class));
 }
 @Test @Order(2) void accountScopedDetailsAndInternalReserveReadsStaySeparated()throws Exception{
  String customer=login("customer"),other=login("customer2"),admin=login("admin"),viewer=nonAdminViewer();
  mvc.perform(get("/api/v1/banking/accounts/1/key-holders").header("Authorization","Bearer "+admin))
   .andExpect(status().isOk()).andExpect(jsonPath("$[0].HOLDER_NAME").value("Aarav Mehta (demo)"))
   .andExpect(jsonPath("$[0].CAN_SIGN_IN").value("Y"))
   .andExpect(jsonPath("$[0].SECRET_DIGEST").doesNotExist());
  mvc.perform(get("/api/v1/banking/accounts/1/key-holders").header("Authorization","Bearer "+customer))
   .andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/banking/transactions/1/details?accountId=1").header("Authorization","Bearer "+customer))
   .andExpect(status().isOk()).andExpect(jsonPath("$.AMOUNT").isNumber()).andExpect(jsonPath("$.journals[0].control.IS_BALANCED").value("Y"));
  mvc.perform(get("/api/v1/banking/transactions/1/details?accountId=1").header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/banking/transactions?accountNumber=MB000000001").header("Authorization","Bearer "+admin))
   .andExpect(status().isOk()).andExpect(jsonPath("$[0].amount").isNumber());
  mvc.perform(get("/api/v1/banking/transactions?accountNumber=MB000000001").header("Authorization","Bearer "+viewer))
   .andExpect(status().isOk()).andExpect(jsonPath("$[0].amount").doesNotExist());
  mvc.perform(get("/api/v1/banking/transactions/1/details?accountNumber=MB000000001").header("Authorization","Bearer "+viewer)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/banking/transactions/1/details?accountNumber=MB000000001").header("Authorization","Bearer "+viewer).header("X-Customer-Hash",HASH)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/banking/transactions/1/details?accountNumber=MB000000001").header("Authorization","Bearer "+admin))
   .andExpect(status().isOk()).andExpect(jsonPath("$.accountRole").value("TARGET"));
  mvc.perform(get("/api/v1/transactions/1").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.amount").isNumber());
  mvc.perform(get("/api/v1/transactions/1").header("Authorization","Bearer "+viewer)).andExpect(status().isOk()).andExpect(jsonPath("$.amount").doesNotExist());
  mvc.perform(get("/api/v1/banking/transactions/3/details").header("Authorization","Bearer "+customer)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/banking/transactions/3/details").header("Authorization","Bearer "+admin))
   .andExpect(status().isOk()).andExpect(jsonPath("$.accountRole").value("INTERNAL")).andExpect(jsonPath("$.journals[0].accountPostings[0].GL_ACCOUNT_ID").isNumber());
  mvc.perform(get("/api/v1/transactions/3").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.amount").isNumber());
  mvc.perform(get("/api/v1/transactions/3").header("Authorization","Bearer "+customer)).andExpect(status().isOk()).andExpect(jsonPath("$.amount").doesNotExist());
  mvc.perform(get("/api/v1/treasury/reserve-accounts/1/ledger").header("Authorization","Bearer "+admin))
   .andExpect(status().isOk()).andExpect(jsonPath("$[0].externalSettlementRef").value("SIMULATED-OPENING")).andExpect(jsonPath("$[0].evidenceStatus").value("VERIFIED"));
  mvc.perform(get("/api/v1/treasury/reserve-accounts/1/reconciliation").header("Authorization","Bearer "+admin))
   .andExpect(status().isOk()).andExpect(jsonPath("$.reserveAccountId").value(1)).andExpect(jsonPath("$.isMatched").value("Y"));
  mvc.perform(get("/api/v1/treasury/reserve-accounts/1/ledger").header("Authorization","Bearer "+customer)).andExpect(status().isForbidden());
 }
 @Test @Order(2) void glRegisterIsPagedAndReconciliationOnly()throws Exception{
  String admin=login("admin"),customer=login("customer"),viewer=nonAdminViewer();
  String path="/api/v1/banking/gl-accounts/6/ledger";
  mvc.perform(get(path).header("Authorization","Bearer "+customer)).andExpect(status().isForbidden());
  mvc.perform(get(path).header("Authorization","Bearer "+viewer)).andExpect(status().isForbidden());
  mvc.perform(get(path+"?limit=0").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
  var page=value(mvc.perform(get(path+"?limit=1&offset=0").header("Authorization","Bearer "+admin)).andExpect(status().isOk()));
  assertEquals(6,page.path("account").path("GL_ACCOUNT_ID").asLong());
  assertEquals("DR",page.path("account").path("NORMAL_SIDE").asText());
  assertTrue(page.path("total").asLong()>0);
  assertEquals(1,page.path("postings").size());
  assertEquals(0,page.path("balance").decimalValue().compareTo(page.path("postings").get(0).path("RUNNING_BALANCE").decimalValue()));
  long journalId=page.path("postings").get(0).path("JOURNAL_ID").asLong();
  var detail=value(mvc.perform(get("/api/v1/banking/journals/"+journalId).header("Authorization","Bearer "+admin)).andExpect(status().isOk()));
  assertEquals("Y",detail.path("control").path("IS_BALANCED").asText());
  assertTrue(detail.path("lines").size()>=2);
  mvc.perform(get("/api/v1/banking/journals/"+journalId).header("Authorization","Bearer "+customer)).andExpect(status().isForbidden());
 }
 @Test @Order(4) void newAccountsActivateThroughRealLedgerFence()throws Exception{
  String admin=login("admin");String request=UUID.randomUUID().toString();
  long id=value(send("/accounts",admin,Map.of("requestId",request,"primaryCifId","demo-cif-1","productId",1,"productVersionId",1,"branchCode","MUM001","operationMode","SELF_OPERATED","additionalParties",List.of())).andExpect(status().isCreated())).path("id").asLong();
  send("/accounts/"+id+"/activate",admin,Map.of("requestId",UUID.randomUUID().toString())).andExpect(status().isAccepted());
  assertEquals("ACTIVE",db.queryForObject("SELECT LIFECYCLE_STATUS FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?",String.class,id));
  assertEquals("OPEN",db.queryForObject("SELECT DEBIT_STATUS FROM M05_POSTING_FENCE WHERE BANK_ACCOUNT_ID=?",String.class,id));
 }
 @Test @Order(5) void tellerPostingIsBalancedAndIdempotent()throws Exception{
  String admin=login("admin"),checker=login("checker");
  mvc.perform(get("/api/v1/teller/cash-ledger-accounts").header("Authorization","Bearer "+admin))
    .andExpect(status().isOk()).andExpect(jsonPath("$[0].GL_CODE").value("CASH"))
    .andExpect(jsonPath("$[1]").doesNotExist());
  send("/teller/tills",admin,Map.of("branchCode","MUM001","cashGlId",6)).andExpect(status().isConflict());
  BigDecimal reserveBefore=db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class);
  int reserveEntriesBefore=db.queryForObject("SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER WHERE RESERVE_ACCOUNT_ID=1",Integer.class);
  BigDecimal vaultBefore=db.queryForObject("SELECT CASH_BALANCE FROM MBX_BRANCH_VAULT WHERE BRANCH_CODE='MUM001'",BigDecimal.class);
  JsonNode opening=value(send("/teller/tills",admin,Map.of("branchCode","MUM001","cashGlId",2)).andExpect(status().isOk()));
  String till=opening.path("tillId").asText();
  assertEquals(0,new BigDecimal("5000").compareTo(new BigDecimal(opening.path("openingCash").asText())));
  assertEquals(0,vaultBefore.subtract(new BigDecimal("5000")).compareTo(db.queryForObject("SELECT CASH_BALANCE FROM MBX_BRANCH_VAULT WHERE BRANCH_CODE='MUM001'",BigDecimal.class)));
  assertEquals(0,new BigDecimal("5000").compareTo(db.queryForObject("SELECT CASH_BALANCE FROM MBX_TELLER_TILL WHERE TILL_ID=?",BigDecimal.class,till)));
  assertEquals(0,reserveBefore.compareTo(db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class)));
  assertEquals(reserveEntriesBefore,db.queryForObject("SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER WHERE RESERVE_ACCOUNT_ID=1",Integer.class));
  assertEquals(0,db.queryForObject("SELECT DIFFERENCE FROM M07_V_RESERVE_POSITION_RECON WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class).compareTo(BigDecimal.ZERO));
  assertEquals(0,db.queryForObject("SELECT DEBIT_TOTAL-CREDIT_TOTAL FROM M05_V_GL_JOURNAL_CONTROL WHERE JOURNAL_ID=?",BigDecimal.class,opening.path("fundingJournalId").asLong()).compareTo(BigDecimal.ZERO));
  BigDecimal before=balance(1);
  var command=Map.of("requestKey",UUID.randomUUID().toString(),"tillId",till,"accountId",1,"direction","DEPOSIT","amount","100.00","reason","Test deposit");
  send("/teller/cash",admin,command).andExpect(status().isOk());
  send("/teller/cash",admin,command).andExpect(status().isOk());
  assertEquals(0,before.add(new BigDecimal("100")).compareTo(balance(1)));
  var withdrawal=Map.of("requestKey",UUID.randomUUID().toString(),"tillId",till,"accountId",1,"direction","WITHDRAWAL","amount","1500.00","reason","Test withdrawal");
  send("/teller/cash",admin,withdrawal).andExpect(status().isOk());
  assertEquals(0,before.subtract(new BigDecimal("1400")).compareTo(balance(1)));
  assertEquals(0,new BigDecimal("3600").compareTo(db.queryForObject("SELECT CASH_BALANCE FROM MBX_TELLER_TILL WHERE TILL_ID=?",BigDecimal.class,till)));
  send("/teller/cash",admin,Map.of("requestKey",UUID.randomUUID().toString(),"tillId",till,"accountId",1,"direction","WITHDRAWAL","amount","3600.01","reason","Too much cash"))
    .andExpect(status().isConflict());
  assertEquals(0,before.subtract(new BigDecimal("1400")).compareTo(balance(1)));
  var topUp=Map.of("requestKey",UUID.randomUUID().toString(),"amount","400.00","evidenceRef","COUNTED_VAULT_TRANSFER");
  send("/teller/tills/"+till+"/replenish",admin,topUp).andExpect(status().isConflict());
  var replenished=value(send("/teller/tills/"+till+"/replenish",checker,topUp).andExpect(status().isOk()));
  send("/teller/tills/"+till+"/replenish",checker,topUp).andExpect(status().isOk());
  assertEquals(0,new BigDecimal("4000").compareTo(db.queryForObject("SELECT CASH_BALANCE FROM MBX_TELLER_TILL WHERE TILL_ID=?",BigDecimal.class,till)));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM MBX_TILL_REPLENISHMENT WHERE TILL_ID=?",Integer.class,till));
  assertEquals(0,db.queryForObject("SELECT DEBIT_TOTAL-CREDIT_TOTAL FROM M05_V_GL_JOURNAL_CONTROL WHERE JOURNAL_ID=?",BigDecimal.class,replenished.path("journalId").asLong()).compareTo(BigDecimal.ZERO));
  send("/teller/tills/"+till+"/close",admin,Map.of("countedCash","4000","reason","Close till")).andExpect(status().isConflict());
  send("/teller/tills/"+till+"/close",checker,Map.of("countedCash","4000","reason","Independent close")).andExpect(status().isOk());
  assertEquals(0,vaultBefore.subtract(new BigDecimal("1400")).compareTo(db.queryForObject("SELECT CASH_BALANCE FROM MBX_BRANCH_VAULT WHERE BRANCH_CODE='MUM001'",BigDecimal.class)));
  assertEquals(0,BigDecimal.ZERO.compareTo(db.queryForObject("SELECT CASH_BALANCE FROM MBX_TELLER_TILL WHERE TILL_ID=?",BigDecimal.class,till)));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM MBX_TILL_CASH_RETURN WHERE TILL_ID=?",Integer.class,till));
  assertEquals(0,reserveBefore.compareTo(db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class)));
  assertEquals(reserveEntriesBefore,db.queryForObject("SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER WHERE RESERVE_ACCOUNT_ID=1",Integer.class));
  // A separately evidenced cash delivery, not till opening, moves the RBI mirror.
  db.update("INSERT INTO M05_GL_ACCOUNT(GL_ACCOUNT_ID,GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE) VALUES (90,'TEST_VAULT_CASH','Test branch vault cash','ASSET','DR')");
  send("/teller/vaults",admin,Map.of("branchCode","DEL999","vaultGlId",90,"countedCash","0","evidenceRef","EMPTY_VAULT_TEST"))
    .andExpect(status().isOk());
  String shipmentRef="SIM-CASH-"+UUID.randomUUID();
  var request=Map.of("requestKey",UUID.randomUUID().toString(),"branchCode","DEL999","reserveAccountId",1,
    "amount","5000.00","shipmentRef",shipmentRef,"requestEvidenceRef","SIMULATED_RBI_ADVICE");
  String delivery=value(send("/teller/cash-deliveries",admin,request).andExpect(status().isOk())).path("deliveryId").asText();
  assertEquals(delivery,value(send("/teller/cash-deliveries",admin,request).andExpect(status().isOk())).path("deliveryId").asText());
  send("/teller/cash-deliveries/"+delivery+"/confirm",checker,Map.of("countedCash","4999.99","receiptEvidenceRef","COUNTED_TEST"))
    .andExpect(status().isConflict());
  send("/teller/cash-deliveries/"+delivery+"/confirm",admin,Map.of("countedCash","5000.00","receiptEvidenceRef","COUNTED_TEST"))
    .andExpect(status().isConflict());
  var confirmed=value(send("/teller/cash-deliveries/"+delivery+"/confirm",checker,
    Map.of("countedCash","5000.00","receiptEvidenceRef","COUNTED_TEST")).andExpect(status().isOk()));
  send("/teller/cash-deliveries/"+delivery+"/confirm",checker,
    Map.of("countedCash","5000.00","receiptEvidenceRef","COUNTED_TEST")).andExpect(status().isOk());
  assertEquals(0,new BigDecimal("5000").compareTo(db.queryForObject("SELECT CASH_BALANCE FROM MBX_BRANCH_VAULT WHERE BRANCH_CODE='DEL999'",BigDecimal.class)));
  assertEquals(0,reserveBefore.subtract(new BigDecimal("5000")).compareTo(db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class)));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER WHERE CASH_DELIVERY_ID=? AND RAIL_CODE='CASH' AND MOVEMENT_SIDE='OUT'",Integer.class,delivery));
  assertEquals(0,db.queryForObject("SELECT DIFFERENCE FROM M07_V_RESERVE_POSITION_RECON WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class).compareTo(BigDecimal.ZERO));
  assertEquals(0,db.queryForObject("SELECT DEBIT_TOTAL-CREDIT_TOTAL FROM M05_V_GL_JOURNAL_CONTROL WHERE JOURNAL_ID=?",BigDecimal.class,confirmed.path("journalId").asLong()).compareTo(BigDecimal.ZERO));
  String oversized=value(send("/teller/cash-deliveries",admin,Map.of("requestKey",UUID.randomUUID().toString(),"branchCode","DEL999",
    "reserveAccountId",1,"amount","2000000.00","shipmentRef","SIM-CASH-"+UUID.randomUUID(),"requestEvidenceRef","TEST_OVERSIZED"))
    .andExpect(status().isOk())).path("deliveryId").asText();
  send("/teller/cash-deliveries/"+oversized+"/confirm",checker,
    Map.of("countedCash","2000000.00","receiptEvidenceRef","COUNTED_OVERSIZED"))
    .andExpect(status().isConflict());
  assertEquals("PENDING",db.queryForObject("SELECT STATUS FROM MBX_RBI_CASH_DELIVERY WHERE DELIVERY_ID=?",String.class,oversized));
 }
 @Test @Order(6) void rateApprovalRejectsSelfApproval()throws Exception{
  String admin=login("admin"),checker=login("checker");var now=java.time.OffsetDateTime.now();
  String id=value(send("/fx/rates",admin,Map.of("baseCurrency","EUR","quoteCurrency","INR","buyRate","89","midRate","90","sellRate","91","sourceCode","TEST","observedAt",now.toString(),"validUntil",now.plusHours(1).toString(),"reason","Test quote")).andExpect(status().isOk())).path("rateId").asText();
  send("/fx/rates/"+id+"/decision",admin,Map.of("decision","APPROVED")).andExpect(status().isConflict());
  send("/fx/rates/"+id+"/decision",checker,Map.of("decision","APPROVED")).andExpect(status().isOk());
 }
 @Test @Order(7) void newCustomerHashIsReturnedOnceAndStoredAsDigest()throws Exception{
  String admin=login("admin");
  var created=value(send("/customer-access/users",admin,Map.of("username","new_customer","userType","CUSTOMER","password","StrongCustomer!123")).andExpect(status().isOk()));
  assertEquals(43,created.path("customerHash").asText().length());
  String id=created.path("user").path("userId").asText();
  assertEquals(32,db.queryForObject("SELECT SECRET_DIGEST FROM MBX_CUSTOMER_SECRET WHERE USER_ID=?",byte[].class,id).length);
  mvc.perform(get("/api/v1/iam/users/"+id).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.customerHash").doesNotExist());
 }
 @Test @Order(8) void customerStatementsUseSignedInOwnership()throws Exception{
  String customer=login("customer");String date=java.time.LocalDate.now().toString();
  var body=Map.of("accountId",1,"customerCifId","demo-cif-1","statementType","AD_HOC","periodStart",date,"periodEnd",date,"locale","en-IN","format","PDF","channel","WEB");
  send("/reporting/statement-requests",login("customer2"),body).andExpect(status().isForbidden());
  String request=value(send("/reporting/statement-requests",customer,body).andExpect(status().isCreated())).path("requestId").asText();
  var issued=value(mvc.perform(post("/api/v1/reporting/statement-requests/"+request+"/process").header("Authorization","Bearer "+customer)).andExpect(status().isOk()));
  assertEquals("COMPLETED",issued.path("status").asText());String id=issued.path("statementId").asText();
  var statement=value(mvc.perform(get("/api/v1/reporting/statements/"+id).header("Authorization","Bearer "+customer)).andExpect(status().isOk()));
  assertEquals(0,statement.path("closingBalance").decimalValue().compareTo(balance(1)));
  mvc.perform(get("/api/v1/reporting/statements/"+id+"/download").header("Authorization","Bearer "+login("customer2"))).andExpect(status().isForbidden());
  var pdf=mvc.perform(get("/api/v1/reporting/statements/"+id+"/download").header("Authorization","Bearer "+customer)).andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
  assertTrue(new String(pdf,java.nio.charset.StandardCharsets.US_ASCII).startsWith("%PDF-1.4"));
  java.nio.file.Files.write(java.nio.file.Path.of("target/test-statement.pdf"),pdf);
 }
 @Test @Order(9) void simulatedPaymentSettlesCustomerSuspenseAndReserve()throws Exception{
  String customer=login("customer"),checker=login("checker");
  BigDecimal reserveBefore=db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class);
  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Test recipient","accountToken","test-recipient-token","bankCode","TEST0123456")).andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
  BigDecimal before=balance(1);
  long payment=value(send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,"railCode","UPI","amount","500","requestKey",UUID.randomUUID().toString())).andExpect(status().isOk())).path("paymentId").asLong();
  send("/payments/"+payment+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isOk());
  assertEquals(0,before.subtract(new BigDecimal("500")).compareTo(balance(1)));
  String operator=login("admin");
  send("/payments/"+payment+"/simulate-outcome",operator,Map.of("outcome","ACCEPTED")).andExpect(status().isOk());
  send("/payments/"+payment+"/simulate-outcome",operator,Map.of("outcome","SETTLED")).andExpect(status().isOk());
  send("/payments/"+payment+"/simulate-outcome",operator,Map.of("outcome","SETTLED")).andExpect(status().isOk());
  assertEquals("SETTLED",db.queryForObject("SELECT STATUS FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",String.class,payment));
  assertEquals(0,reserveBefore.subtract(new BigDecimal("500")).compareTo(db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class)));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M05_V_GL_JOURNAL_CONTROL WHERE IS_BALANCED='N'",Integer.class));
 }
 @Test @Order(10) void loanOriginationDisbursementAndScheduleAreLinked()throws Exception{
  String admin=login("admin"),checker=login("checker"),customer=login("customer");
  long id=value(send("/loans/applications",admin,Map.of("cifId","demo-cif-1","productId",2,"productVersionId",2,"branchCode","MUM001","channelCode","BRANCH","amount","12000","tenureMonths",12,"purposeCode","PERSONAL")).andExpect(status().isOk())).path("data").path("id").asLong();
  send("/loans/applications/"+id+"/submit",admin,Map.of()).andExpect(status().isOk());
  send("/loans/applications/"+id+"/assessments",admin,Map.of("monthlyIncome","50000","monthlyObligations","5000","recommendation","APPROVE","reasonCodes","AFFORDABLE")).andExpect(status().isOk());
  var decision=Map.of("code","APPROVE","reasonCodes","APPROVED","sanctionedAmount","12000","sanctionedTenureMonths",12,"annualRatePct","12","authorityCode","LOAN_SANCTION","authorityEvidenceRef","DEMO-AUTHORITY");
  send("/loans/applications/"+id+"/decisions",admin,decision).andExpect(status().isConflict());
  send("/loans/applications/"+id+"/decisions",checker,decision).andExpect(status().isOk());
  long offer=value(send("/loans/applications/"+id+"/offers",admin,Map.of("documentRef","demo://offer","expiresAt",java.time.OffsetDateTime.now().plusDays(7).toString())).andExpect(status().isOk())).path("data").path("offerId").asLong();
  var customerOffers=value(mvc.perform(get("/api/v1/loans/applications/"+id+"/offers").header("Authorization","Bearer "+customer)).andExpect(status().isOk())).path("data");
  assertEquals("12000",customerOffers.get(0).path("SANCTIONED_AMOUNT").decimalValue().stripTrailingZeros().toPlainString());
  assertEquals(12,customerOffers.get(0).path("SANCTIONED_TENURE_MONTHS").asInt());
  assertEquals(0,new BigDecimal("12").compareTo(customerOffers.get(0).path("ANNUAL_RATE_PCT").decimalValue()));
  mvc.perform(get("/api/v1/loans/applications/"+id+"/offers").header("Authorization","Bearer "+login("customer2"))).andExpect(status().isForbidden());
  send("/loans/offers/"+offer+"/accept",admin,Map.of("disbursementAccountId",1,"repaymentAccountId",1)).andExpect(status().isForbidden());
  send("/loans/offers/"+offer+"/accept",customer,Map.of("disbursementAccountId",1,"repaymentAccountId",1)).andExpect(status().isOk());
  long doc=value(send("/loans/applications/"+id+"/documents",admin,Map.of("type","SIGNED_OFFER","storageReference","demo://signed-offer","sha256Hex","c".repeat(64))).andExpect(status().isOk())).path("data").path("documentRefId").asLong();
  send("/loans/applications/"+id+"/documents/"+doc+"/verify",admin,Map.of()).andExpect(status().isForbidden());
  send("/loans/applications/"+id+"/documents/"+doc+"/verify",checker,Map.of()).andExpect(status().isOk());
  long facility=value(send("/loans/applications/"+id+"/convert",admin,Map.of()).andExpect(status().isOk())).path("data").path("facilityId").asLong();
  long disbursement=value(send("/loans/facilities/"+facility+"/disbursements",admin,Map.of("requestKey",UUID.randomUUID().toString())).andExpect(status().isOk())).path("disbursementId").asLong();
  BigDecimal before=balance(1);
  send("/loans/disbursements/"+disbursement+"/approve",admin,Map.of("authorityCode","LOAN_SANCTION")).andExpect(status().isForbidden());
  send("/loans/disbursements/"+disbursement+"/approve",checker,Map.of("authorityCode","LOAN_SANCTION")).andExpect(status().isOk());
  send("/loans/disbursements/"+disbursement+"/approve",checker,Map.of("authorityCode","LOAN_SANCTION")).andExpect(status().isOk());
  assertEquals(0,before.add(new BigDecimal("12000")).compareTo(balance(1)));
  assertEquals(0,new BigDecimal("12000").compareTo(db.queryForObject("SELECT SUM(PRINCIPAL_DUE) FROM M08_LOAN_SCHEDULE_ITEM WHERE FACILITY_ID=?",BigDecimal.class,facility)));
  assertEquals(12,db.queryForObject("SELECT COUNT(*) FROM M08_LOAN_SCHEDULE_ITEM WHERE FACILITY_ID=?",Integer.class,facility));
  send("/loans/facilities/"+facility+"/accruals",admin,Map.of()).andExpect(status().isOk()).andExpect(content().json("[]"));
  send("/loans/facilities/"+facility+"/repayments",admin,Map.of("requestKey",UUID.randomUUID().toString(),"amount","1")).andExpect(status().isConflict());
  // Age one synthetic installment to exercise due servicing without changing production clocks.
  db.update("UPDATE M08_LOAN_SCHEDULE_ITEM SET DUE_DATE=? WHERE FACILITY_ID=? AND INSTALLMENT_NO=1",java.time.LocalDate.now(),facility);
  BigDecimal interest=db.queryForObject("SELECT INTEREST_DUE FROM M08_LOAN_SCHEDULE_ITEM WHERE FACILITY_ID=? AND INSTALLMENT_NO=1",BigDecimal.class,facility);
  send("/loans/facilities/"+facility+"/accruals",admin,Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
  send("/loans/facilities/"+facility+"/accruals",admin,Map.of()).andExpect(status().isOk()).andExpect(content().json("[]"));
  var repayment=Map.of("requestKey",UUID.randomUUID().toString(),"amount",interest.add(new BigDecimal("1000")).toPlainString());
  BigDecimal repaymentBefore=balance(1);
  send("/loans/facilities/"+facility+"/repayments",admin,repayment).andExpect(status().isOk());
  send("/loans/facilities/"+facility+"/repayments",admin,repayment).andExpect(status().isOk());
  assertEquals(0,repaymentBefore.subtract(interest).subtract(new BigDecimal("1000")).compareTo(balance(1)));
  assertEquals("PAID",db.queryForObject("SELECT STATUS FROM M08_LOAN_SCHEDULE_ITEM WHERE FACILITY_ID=? AND INSTALLMENT_NO=1",String.class,facility));
  assertEquals(0,new BigDecimal("11000").compareTo(db.queryForObject("SELECT PRINCIPAL_OUTSTANDING FROM M08_LOAN_FACILITY WHERE FACILITY_ID=?",BigDecimal.class,facility)));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M05_V_GL_JOURNAL_CONTROL WHERE IS_BALANCED='N'",Integer.class));
 }
 @Test @Order(11) void overrideApprovalIsBoundToExactCommand()throws Exception{
  String admin=login("admin"),checker=login("checker");
  String checkerId=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='checker'",String.class);
  db.update("INSERT INTO M03_PM_OVERRIDE_POLICY(OVERRIDE_POLICY_ID,PRODUCT_VERSION_ID,RULE_FAMILY,RULE_CODE,FIELD_CODE,AUTHORITY_CODE,MIN_VALUE,MAX_VALUE,VALID_FROM) VALUES (900,1,'INTEREST','BASE','RATE_PCT','LOAN_SANCTION',0,20,SYSTIMESTAMP)");
  String request=UUID.randomUUID().toString();var command=new LinkedHashMap<String,Object>();
  command.put("requestId",request);command.put("overridePolicyId",900);command.put("ratePct","4");command.put("reason","Negotiated demo rate");command.put("effectiveFrom",java.time.OffsetDateTime.now().toString());command.put("approvedByUserId",checkerId);
  send("/accounts/1/override-approvals",admin,Map.of("interest",command)).andExpect(status().isOk());
  send("/accounts/1/override-approvals/"+request+"/decision",admin,Map.of("decision","APPROVED","reason","Self approval attempt")).andExpect(status().isForbidden());
  send("/accounts/1/override-approvals/"+request+"/decision",checker,Map.of("decision","APPROVED","reason","Within authority")).andExpect(status().isOk());
  var altered=new LinkedHashMap<>(command);altered.put("ratePct","5");
  send("/accounts/1/interest-overrides",admin,altered).andExpect(status().isConflict());
  send("/accounts/1/interest-overrides",admin,command).andExpect(status().isCreated());
  assertEquals("APPLIED",db.queryForObject("SELECT STATUS FROM MBX_OVERRIDE_APPROVAL WHERE REQUEST_ID=?",String.class,request));
 }
 @Test @Order(12) void freezingAnAccountBlocksDebitsUntilRelease()throws Exception{
  String admin=login("admin"),customer=login("customer");
  send("/accounts/1/restrictions",admin,Map.of("requestId",UUID.randomUUID().toString(),"type","DEBIT_BLOCK","reasonCode","TEST_REVIEW")).andExpect(status().isAccepted());
  send("/transactions/transfers",customer,transfer(1,2,"10",UUID.randomUUID().toString())).andExpect(status().isConflict());
  long restriction=db.queryForObject("SELECT MAX(RESTRICTION_ID) FROM M04_ACCOUNT_RESTRICTION WHERE ACCOUNT_ID=1",Long.class);
  send("/accounts/1/restrictions/"+restriction+"/release",admin,Map.of("requestId",UUID.randomUUID().toString())).andExpect(status().isAccepted());
  send("/transactions/transfers",customer,transfer(1,2,"10",UUID.randomUUID().toString())).andExpect(status().isOk());
 }

 @Test @Order(13) void fixedFeeCollectionIsBalancedScopedAndIdempotent()throws Exception{
  String admin=login("admin"),customer=login("customer");
  db.update("INSERT INTO M03_PM_FEE_RULE(FEE_RULE_ID,PRODUCT_VERSION_ID,RULE_CODE,TRIGGER_CODE,CHARGE_BASIS,FIXED_AMOUNT,CHARGE_FREQUENCY) VALUES (901,1,'SERVICE_FEE','MANUAL','FIXED',25,'ONCE')");
  db.update("INSERT INTO M05_GL_PRODUCT_MAPPING(PRODUCT_VERSION_ID,POSTING_TYPE,GL_ROLE_CODE,GL_ACCOUNT_ID,EFFECTIVE_FROM,CREATED_BY) SELECT 1,'FEE','CUSTOMER_LIABILITY',1,SYSDATE,USER_ID FROM M01_IAM_USER WHERE USERNAME='admin'");
  db.update("INSERT INTO M05_GL_PRODUCT_MAPPING(PRODUCT_VERSION_ID,POSTING_TYPE,GL_ROLE_CODE,GL_ACCOUNT_ID,EFFECTIVE_FROM,CREATED_BY) SELECT 1,'FEE','FEE_INCOME',5,SYSDATE,USER_ID FROM M01_IAM_USER WHERE USERNAME='admin'");
  var fee=new LinkedHashMap<String,Object>();fee.put("feeKey",UUID.randomUUID().toString());fee.put("bankAccountId",1);fee.put("productFeeRuleId",901);fee.put("assessedAmount","25");fee.put("dueDate",java.time.LocalDate.now().toString());
  long id=value(send("/fees",admin,fee).andExpect(status().isOk())).path("feeAssessmentId").asLong();
  fee.put("bankAccountId",2);send("/fees",admin,fee).andExpect(status().isConflict());
  send("/fees/"+id+"/collect",customer,Map.of()).andExpect(status().isForbidden());
  BigDecimal before=balance(1);send("/fees/"+id+"/collect",admin,Map.of()).andExpect(status().isOk());send("/fees/"+id+"/collect",admin,Map.of()).andExpect(status().isOk());
  assertEquals(0,before.subtract(new BigDecimal("25")).compareTo(balance(1)));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M05_V_GL_JOURNAL_CONTROL WHERE IS_BALANCED='N'",Integer.class));
 }

 @Test @Order(14) void branchRoleCannotOperateAnotherBranch()throws Exception{
  String checkerId=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='checker'",String.class);
  db.update("UPDATE M01_IAM_USER_ROLE SET SCOPE_TYPE='BRANCH',SCOPE_REF='DEL999' WHERE USER_ID=?",checkerId);
  try{
   String checker=login("checker");
   send("/transactions/transfers",checker,transfer(1,2,"1",UUID.randomUUID().toString())).andExpect(status().isForbidden());
   send("/transactions/1/reversal-requests",checker,Map.of("requestKey",UUID.randomUUID().toString(),"reasonText","Scope check","correlationId",UUID.randomUUID().toString(),"valueDate",java.time.LocalDate.now().toString())).andExpect(status().isForbidden());
   send("/accounts",checker,Map.of("requestId",UUID.randomUUID().toString(),"primaryCifId","demo-cif-1","productId",1,"productVersionId",1,"branchCode","MUM001","operationMode","SELF_OPERATED")).andExpect(status().isForbidden());
  }finally{db.update("UPDATE M01_IAM_USER_ROLE SET SCOPE_TYPE='GLOBAL',SCOPE_REF=NULL WHERE USER_ID=?",checkerId);}
 }
 @Test @Order(18) void publicCustomerSignupCreatesVerifiedSelfScopedIdentity()throws Exception{
  String username="new_signup_"+UUID.randomUUID().toString().substring(0,8),password="NewCustomer!2026";
  var body=Map.of("username",username,"legalName","New Demo Customer","dateOfBirth","1995-04-12","password",password);
  mvc.perform(get("/api/v1/auth/signup-availability")).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
  var result=mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn();
  String cif=json.readTree(result.getResponse().getContentAsString()).path("data").path("cifId").asText();
  assertEquals("ACTIVE",db.queryForObject("SELECT STATUS FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",String.class,cif));
  assertEquals("VERIFIED",db.queryForObject("SELECT KYC_STATUS FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",String.class,cif));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M02_KYC_CASE WHERE CIF_ID=? AND STATUS='APPROVED' AND DECIDED_AT IS NOT NULL",Integer.class,cif));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M01_IAM_CUSTOMER_LINK L JOIN M01_IAM_USER U ON U.USER_ID=L.USER_ID WHERE U.USERNAME=? AND L.CIF_ID=? AND L.STATUS='ACTIVE'",Integer.class,username,cif));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M01_IAM_USER_ROLE A JOIN M01_IAM_USER U ON U.USER_ID=A.USER_ID JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID WHERE U.USERNAME=? AND A.SCOPE_TYPE='SELF' AND R.ROLE_CODE='RETAIL_CUSTOMER'",Integer.class,username));
  var signedIn=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",username,"password",password,"clientId","MONEYBAGS_WEB")))).andExpect(status().isOk()).andReturn();
  String token=json.readTree(signedIn.getResponse().getContentAsString()).path("data").path("accessToken").asText();
  var offers=mvc.perform(get("/api/v1/products/customer-offers").param("cifId",cif).param("channel","BRANCH").param("currency","INR").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn();
  boolean savingsOffer=false;for(JsonNode offer:json.readTree(offers.getResponse().getContentAsString()).path("data"))if(offer.path("product").path("PRODUCT_ID").asInt()==1)savingsOffer=true;
  assertTrue(savingsOffer);
  mvc.perform(post("/api/v1/accounts/self").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"cifId","demo-cif-1","productId",1,"productVersionId",1)))).andExpect(status().isForbidden());
  var opening=mvc.perform(post("/api/v1/accounts/self").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"cifId",cif,"productId",1,"productVersionId",1)))).andExpect(status().isCreated()).andReturn();
  String accountId=json.readTree(opening.getResponse().getContentAsString()).path("id").asText();
  mvc.perform(post("/api/v1/accounts/self/"+accountId+"/activate").header("Authorization","Bearer "+login("customer2")).contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString())))).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/accounts/self/"+accountId+"/activate").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString())))).andExpect(status().isAccepted());
  assertEquals("ACTIVE",db.queryForObject("SELECT ACCOUNT_STATUS FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?",String.class,Long.parseLong(accountId)));
  mvc.perform(get("/api/v1/banking/my-dashboard").header("Authorization","Bearer "+token)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/accounts/1/position").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isConflict());
  var minorBody=Map.of("username","minor_signup_"+UUID.randomUUID().toString().substring(0,8),"legalName","Minor Demo","dateOfBirth","2015-04-12","password","NewCustomer!2026");
  mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(minorBody))).andExpect(status().isBadRequest());
  var adminBody=Map.of("username","staff_signup_"+UUID.randomUUID().toString().substring(0,8),"legalName","Staff Created Customer","dateOfBirth","1993-06-20","password","NewCustomer!2026");
  mvc.perform(post("/api/v1/iam/users/demo-customer").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(adminBody))).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/iam/users/demo-customer").header("Authorization","Bearer "+login("admin")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(adminBody))).andExpect(status().isOk());
 }

 /** Customer self-service intake is scoped, idempotent and immediately enters the staff review queue. */
 @Test @Order(19) void customerCanApplyOnlyForOwnCifAndSeeOwnApplication()throws Exception{
  String customer=login("customer"),other=login("customer2"),admin=login("admin"),key=UUID.randomUUID().toString();
  var request=Map.of("cifId","demo-cif-1","productId",2,"productVersionId",2,
      "amount","12000","tenureMonths",12,"purposeCode","PERSONAL");
  var owned=mvc.perform(post("/api/v1/loans/my/applications").header("Authorization","Bearer "+customer)
      .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(request))).andExpect(status().isOk())
      .andExpect(jsonPath("$.data.status").value("SUBMITTED")).andReturn();
  long id=json.readTree(owned.getResponse().getContentAsString()).path("data").path("id").asLong();
  assertTrue(id>0);
  mvc.perform(get("/api/v1/loans/my/applications").header("Authorization","Bearer "+customer))
      .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.id == "+id+")].status").value("SUBMITTED"));
  mvc.perform(get("/api/v1/loans/my/applications").header("Authorization","Bearer "+other))
      .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.id == "+id+")]").isEmpty());
  mvc.perform(post("/api/v1/loans/my/applications").header("Authorization","Bearer "+customer)
      .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(request))).andExpect(status().isOk())
      .andExpect(jsonPath("$.data.id").value(id));
  var changed=new HashMap<>(request);changed.put("amount","13000");
  mvc.perform(post("/api/v1/loans/my/applications").header("Authorization","Bearer "+customer)
      .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(changed))).andExpect(status().isConflict());
  mvc.perform(post("/api/v1/loans/my/applications").header("Authorization","Bearer "+other)
      .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(request))).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/loans/my/applications").header("Authorization","Bearer "+admin)
      .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(request))).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/loans/applications").param("branch","MUM001").header("Authorization","Bearer "+admin))
      .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.id == "+id+")].status").value("SUBMITTED"));
  send("/loans/applications/"+id+"/assessments",admin,Map.of("monthlyIncome","50000","monthlyObligations","5000","recommendation","APPROVE","reasonCodes","AFFORDABLE")).andExpect(status().isOk());
  send("/loans/applications/"+id+"/decisions",login("checker"),Map.of("code","APPROVE","reasonCodes","APPROVED","sanctionedAmount","12000","sanctionedTenureMonths",12,"annualRatePct","12","authorityCode","LOAN_SANCTION","authorityEvidenceRef","DEMO-AUTHORITY")).andExpect(status().isOk());
  long offer=value(send("/loans/applications/"+id+"/offers",admin,Map.of("documentRef","demo://customer-offer","expiresAt",java.time.OffsetDateTime.now().plusDays(7).toString())).andExpect(status().isOk())).path("data").path("offerId").asLong();
  send("/loans/offers/"+offer+"/accept",customer,Map.of("disbursementAccountId",1,"repaymentAccountId",1)).andExpect(status().isOk());
  long document=value(send("/loans/applications/"+id+"/documents",admin,Map.of("type","SIGNED_OFFER","storageReference","demo://customer-signed-offer","sha256Hex","d".repeat(64))).andExpect(status().isOk())).path("data").path("documentRefId").asLong();
  send("/loans/applications/"+id+"/documents/"+document+"/verify",login("checker"),Map.of()).andExpect(status().isOk());
  long facility=value(send("/loans/applications/"+id+"/convert",admin,Map.of()).andExpect(status().isOk())).path("data").path("facilityId").asLong();
  long disbursement=value(send("/loans/facilities/"+facility+"/disbursements",admin,Map.of("requestKey",UUID.randomUUID().toString())).andExpect(status().isOk())).path("disbursementId").asLong();
  send("/loans/disbursements/"+disbursement+"/approve",login("checker"),Map.of("authorityCode","LOAN_SANCTION")).andExpect(status().isOk());
  mvc.perform(get("/api/v1/loans/facilities/"+facility).header("Authorization","Bearer "+customer))
      .andExpect(status().isOk()).andExpect(jsonPath("$.data.STATUS").value("ACTIVE"));
  mvc.perform(get("/api/v1/loans/facilities/"+facility+"/schedule").header("Authorization","Bearer "+customer))
      .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(12));
  mvc.perform(get("/api/v1/loans/facilities/"+facility).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
 }

 @Test @Order(15) void assistantMcpEnforcesScopeAndExactConfirmation()throws Exception{
  String customer=login("customer"),other=login("customer2"),checker=login("checker"),admin=login("admin");
  mvc.perform(post("/api/v1/assistant/mcp").contentType(MediaType.APPLICATION_JSON)
    .header("MCP-Protocol-Version","2026-07-28").header("Mcp-Method","tools/list")
    .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"))
    .andExpect(status().isUnauthorized());
  JsonNode customerList=mcp(customer,"tools/list",null,Map.of()).path("result").path("tools");
  assertTrue(customerList.toString().contains("list_my_accounts"));
  assertTrue(customerList.toString().contains("get_my_account_overview"));
  assertTrue(customerList.toString().contains("list_my_recent_transactions"));
  assertTrue(customerList.toString().contains("open_add_beneficiary_form"));
  assertFalse(customerList.toString().contains("search_customers"));
  JsonNode adminList=mcp(admin,"tools/list",null,Map.of()).path("result").path("tools");
  assertTrue(adminList.toString().contains("search_customers"));
  assertFalse(adminList.toString().contains("open_add_beneficiary_form"));
  assertFalse(adminList.toString().contains("draft_payment"));
  assertTrue(mcp(other,"tools/call","get_account_position",Map.of("accountId",1)).path("result").path("isError").asBoolean());
  assertFalse(mcp(customer,"tools/call","get_account_position",Map.of("accountId",1)).path("result").path("isError").asBoolean());
  assertEquals(1,toolValue(mcp(customer,"tools/call","get_my_account_overview",Map.of())).path("accounts").get(0).path("accountId").asInt());
  assertTrue(toolValue(mcp(customer,"tools/call","list_my_recent_transactions",Map.of())).isArray());
  int beforeForm=db.queryForObject("SELECT COUNT(*) FROM MBX_BENEFICIARY",Integer.class);
  JsonNode form=toolValue(mcp(customer,"tools/call","open_add_beneficiary_form",Map.of("displayName","Ravi")));
  assertEquals("ADD_BENEFICIARY",form.path("uiAction").asText());
  assertEquals("Ravi",form.path("displayName").asText());
  assertEquals(beforeForm,db.queryForObject("SELECT COUNT(*) FROM MBX_BENEFICIARY",Integer.class));

  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Assistant test","accountToken","testacct001","bankCode","ABCD0123456"))
    .andExpect(status().isOk())).path("beneficiaryId").asText();
  String verifyId=toolValue(mcp(admin,"tools/call","draft_beneficiary_verification",Map.of("beneficiaryId",beneficiary))).path("intentId").asText();
  send("/assistant/intents/"+verifyId+"/confirm",customer,Map.of()).andExpect(status().isForbidden());
  send("/assistant/intents/"+verifyId+"/confirm",admin,Map.of()).andExpect(status().isOk())
    .andExpect(jsonPath("$.action").value("BENEFICIARY_VERIFY"));
  send("/assistant/intents/"+verifyId+"/confirm",admin,Map.of()).andExpect(status().isOk())
    .andExpect(jsonPath("$.action").value("BENEFICIARY_VERIFY"));
  assertEquals("ACTIVE",db.queryForObject("SELECT STATUS FROM MBX_BENEFICIARY WHERE BENEFICIARY_ID=?",String.class,beneficiary));

  String paymentId=toolValue(mcp(customer,"tools/call","draft_payment",Map.of("accountId",1,"beneficiaryId",beneficiary,"rail","UPI","amount","1.00"))).path("intentId").asText();
  assertFalse(paymentId.isBlank());
  send("/assistant/intents/"+paymentId+"/confirm",other,Map.of()).andExpect(status().isForbidden());
  send("/assistant/intents/"+paymentId+"/confirm",login("customer"),Map.of()).andExpect(status().isForbidden());
  JsonNode first=value(send("/assistant/intents/"+paymentId+"/confirm",customer,Map.of()).andExpect(status().isOk()));
  JsonNode replay=value(send("/assistant/intents/"+paymentId+"/confirm",customer,Map.of()).andExpect(status().isOk()));
  assertEquals(first.path("resultRef").asText(),replay.path("resultRef").asText());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION WHERE REQUEST_KEY=?",Integer.class,paymentId));
  String expired=toolValue(mcp(customer,"tools/call","draft_payment",Map.of("accountId",1,"beneficiaryId",beneficiary,"rail","UPI","amount","2.00"))).path("intentId").asText();
  db.update("UPDATE MBX_ASSISTANT_INTENT SET EXPIRES_AT=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP) WHERE INTENT_ID=?",expired);
  send("/assistant/intents/"+expired+"/confirm",customer,Map.of()).andExpect(status().isConflict());
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION WHERE REQUEST_KEY=?",Integer.class,expired));
 }
 @Test @Order(16) void customerMcpTracksPaymentsAndGeneratesScopedStatements()throws Exception{
  String customer=login("customer"),other=login("customer2");
  JsonNode tools=mcp(customer,"tools/list",null,Map.of()).path("result").path("tools");
  assertTrue(tools.toString().contains("generate_my_statement"));
  assertTrue(tools.toString().contains("list_my_last_transactions"));
  assertTrue(toolValue(mcp(customer,"tools/call","list_my_last_transactions",Map.of("limit",3))).size()<=3);
  assertTrue(mcp(customer,"tools/call","list_my_last_transactions",Map.of("limit",101)).path("result").path("isError").asBoolean());
  assertTrue(mcp(other,"tools/call","list_recent_transactions",Map.of("accountId",1,"limit",3)).path("result").path("isError").asBoolean());
  String payee=db.queryForObject("SELECT BENEFICIARY_ID FROM MBX_BENEFICIARY WHERE DISPLAY_NAME='Assistant test'",String.class);
  assertTrue(mcp(customer,"tools/call","draft_payment",Map.of("accountId",1,"beneficiaryId",payee,"rail","RTGS","amount","1.00")).path("result").path("isError").asBoolean());
  JsonNode payments=toolValue(mcp(customer,"tools/call","list_my_payments",Map.of("limit",5)));
  assertTrue(payments.isArray());
  assertTrue(payments.size()>0);
  long paymentId=payments.get(0).path("paymentId").asLong();
  assertFalse(toolValue(mcp(customer,"tools/call","get_my_payment_status",Map.of("paymentId",paymentId))).path("status").asText().isBlank());
  assertTrue(mcp(other,"tools/call","get_my_payment_status",Map.of("paymentId",paymentId)).path("result").path("isError").asBoolean());
  String yesterday=java.time.LocalDate.now().minusDays(1).toString();
  var period=Map.of("accountId",1,"periodStart",yesterday,"periodEnd",yesterday,"format","PDF");
  assertTrue(mcp(other,"tools/call","generate_my_statement",period).path("result").path("isError").asBoolean());
  JsonNode generated=toolValue(mcp(customer,"tools/call","generate_my_statement",period));
  assertEquals("COMPLETED",generated.path("status").asText());
  String statementId=generated.path("statementId").asText();
  assertFalse(statementId.isBlank());
  assertEquals("EXISTING",toolValue(mcp(customer,"tools/call","generate_my_statement",period)).path("status").asText());
  assertTrue(toolValue(mcp(customer,"tools/call","get_my_statement",Map.of("statementId",statementId))).path("downloadPath").asText().contains(statementId));
  assertTrue(toolValue(mcp(customer,"tools/call","list_my_statement_requests",Map.of("accountId",1,"limit",10))).isArray());
  assertTrue(mcp(other,"tools/call","get_my_statement",Map.of("statementId",statementId)).path("result").path("isError").asBoolean());
 }
 @Test @Order(17) void assistantInternalTransferCreditsOnlyAfterExactConfirmation()throws Exception{
  String customer=login("customer"),other=login("customer2"),checker=login("checker");
  String targetNumber=db.queryForObject("SELECT ACCOUNT_NUMBER FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=2",String.class);
  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Internal recipient","accountToken",targetNumber,"bankCode","MCPB0000002"))
    .andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
  JsonNode listed=toolValue(mcp(customer,"tools/call","list_my_beneficiaries",Map.of()));
  assertTrue(listed.toString().contains("INTERNAL"));
  assertTrue(mcp(customer,"tools/call","draft_payment",Map.of("accountId",1,"beneficiaryId",beneficiary,"rail","UPI","amount","1.00"))
    .path("result").path("isError").asBoolean());
  assertTrue(mcp(other,"tools/call","draft_internal_transfer",Map.of("accountId",2,"beneficiaryId",beneficiary,"amount","1.00"))
    .path("result").path("isError").asBoolean());
  BigDecimal sourceBefore=balance(1),targetBefore=balance(2);
  String intent=toolValue(mcp(customer,"tools/call","draft_internal_transfer",Map.of("accountId",1,"beneficiaryId",beneficiary,"amount","1.00")))
    .path("intentId").asText();
  assertEquals(0,sourceBefore.compareTo(balance(1)));
  assertEquals(0,targetBefore.compareTo(balance(2)));
  send("/assistant/intents/"+intent+"/confirm",other,Map.of()).andExpect(status().isForbidden());
  send("/assistant/intents/"+intent+"/confirm",login("customer"),Map.of()).andExpect(status().isForbidden());
  JsonNode posted=value(send("/assistant/intents/"+intent+"/confirm",customer,Map.of()).andExpect(status().isOk()));
  assertEquals("INTERNAL_TRANSFER",posted.path("action").asText());
  assertEquals(0,sourceBefore.subtract(BigDecimal.ONE).compareTo(balance(1)));
  assertEquals(0,targetBefore.add(BigDecimal.ONE).compareTo(balance(2)));
  assertEquals(posted.path("resultRef").asText(),value(send("/assistant/intents/"+intent+"/confirm",customer,Map.of()).andExpect(status().isOk())).path("resultRef").asText());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M05_TXN_TRANSACTION_LOG WHERE REQUEST_KEY=? AND STATUS='POSTED'",Integer.class,intent));
  String externalSameNumber=value(send("/beneficiaries",customer,Map.of("displayName","External number collision","accountToken",targetNumber,"bankCode","ABCD0123456"))
    .andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+externalSameNumber+"/verify",checker,Map.of()).andExpect(status().isOk());
  assertTrue(mcp(customer,"tools/call","draft_internal_transfer",Map.of("accountId",1,"beneficiaryId",externalSameNumber,"amount","1.00"))
    .path("result").path("isError").asBoolean());
  assertEquals("PAYMENT_INITIATE",toolValue(mcp(customer,"tools/call","draft_payment",Map.of("accountId",1,"beneficiaryId",externalSameNumber,"rail","UPI","amount","1.00")))
    .path("action").asText());
 }
 @Test @Order(18) void administratorInheritsAllPermissionsAndCheckerCannotInitiateWrites()throws Exception{
  String admin=login("admin"),checker=login("checker");
  assertEquals(db.queryForObject("SELECT COUNT(*) FROM M01_IAM_PERMISSION",Integer.class),
    db.queryForObject("SELECT COUNT(*) FROM M01_IAM_ROLE_PERMISSION RP JOIN M01_IAM_ROLE R ON R.ROLE_ID=RP.ROLE_ID WHERE R.ROLE_CODE='BANK_ADMIN'",Integer.class));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M01_IAM_ROLE_PERMISSION RP JOIN M01_IAM_ROLE R ON R.ROLE_ID=RP.ROLE_ID JOIN M01_IAM_PERMISSION P ON P.PERMISSION_ID=RP.PERMISSION_ID WHERE R.ROLE_CODE='BANK_CHECKER' AND P.PERMISSION_CODE IN ('TXN_POST','PAYMENT_OPERATE','TREASURY_RECONCILE','IAM_ROLE_MANAGE','STATEMENT_ADMIN')",Integer.class));
  mvc.perform(get("/api/v1/treasury/reserve-accounts/1/ledger").header("Authorization","Bearer "+checker)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/banking/gl-accounts").header("Authorization","Bearer "+checker)).andExpect(status().isOk());
  send("/transactions/transfers",checker,transfer(1,2,"1.00",UUID.randomUUID().toString())).andExpect(status().isForbidden());
  send("/payments/1/simulate-outcome",checker,Map.of("outcome","SETTLED")).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/assistant/mcp").header("Authorization","Bearer "+checker).contentType(MediaType.APPLICATION_JSON).content("{}"))
    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHECKER_APPROVAL_ONLY"));
  mvc.perform(get("/api/v1/treasury/reserve-accounts/1/ledger").header("Authorization","Bearer "+admin)).andExpect(status().isOk());
 }
 @Test @Order(19) void syntheticReserveOpeningRequiresIndependentApprovalAndBalancesBothBooks()throws Exception{
  String admin=login("admin"),checker=login("checker");
  db.update("INSERT INTO M05_GL_ACCOUNT(GL_ACCOUNT_ID,GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE) VALUES (91,'OPENING_RBI','Synthetic opening RBI reserve','ASSET','DR')");
  db.update("INSERT INTO M05_GL_ACCOUNT(GL_ACCOUNT_ID,GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE) VALUES (92,'OPENING_CAPITAL','Synthetic opening capital','EQUITY','CR')");
  db.update("INSERT INTO M07_RESERVE_ACCOUNT(RESERVE_ACCOUNT_ID,RESERVE_ACCOUNT_CODE,ACCOUNT_TYPE,EXTERNAL_ACCOUNT_REF,GL_ACCOUNT_ID) VALUES (77,'OPENING-TEST','RBI_CURRENT','SIMULATED-OPENING-TEST',91)");
  db.update("INSERT INTO M07_RESERVE_POSITION(RESERVE_ACCOUNT_ID,CONFIRMED_BALANCE,ACTIVE_HOLD_AMOUNT) VALUES (77,0,0)");
  var command=Map.of("requestKey",UUID.randomUUID().toString(),"reserveAccountId",77,"equityGlId",92,
    "amount","50000.00","evidenceRef","DEMO-OPENING-RESERVE-TEST");
  send("/treasury/reserve-openings",checker,command).andExpect(status().isForbidden());
  String opening=value(send("/treasury/reserve-openings",admin,command).andExpect(status().isOk())).path("openingId").asText();
  assertEquals(opening,value(send("/treasury/reserve-openings",admin,command).andExpect(status().isOk())).path("openingId").asText());
  send("/treasury/reserve-openings/"+opening+"/confirm",admin,Map.of()).andExpect(status().isConflict());
  var confirmed=value(send("/treasury/reserve-openings/"+opening+"/confirm",checker,Map.of()).andExpect(status().isOk()));
  send("/treasury/reserve-openings/"+opening+"/confirm",checker,Map.of()).andExpect(status().isOk());
  assertEquals(0,new BigDecimal("50000").compareTo(db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=77",BigDecimal.class)));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER WHERE RESERVE_OPENING_ID=? AND RAIL_CODE='RBI' AND MOVEMENT_SIDE='IN'",Integer.class,opening));
  assertEquals(0,db.queryForObject("SELECT DIFFERENCE FROM M07_V_RESERVE_POSITION_RECON WHERE RESERVE_ACCOUNT_ID=77",BigDecimal.class).compareTo(BigDecimal.ZERO));
  assertEquals(0,db.queryForObject("SELECT DEBIT_TOTAL-CREDIT_TOTAL FROM M05_V_GL_JOURNAL_CONTROL WHERE JOURNAL_ID=?",BigDecimal.class,confirmed.path("journalId").asLong()).compareTo(BigDecimal.ZERO));
  assertEquals(0,new BigDecimal("50000").compareTo(db.queryForObject("SELECT SUM(AMOUNT) FROM M05_GL_POSTING WHERE GL_ACCOUNT_ID=91 AND ENTRY_SIDE='DR'",BigDecimal.class)));
  assertEquals(0,new BigDecimal("50000").compareTo(db.queryForObject("SELECT SUM(AMOUNT) FROM M05_GL_POSTING WHERE GL_ACCOUNT_ID=92 AND ENTRY_SIDE='CR'",BigDecimal.class)));
 }
 JsonNode mcp(String token,String method,String name,Object arguments)throws Exception{
  Map<String,Object> params="tools/call".equals(method)?Map.of("name",name,"arguments",arguments):Map.of();
  var request=post("/api/v1/assistant/mcp").contentType(MediaType.APPLICATION_JSON)
    .header("Authorization","Bearer "+token).header("MCP-Protocol-Version","2026-07-28")
    .header("Mcp-Method",method).content(json.writeValueAsString(Map.of("jsonrpc","2.0","id",1,"method",method,"params",params)));
  if(name!=null)request.header("Mcp-Name",name);
  return value(mvc.perform(request).andExpect(status().isOk()));
 }
 JsonNode toolValue(JsonNode response)throws Exception{
  assertFalse(response.path("result").path("isError").asBoolean(),response.toString());
  return json.readTree(response.path("result").path("content").get(0).path("text").asText());
 }

 @Test @Order(20) void fixedDepositFundingAndMaturityAreBalancedAndScoped()throws Exception{
  String customer=login("customer"),other=login("customer2"),admin=login("admin");
  BigDecimal before=balance(1);String key=UUID.randomUUID().toString();
  assertTrue(before.compareTo(new BigDecimal("1000000.00"))<0);
  String unfundedKey=UUID.randomUUID().toString();
  send("/term-deposits",customer,Map.of("productId",3,"productVersionId",3,"fundingAccountId",1,
    "amount","1000000.00","tenureMonths",1,"requestKey",unfundedKey))
    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
    .andExpect(jsonPath("$.message").value("Spendable funds are insufficient"));
  assertEquals(0,before.compareTo(balance(1)));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM MBX_TERM_DEPOSIT WHERE REQUEST_KEY=?",Integer.class,unfundedKey));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M05_TXN_TRANSACTION_LOG WHERE REQUEST_KEY=?",Integer.class,"term-fund:"+unfundedKey));
  var request=Map.of("productId",3,"productVersionId",3,"fundingAccountId",1,"amount","1000.00","tenureMonths",1,"requestKey",key);
  long id=value(send("/term-deposits",customer,request).andExpect(status().isOk())).path("TERM_DEPOSIT_ID").asLong();
  assertTrue(id>0);
  assertEquals(0,before.subtract(new BigDecimal("1000.00")).compareTo(balance(1)));
  assertEquals(id,value(send("/term-deposits",customer,request).andExpect(status().isOk())).path("TERM_DEPOSIT_ID").asLong());
  assertEquals(0,before.subtract(new BigDecimal("1000.00")).compareTo(balance(1)));
  send("/term-deposits",other,Map.of("productId",3,"productVersionId",3,"fundingAccountId",1,"amount","1000.00","tenureMonths",1,"requestKey",UUID.randomUUID().toString())).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/term-deposits/"+id).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
  send("/term-deposits",customer,Map.of("productId",3,"productVersionId",3,"fundingAccountId",1,"amount","1000.00","tenureMonths",2,"requestKey",key)).andExpect(status().isConflict());
  send("/term-deposits/"+id+"/mature",admin,Map.of()).andExpect(status().isConflict());
  db.update("UPDATE MBX_TERM_DEPOSIT SET START_DATE=?,MATURITY_DATE=? WHERE TERM_DEPOSIT_ID=?",
    java.time.LocalDate.now().minusDays(2),java.time.LocalDate.now().minusDays(1),id);
  var matured=value(send("/term-deposits/"+id+"/mature",admin,Map.of()).andExpect(status().isOk()));
  assertEquals("MATURED",matured.path("STATUS").asText());
  BigDecimal earned=db.queryForObject("SELECT MATURITY_INTEREST FROM MBX_TERM_DEPOSIT WHERE TERM_DEPOSIT_ID=?",BigDecimal.class,id);
  assertEquals(0,before.add(earned).compareTo(balance(1)));
  send("/term-deposits/"+id+"/mature",admin,Map.of()).andExpect(status().isOk());
  assertEquals(0,before.add(earned).compareTo(balance(1)));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M05_V_GL_JOURNAL_CONTROL WHERE IS_BALANCED='N'",Integer.class));
 }

 @Test @Order(21) void monthlySavingsInterestPostsOnceAndAppearsForOwner()throws Exception{
  String customer=login("customer"),other=login("customer2"),admin=login("admin");
  java.time.YearMonth month=java.time.YearMonth.now(java.time.ZoneId.of("Asia/Kolkata")).minusMonths(1);
  db.update("UPDATE MBX_INTEREST_ROLLOUT SET START_DATE=? WHERE CONFIG_ID=1",month.atDay(1));
  db.update("UPDATE M04_BANK_ACCOUNT SET OPENED_AT=?,ACTIVATED_AT=? WHERE ACCOUNT_ID=1",
    month.atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime(),
    month.atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime());
  String adminId=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='admin'",String.class);
  String key=UUID.randomUUID().toString();
  db.update("INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,TARGET_ACCOUNT_ID,PRODUCT_VERSION_ID,AMOUNT,VALUE_DATE) VALUES (?,'BRANCH',?,?,?,'DEPOSIT','POSTED',1,1,1000,?)",
    adminId,key,com.moneybags.integration.CustomerHashService.digest(key),key,month.atDay(1));
  long txn=db.queryForObject("SELECT TXN_ID FROM M05_TXN_TRANSACTION_LOG WHERE REQUEST_KEY=?",Long.class,key);
  ledger.postJournal(new com.moneybags.txn.api.Contracts.JournalRequest(key,"DEPOSIT",txn,null,null,null,null,null,month.atDay(1),"Past test funding",
    List.of(new com.moneybags.txn.api.Contracts.JournalLine(2L,null,null,null,"DR",new BigDecimal("1000"),"Test cash"),
      new com.moneybags.txn.api.Contracts.JournalLine(1L,1L,null,null,"CR",new BigDecimal("1000"),"Test funding"))),adminId);
  BigDecimal before=balance(1);
  mvc.perform(post("/api/v1/deposit-interest/runs").param("period",month.toString()).header("Authorization","Bearer "+admin)).andExpect(status().isOk());
  BigDecimal amount=db.queryForObject("SELECT INTEREST_AMOUNT FROM MBX_SAVINGS_INTEREST_PERIOD WHERE ACCOUNT_ID=1 AND PERIOD_MONTH=?",BigDecimal.class,month.atDay(1));
  assertTrue(amount.signum()>0);
  assertEquals(0,before.add(amount).compareTo(balance(1)));
  mvc.perform(post("/api/v1/deposit-interest/runs").param("period",month.toString()).header("Authorization","Bearer "+admin)).andExpect(status().isOk());
  assertEquals(0,before.add(amount).compareTo(balance(1)));
  mvc.perform(get("/api/v1/deposit-interest/accounts/1").header("Authorization","Bearer "+customer)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/deposit-interest/accounts/1").header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M05_V_GL_JOURNAL_CONTROL WHERE IS_BALANCED='N'",Integer.class));
 }

 @Test @Order(22) @org.springframework.transaction.annotation.Transactional
 void savingsWithoutApprovedInterestRuleDoesNotCreateAPosting() throws Exception {
  String admin=login("admin");
  java.time.YearMonth month=java.time.YearMonth.now(java.time.ZoneId.of("Asia/Kolkata")).minusMonths(2);
  db.update("UPDATE MBX_INTEREST_ROLLOUT SET START_DATE=? WHERE CONFIG_ID=1",month.atDay(1));
  db.update("UPDATE M04_BANK_ACCOUNT SET OPENED_AT=?,ACTIVATED_AT=? WHERE ACCOUNT_ID=1",
    month.atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime(),
    month.atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime());
  db.update("DELETE FROM M03_PM_INTEREST_RULE WHERE PRODUCT_VERSION_ID=1");
  String response=mvc.perform(post("/api/v1/deposit-interest/runs").param("period",month.toString())
    .header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertTrue(response.contains("NOT_CONFIGURED"));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM MBX_SAVINGS_INTEREST_PERIOD WHERE ACCOUNT_ID=1 AND PERIOD_MONTH=?",
    Integer.class,month.atDay(1)));
 }

 @Test @Order(23) void customerClosureRequiresOwnSettledSoleAccountAndBankReview() throws Exception {
  String customer=login("customer"),other=login("customer2"),requestId=UUID.randomUUID().toString();
  send("/accounts/self/1/closures",customer,Map.of("requestId",UUID.randomUUID().toString(),
    "reasonCode","CUSTOMER_REQUEST")).andExpect(status().isConflict());
  var opening=send("/accounts/self",customer,Map.of("requestId",UUID.randomUUID().toString(),
    "cifId","demo-cif-1","productId",1,"productVersionId",1)).andExpect(status().isCreated()).andReturn();
  long id=json.readTree(opening.getResponse().getContentAsString()).path("id").asLong();
  send("/accounts/self/"+id+"/activate",customer,Map.of("requestId",UUID.randomUUID().toString())).andExpect(status().isAccepted());
  Map<String,Object> closure=Map.of("requestId",requestId,"reasonCode","CUSTOMER_REQUEST","remarks","No longer needed");
  send("/accounts/self/"+id+"/closures",other,closure).andExpect(status().isForbidden());
  send("/accounts/self/"+id+"/closures",customer,closure).andExpect(status().isAccepted());
  assertEquals("CLOSING",db.queryForObject("SELECT ACCOUNT_STATUS FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?",String.class,id));
  assertEquals("CLOSED",db.queryForObject("SELECT DEBIT_STATUS FROM M05_POSTING_FENCE WHERE BANK_ACCOUNT_ID=?",String.class,id));
  send("/accounts/self/"+id+"/closures",customer,closure).andExpect(status().isAccepted());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M04_ACCOUNT_CLOSURE_REQUEST WHERE ACCOUNT_ID=?",Integer.class,id));
 }

 @Test @Order(24) void customerNomineesRequirePrimaryOwnershipAndFullShares() throws Exception {
  String customer=login("customer"),other=login("customer2");
  var opening=send("/accounts/self",customer,Map.of("requestId",UUID.randomUUID().toString(),
    "cifId","demo-cif-1","productId",1,"productVersionId",1)).andExpect(status().isCreated()).andReturn();
  long id=json.readTree(opening.getResponse().getContentAsString()).path("id").asLong();
  send("/accounts/self/"+id+"/activate",customer,Map.of("requestId",UUID.randomUUID().toString())).andExpect(status().isAccepted());
  var nominee=Map.of("name","Test Nominee","relationship","SPOUSE","sharePercentage","100.00");
  var body=Map.of("requestId",UUID.randomUUID().toString(),"nominees",List.of(nominee));
  mvc.perform(put("/api/v1/accounts/self/"+id+"/nominees").header("Authorization","Bearer "+other)
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isForbidden());
  mvc.perform(put("/api/v1/accounts/"+id+"/nominees").header("Authorization","Bearer "+customer)
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isForbidden());
  mvc.perform(put("/api/v1/accounts/self/"+id+"/nominees").header("Authorization","Bearer "+customer)
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),
      "nominees",List.of(Map.of("name","Test Nominee","relationship","SPOUSE","sharePercentage","50.00"))))))
    .andExpect(status().isBadRequest());
  mvc.perform(put("/api/v1/accounts/self/"+id+"/nominees").header("Authorization","Bearer "+customer)
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isNoContent());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M04_ACCOUNT_NOMINEE WHERE ACCOUNT_ID=? AND IS_ACTIVE='Y'",Integer.class,id));
  mvc.perform(get("/api/v1/accounts/"+id+"/nominees").header("Authorization","Bearer "+customer))
    .andExpect(status().isOk()).andExpect(jsonPath("$[0].NOMINEE_NAME").value("Test Nominee"));
 }

 @Test @Order(25) void customerLimitAndIssueRequestsNeedOfficerDecision() throws Exception {
  String customer=login("customer"),other=login("customer2"),admin=login("admin");
  String limitId=UUID.randomUUID().toString();
  var request=Map.of("requestId",limitId,"operationCode","INTERNAL_TRANSFER",
    "periodCode","DAY","requestedAmount","50000.00","reason","Temporary transfer need");
  send("/accounts/self/1/limit-requests",other,request).andExpect(status().isForbidden());
  send("/accounts/self/1/limit-requests",customer,request).andExpect(status().isCreated())
    .andExpect(jsonPath("$.REQUEST_STATUS").value("PENDING"));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M04_ACCOUNT_LIMIT WHERE CHANGE_REQUEST_ID=?",Integer.class,limitId));
  mvc.perform(get("/api/v1/accounts/self/1/requests").header("Authorization","Bearer "+other))
    .andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/accounts/customer-requests").header("Authorization","Bearer "+admin))
    .andExpect(status().isOk());
  send("/accounts/1/customer-requests/"+limitId+"/decision",customer,
    Map.of("decision","APPROVED","productLimitRuleId",1)).andExpect(status().isForbidden());
  String reason="Outside current product policy";
  send("/accounts/1/customer-requests/"+limitId+"/decision",admin,
    Map.of("decision","REJECTED","reason",reason)).andExpect(status().isNoContent());
  assertEquals("REJECTED",db.queryForObject("SELECT REQUEST_STATUS FROM MBX_ACCOUNT_CUSTOMER_REQUEST WHERE REQUEST_ID=?",String.class,limitId));
  String issueId=UUID.randomUUID().toString();
  send("/accounts/self/1/issues",customer,Map.of("requestId",issueId,"details","Card may be compromised"))
    .andExpect(status().isCreated());
  send("/accounts/1/customer-requests/"+issueId+"/decision",admin,
    Map.of("decision","RESOLVED","reason","Customer contacted"))
    .andExpect(status().isNoContent());
  assertEquals("RESOLVED",db.queryForObject("SELECT REQUEST_STATUS FROM MBX_ACCOUNT_CUSTOMER_REQUEST WHERE REQUEST_ID=?",String.class,issueId));
 }

 @Test @Order(26) void approvedCustomerLimitUsesProductRuleAndSafetyBlockIsOwnerOnly() throws Exception {
  String customer=login("customer"),other=login("customer2"),admin=login("admin");
  db.update("INSERT INTO M03_PM_LIMIT_RULE(PRODUCT_VERSION_ID,RULE_CODE,OPERATION_CODE,PERIOD_CODE,MAX_AMOUNT,CURRENCY_CODE) VALUES (1,'CUSTOMER_LIMIT_TEST','INTERNAL_TRANSFER','DAY',100000,'INR')");
  Long rule=db.queryForObject("SELECT LIMIT_RULE_ID FROM M03_PM_LIMIT_RULE WHERE RULE_CODE='CUSTOMER_LIMIT_TEST'",Long.class);
  var opening=send("/accounts/self",customer,Map.of("requestId",UUID.randomUUID().toString(),
    "cifId","demo-cif-1","productId",1,"productVersionId",1)).andExpect(status().isCreated()).andReturn();
  long id=json.readTree(opening.getResponse().getContentAsString()).path("id").asLong();
  send("/accounts/self/"+id+"/activate",customer,Map.of("requestId",UUID.randomUUID().toString())).andExpect(status().isAccepted());
  String requestId=UUID.randomUUID().toString();
  send("/accounts/self/"+id+"/limit-requests",customer,Map.of("requestId",requestId,
    "operationCode","INTERNAL_TRANSFER","periodCode","DAY","requestedAmount","50000.00","reason","Travel"))
    .andExpect(status().isCreated());
  send("/accounts/"+id+"/customer-requests/"+requestId+"/decision",admin,
    Map.of("decision","APPROVED","productLimitRuleId",rule)).andExpect(status().isNoContent());
  assertEquals(0,new BigDecimal("50000.00").compareTo(db.queryForObject(
    "SELECT LIMIT_AMOUNT FROM M04_ACCOUNT_LIMIT WHERE CHANGE_REQUEST_ID=?",BigDecimal.class,requestId)));
  var block=Map.of("requestId",UUID.randomUUID().toString(),"type","DEBIT_BLOCK","details","Suspicious activity");
  send("/accounts/self/"+id+"/safety-blocks",other,block).andExpect(status().isForbidden());
  send("/accounts/self/"+id+"/safety-blocks",customer,block).andExpect(status().isAccepted());
  assertEquals("DEBIT_BLOCKED",db.queryForObject("SELECT ACCOUNT_STATUS FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?",String.class,id));
  assertEquals("ACTIVE",db.queryForObject("SELECT RESTRICTION_STATUS FROM M04_ACCOUNT_RESTRICTION WHERE REQUEST_ID=?",
    String.class,block.get("requestId")));
 }

 @Test @Order(27) void interestPreviewIsReadOnlyAndScopedToAccountParty() throws Exception {
  String customer=login("customer"),other=login("customer2");
  mvc.perform(get("/api/v1/deposit-interest/accounts/1/accrual").header("Authorization","Bearer "+customer))
    .andExpect(status().isOk()).andExpect(jsonPath("$.status").exists());
  mvc.perform(get("/api/v1/deposit-interest/accounts/1/accrual").header("Authorization","Bearer "+other))
    .andExpect(status().isForbidden());
 }

 @Test @Order(28) void publicSignupDoesNotInheritUnexpectedRetailRolePermissions() throws Exception {
  db.update("INSERT INTO M01_IAM_ROLE_PERMISSION(ROLE_ID,PERMISSION_ID) " +
    "SELECT R.ROLE_ID,P.PERMISSION_ID FROM M01_IAM_ROLE R CROSS JOIN M01_IAM_PERMISSION P " +
    "WHERE R.ROLE_CODE='RETAIL_CUSTOMER' AND P.PERMISSION_CODE='IAM_ROLE_MANAGE'");
  String username="scoped_signup_"+UUID.randomUUID().toString().substring(0,8);
  var body=Map.of("username",username,"legalName","Scoped Customer","dateOfBirth","1995-04-12",
    "password","NewCustomer!2026");
  mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
    .content(json.writeValueAsString(body))).andExpect(status().isOk());
  assertEquals("RETAIL_CUSTOMER_SIGNUP",db.queryForObject(
    "SELECT R.ROLE_CODE FROM M01_IAM_USER U JOIN M01_IAM_USER_ROLE UR ON UR.USER_ID=U.USER_ID " +
      "JOIN M01_IAM_ROLE R ON R.ROLE_ID=UR.ROLE_ID WHERE U.USERNAME=?",String.class,username));
  assertEquals(0,db.queryForObject(
    "SELECT COUNT(*) FROM M01_IAM_USER U JOIN M01_IAM_USER_ROLE UR ON UR.USER_ID=U.USER_ID " +
      "JOIN M01_IAM_ROLE_PERMISSION RP ON RP.ROLE_ID=UR.ROLE_ID " +
      "JOIN M01_IAM_PERMISSION P ON P.PERMISSION_ID=RP.PERMISSION_ID " +
      "WHERE U.USERNAME=? AND P.PERMISSION_CODE='IAM_ROLE_MANAGE'",Integer.class,username));
  var loginResult=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
    .content(json.writeValueAsString(Map.of("username",username,"password","NewCustomer!2026",
      "clientId","MONEYBAGS_WEB")))).andExpect(status().isOk()).andReturn();
  String customer=json.readTree(loginResult.getResponse().getContentAsString())
    .path("data").path("accessToken").asText();
  mvc.perform(get("/api/v1/banking/my-dashboard").header("Authorization","Bearer "+customer))
    .andExpect(status().isOk());
 }

 @Test @Order(29) void accountPartiesShowNamesOnlyToAuthorizedCustomers() throws Exception {
  mvc.perform(get("/api/v1/accounts/1/parties").header("Authorization","Bearer "+login("customer")))
    .andExpect(status().isOk()).andExpect(jsonPath("$[0].PARTY_NAME").value("Aarav Mehta (demo)"));
  mvc.perform(get("/api/v1/accounts/1/parties").header("Authorization","Bearer "+login("customer2")))
    .andExpect(status().isForbidden());
 }

}
