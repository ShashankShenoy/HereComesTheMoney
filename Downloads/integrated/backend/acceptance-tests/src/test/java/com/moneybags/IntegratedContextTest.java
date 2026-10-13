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
 @Autowired com.moneybags.cif.service.CifService cifService;
 @Autowired com.moneybags.payments.core.IntegrationChecks paymentChecks;
 @Autowired com.moneybags.payments.core.PaymentService paymentService;
 @Autowired com.moneybags.payments.core.DispatchService paymentDispatch;
 @Autowired com.moneybags.integration.PaymentWorkflowController paymentWorkflow;
 @Autowired com.moneybags.account.integration.ModuleClients accountClients;
 @Autowired com.moneybags.account.db.AccountStore accountStore;
 @Autowired com.moneybags.account.domain.AccountService accountService;
 @Autowired com.moneybags.txn.core.OperationsService operationsService;
 @Autowired com.moneybags.privacy.hold.LegalHoldRepository legalHolds;
 @Autowired com.moneybags.privacy.audit.AuditController privacyAudit;
 @Autowired com.moneybags.privacy.casework.CaseRepository privacyCases;
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
 @Test @Order(1) void invalidTransportRequestsRemainClientErrors()throws Exception{
  mvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("not-json"))
   .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
  String customer=login("customer");
  mvc.perform(post("/api/v1/reporting/statement-requests").header("Authorization","Bearer "+customer)
   .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("accountId",1,"statementType","AD_HOC",
    "periodStart",java.time.LocalDate.now().toString(),"periodEnd",java.time.LocalDate.now().toString(),"format","CSV","channel","WEB"))))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
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
  send("/teller/tills",admin,Map.of("branchCode","MUM001","cashGlId",2)).andExpect(status().isConflict())
    .andExpect(jsonPath("$.code").value("TILL_ALREADY_EXISTS"));
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
  send("/teller/tills",admin,Map.of("branchCode","MUM001","cashGlId",2)).andExpect(status().isConflict())
    .andExpect(jsonPath("$.code").value("TILL_ALREADY_EXISTS"));
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
  String admin=login("admin"),checker=login("checker"),customer=login("customer");var now=java.time.OffsetDateTime.now();
  String id=value(send("/fx/rates",admin,Map.of("baseCurrency","EUR","quoteCurrency","INR","buyRate","89","midRate","90","sellRate","91","sourceCode","TEST","observedAt",now.toString(),"validUntil",now.plusHours(1).toString(),"reason","Test quote")).andExpect(status().isOk())).path("rateId").asText();
  assertFalse(value(mvc.perform(get("/api/v1/fx/rates").header("Authorization","Bearer "+customer)).andExpect(status().isOk())).toString().contains(id));
  send("/fx/rates/"+id+"/decision",admin,Map.of("decision","APPROVED")).andExpect(status().isConflict());
  send("/fx/rates/"+id+"/decision",checker,Map.of("decision","APPROVED")).andExpect(status().isOk());
  assertTrue(value(mvc.perform(get("/api/v1/fx/rates").header("Authorization","Bearer "+customer)).andExpect(status().isOk())).toString().contains(id));
  mvc.perform(get("/api/v1/fx/quote?base=EUR&quote=INR&amount=10.00").header("Authorization","Bearer "+admin))
    .andExpect(status().isOk()).andExpect(jsonPath("$.rateId").value(id)).andExpect(jsonPath("$.indicative").value(true)).andExpect(jsonPath("$.reciprocal").value(false)).andExpect(jsonPath("$.validUntil").exists());
  mvc.perform(get("/api/v1/fx/quote?base=INR&quote=EUR&amount=180.00").header("Authorization","Bearer "+admin))
    .andExpect(status().isOk()).andExpect(jsonPath("$.rateId").value(id)).andExpect(jsonPath("$.reciprocal").value(true)).andExpect(jsonPath("$.amount").value(2.0));
  mvc.perform(get("/api/v1/fx/quote?base=EUR&quote=EUR&amount=10.00").header("Authorization","Bearer "+admin)).andExpect(status().isConflict());
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
  var listed=value(mvc.perform(get("/api/v1/reporting/statement-requests?accountId=1").header("Authorization","Bearer "+customer)).andExpect(status().isOk()));
  var listedRequest=new ArrayList<JsonNode>();listed.forEach(r->{if(r.path("requestId").asText().equals(request))listedRequest.add(r);});
  assertEquals(1,listedRequest.size());assertEquals(id,listedRequest.get(0).path("statementId").asText());

  var statement=value(mvc.perform(get("/api/v1/reporting/statements/"+id).header("Authorization","Bearer "+customer)).andExpect(status().isOk()));
  assertEquals(0,statement.path("closingBalance").decimalValue().compareTo(balance(1)));
  mvc.perform(get("/api/v1/reporting/statements/"+id+"/download").header("Authorization","Bearer "+login("customer2"))).andExpect(status().isForbidden());
  var pdf=mvc.perform(get("/api/v1/reporting/statements/"+id+"/download").header("Authorization","Bearer "+customer)).andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
  assertTrue(new String(pdf,java.nio.charset.StandardCharsets.US_ASCII).startsWith("%PDF-1.4"));
  java.nio.file.Files.write(java.nio.file.Path.of("target/test-statement.pdf"),pdf);
 }
 @Test @Order(9) void simulatedPaymentSettlesCustomerSuspenseAndReserve()throws Exception{
  String customer=login("customer"),other=login("customer2"),checker=login("checker");
  BigDecimal reserveBefore=db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class);
  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Test recipient","accountToken","test-recipient-token","bankCode","TEST0123456")).andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
  BigDecimal before=balance(1);
  long payment=value(send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,"railCode","UPI","amount","500","requestKey",UUID.randomUUID().toString())).andExpect(status().isOk())).path("paymentId").asLong();
  assertTrue(value(mvc.perform(get("/api/v1/payments").header("Authorization","Bearer "+customer)).andExpect(status().isOk())).toString().contains("\"paymentId\":"+payment));
  assertFalse(value(mvc.perform(get("/api/v1/payments").header("Authorization","Bearer "+other)).andExpect(status().isOk())).toString().contains("\"paymentId\":"+payment));
  for(String path:List.of("/payments/"+payment,"/payments/"+payment+"/history","/payments/"+payment+"/rail-evidence"))
   mvc.perform(get("/api/v1"+path).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
  String ownHistory=value(mvc.perform(get("/api/v1/payments/"+payment+"/history").header("Authorization","Bearer "+customer)).andExpect(status().isOk())).toString();
  assertTrue(ownHistory.contains("TO_STATUS"));
  assertFalse(ownHistory.contains("ACTOR_ID")||ownHistory.contains("EVIDENCE_ID"));
  String invalidKey=UUID.randomUUID().toString();
  send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,"railCode","RTGS","amount","100","requestKey",invalidKey)).andExpect(status().isConflict());
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION WHERE REQUEST_KEY=?",Integer.class,invalidKey));
  String originator=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='customer'",String.class);
  String token=db.queryForObject("SELECT ACCOUNT_TOKEN FROM MBX_BENEFICIARY WHERE BENEFICIARY_ID=?",String.class,beneficiary);
  send("/payments",customer,Map.ofEntries(Map.entry("originatorId",originator),Map.entry("channelCode","WEB"),Map.entry("requestKey",invalidKey),
    Map.entry("correlationId","payment:"+invalidKey),Map.entry("endToEndId","MB-"+invalidKey),Map.entry("direction","OUTBOUND"),
    Map.entry("kind","PAYMENT"),Map.entry("sourceAccountId",1),Map.entry("sourceCifId","demo-cif-1"),
    Map.entry("beneficiaryTokenRef",token),Map.entry("beneficiaryBankCode","TEST0123456"),Map.entry("railCode","RTGS"),
    Map.entry("amount","100"),Map.entry("requestedExecutionDate",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString()))).andExpect(status().isConflict());
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION WHERE REQUEST_KEY=?",Integer.class,invalidKey));
  send("/payments/"+payment+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isOk());
  assertEquals(0,before.subtract(new BigDecimal("500")).compareTo(balance(1)));
  String operator=login("admin");
  send("/payments/"+payment+"/simulate-outcome",operator,Map.of("outcome","ACCEPTED")).andExpect(status().isOk());
  send("/payments/"+payment+"/simulate-outcome",operator,Map.of("outcome","SETTLED")).andExpect(status().isOk());
  send("/payments/"+payment+"/simulate-outcome",operator,Map.of("outcome","SETTLED")).andExpect(status().isOk());
  long provisional=db.queryForObject("SELECT JOURNAL_ID FROM M05_GL_JOURNAL WHERE PAYMENT_ID=? AND JOURNAL_TYPE='PAYMENT_PROVISIONAL'",Long.class,payment);
  long settlement=db.queryForObject("SELECT JOURNAL_ID FROM M05_GL_JOURNAL WHERE PAYMENT_ID=? AND JOURNAL_TYPE='SETTLEMENT'",Long.class,payment);
  long railEvidence=db.queryForObject("SELECT EVIDENCE_ID FROM M06_RAIL_STATUS_EVIDENCE WHERE PAYMENT_ID=? AND RAIL_STATUS='SETTLED'",Long.class,payment);
  var movement=db.queryForMap("SELECT TREASURY_ENTRY_ID,EXTERNAL_SETTLEMENT_REF FROM M07_CENTRAL_TREASURY_LEDGER WHERE PAYMENT_ID=?",payment);
  send("/payments/"+payment+"/reconciliations",operator,Map.of("customerJournalId",provisional,
    "settlementJournalId",settlement,"railEvidenceId",railEvidence,"treasuryEntryId",movement.get("TREASURY_ENTRY_ID"),
    "externalSettlementRef",movement.get("EXTERNAL_SETTLEMENT_REF"),"reconciledAmount","500.00"))
    .andExpect(status().isOk());
  assertEquals("MATCHED",db.queryForObject("SELECT RECONCILIATION_STATUS FROM M06_PAYMENT_RECONCILIATION WHERE PAYMENT_ID=?",String.class,payment));
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
  // Equal persisted timestamps and a clock behind them reproduce the ordering failure without sleeps.
  var evidenceBoundary=java.time.OffsetDateTime.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  db.update("UPDATE M08_LOAN_OFFER SET ISSUED_AT=?,ACCEPTED_AT=? WHERE OFFER_ID=?",evidenceBoundary,evidenceBoundary,offer);
  long document=value(send("/loans/applications/"+id+"/documents",admin,Map.of("type","SIGNED_OFFER","storageReference","demo://customer-signed-offer/"+id,"sha256Hex","d".repeat(64))).andExpect(status().isOk())).path("data").path("documentRefId").asLong();
  assertTrue(db.queryForObject("SELECT CREATED_AT FROM M08_LOAN_DOCUMENT_REF WHERE DOCUMENT_REF_ID=?",java.time.OffsetDateTime.class,document).isAfter(evidenceBoundary));
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

 @Test @Order(30) @Transactional void approvedProductEligibilityIsEnforcedAtEveryAdoptionBoundary() throws Exception {
  String customer=login("customer");
  for(long version:List.of(1L,2L,3L))db.update(
    "INSERT INTO M03_PM_ELIGIBILITY_RULE(PRODUCT_VERSION_ID,RULE_CODE,ATTRIBUTE_CODE,OPERATOR_CODE,VALUE_TYPE,VALUE_NUMBER,FAILURE_REASON_CODE,SEQUENCE_NO) VALUES (?,'AGE_120','AGE','GE','NUMBER',120,'CUSTOMER_AGE_NOT_ELIGIBLE',1)",version);
  var offers=value(mvc.perform(get("/api/v1/products/customer-offers?cifId=demo-cif-1&channel=WEB&currency=INR")
    .header("Authorization","Bearer "+customer)).andExpect(status().isOk())).path("data");
  for(JsonNode offer:offers)assertNotEquals(1,offer.path("product").path("PRODUCT_ID").asInt());
  int accounts=db.queryForObject("SELECT COUNT(*) FROM M04_BANK_ACCOUNT",Integer.class);
  send("/accounts/self",customer,Map.of("requestId",UUID.randomUUID().toString(),"cifId","demo-cif-1",
    "productId",1,"productVersionId",1)).andExpect(status().isConflict());
  assertEquals(accounts,db.queryForObject("SELECT COUNT(*) FROM M04_BANK_ACCOUNT",Integer.class));
  int loans=db.queryForObject("SELECT COUNT(*) FROM M08_LOAN_APPLICATION",Integer.class);
  send("/loans/my/applications",customer,Map.of("cifId","demo-cif-1","productId",2,
    "productVersionId",2,"amount","12000","tenureMonths",12,"purposeCode","PERSONAL"))
    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_ELIGIBLE"));
  assertEquals(loans,db.queryForObject("SELECT COUNT(*) FROM M08_LOAN_APPLICATION",Integer.class));
  int deposits=db.queryForObject("SELECT COUNT(*) FROM MBX_TERM_DEPOSIT",Integer.class);
  send("/term-deposits",customer,Map.of("productId",3,"productVersionId",3,"fundingAccountId",1,
    "amount","1000.00","tenureMonths",12,"requestKey",UUID.randomUUID().toString()))
    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_ELIGIBLE"));
  assertEquals(deposits,db.queryForObject("SELECT COUNT(*) FROM MBX_TERM_DEPOSIT",Integer.class));
 }

 @Test @Order(31) @Transactional void cifClosureCleansOpenReviewStateAndRejectsOutstandingLoans() throws Exception {
  String admin=login("admin");
  JsonNode created=value(send("/cif/customers",admin,Map.of("partyType","INDIVIDUAL","legalName","Closure Test",
    "dateOfBirth","1990-01-01","homeBranchRef","MUM001","segmentCode","RETAIL")).andExpect(status().isOk())).path("data");
  String cif=created.path("customer").path("CIF_ID").asText();
  long rowVersion=created.path("customer").path("ROW_VERSION").asLong();
  var consent=Map.of("purposeCode","MARKETING","captureChannel","BRANCH","evidenceRef","TEST-CONSENT");
  send("/cif/customers/"+cif+"/consents",admin,consent).andExpect(status().isOk());
  send("/cif/customers/"+cif+"/consents",admin,consent).andExpect(status().isConflict())
    .andExpect(jsonPath("$.code").value("CONSENT_ALREADY_ACTIVE"));
  mvc.perform(patch("/api/v1/cif/customers/"+cif+"/status").header("Authorization","Bearer "+admin)
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("rowVersion",rowVersion,
      "status","CLOSED","reason","Customer requested closure"))))
    .andExpect(status().isOk());
  assertEquals("EXPIRED",db.queryForObject("SELECT STATUS FROM M02_KYC_CASE WHERE CIF_ID=?",String.class,cif));
  assertEquals("WITHDRAWN",db.queryForObject("SELECT STATUS FROM M02_CIF_CONSENT WHERE CIF_ID=?",String.class,cif));
  assertEquals("REJECTED",db.queryForObject("SELECT VERIFICATION_STATUS FROM M02_CIF_NAME N JOIN M02_CIF_CUSTOMER C ON C.PARTY_ID=N.PARTY_ID WHERE C.CIF_ID=?",String.class,cif));

  JsonNode depositCustomer=value(send("/cif/customers",admin,Map.of("partyType","INDIVIDUAL","legalName","Deposit Closure Test",
    "dateOfBirth","1990-01-01","homeBranchRef","MUM001","segmentCode","RETAIL")).andExpect(status().isOk())).path("data");
  String depositCif=depositCustomer.path("customer").path("CIF_ID").asText();
  long depositVersion=depositCustomer.path("customer").path("ROW_VERSION").asLong();
  db.update("INSERT INTO MBX_TERM_DEPOSIT(REQUEST_KEY,PRIMARY_CIF_ID,FUNDING_ACCOUNT_ID,PRODUCT_ID,PRODUCT_VERSION_ID,PRINCIPAL,TENURE_MONTHS,ANNUAL_RATE_PCT,DAY_COUNT_BASIS,ROUNDING_MODE,START_DATE,MATURITY_DATE,MATURITY_INTEREST,FUNDING_JOURNAL_ID) VALUES (?,?,1,3,3,1000,12,6.5,'ACT_365','HALF_EVEN',DATE '2026-01-01',DATE '2027-01-01',65,1)",
    UUID.randomUUID().toString(),depositCif);
  mvc.perform(patch("/api/v1/cif/customers/"+depositCif+"/status").header("Authorization","Bearer "+admin)
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("rowVersion",depositVersion,
      "status","CLOSED","reason","Attempted closure"))))
    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPEN_TERM_DEPOSITS"));

  String party=UUID.randomUUID().toString(),loanCif=UUID.randomUUID().toString();
  String adminId=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='admin'",String.class);
  db.update("INSERT INTO M02_CIF_PARTY(PARTY_ID,PARTY_TYPE,DATE_OF_BIRTH) VALUES (?,'INDIVIDUAL',DATE '1990-01-01')",party);
  db.update("INSERT INTO M02_CIF_CUSTOMER(CIF_ID,CIF_NUMBER,PARTY_ID,STATUS,HOME_BRANCH_REF,KYC_STATUS,RISK_LEVEL) VALUES (?,?,?,'ACTIVE','MUM001','VERIFIED','LOW')",
    loanCif,"TEST-"+UUID.randomUUID().toString().substring(0,20),party);
  db.update("INSERT INTO M08_LOAN_APPLICATION(APPLICATION_NUMBER,PRIMARY_CIF_ID,PRODUCT_ID,PRODUCT_VERSION_ID,PRODUCT_RULE_SET_HASH,BRANCH_CODE,CHANNEL_CODE,REQUESTED_AMOUNT,REQUESTED_TENURE_MONTHS,LOAN_PURPOSE_CODE,CREATED_BY_USER_ID,CORRELATION_ID) VALUES (?,?,2,2,?,'MUM001','BRANCH',12000,12,'PERSONAL',?,?)",
    "TEST-"+UUID.randomUUID().toString().substring(0,20),loanCif,"c".repeat(64),adminId,UUID.randomUUID().toString());
  mvc.perform(patch("/api/v1/cif/customers/"+loanCif+"/status").header("Authorization","Bearer "+admin)
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("rowVersion",1,
      "status","CLOSED","reason","Attempted closure"))))
    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPEN_LOANS"));
  assertEquals("ACTIVE",db.queryForObject("SELECT STATUS FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",String.class,loanCif));
 }

 @Test @Order(32) @Transactional void cifProposalRequiresIndependentApprovalAndRejectDoesNotChangeSegment() throws Exception {
  String admin=login("admin"),checker=login("checker");
  JsonNode created=value(send("/cif/customers",admin,Map.of("partyType","INDIVIDUAL","legalName","Case Example",
    "dateOfBirth","1990-01-01","homeBranchRef","MUM001","segmentCode","RETAIL")).andExpect(status().isOk())).path("data");
  String cif=created.path("customer").path("CIF_ID").asText();
  String firstCase=created.path("cases").get(0).path("CASE_ID").asText();
  proposeProfile(admin,cif,"Case Example Approved","PREMIUM");
  assertEquals("RETAIL",db.queryForObject("SELECT SEGMENT_CODE FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",String.class,cif));
  send("/cif/customers/"+cif+"/contacts",admin,Map.of("contactType","EMAIL","value","case@example.test")).andExpect(status().isOk());
  send("/cif/customers/"+cif+"/identifiers",admin,Map.of("identifierType","TEST_ID","value",UUID.randomUUID().toString())).andExpect(status().isOk());
  send("/cif/customers/"+cif+"/addresses",admin,Map.of("addressType","RESIDENTIAL","line1","Test Road",
    "city","Mumbai","countryCode","IN")).andExpect(status().isOk());
  prepareKycEvidence(admin,checker,firstCase);
  send("/cif/cases/"+firstCase+"/review",checker,Map.of("rowVersion",caseVersion(firstCase),"outcome","APPROVE",
    "riskLevel","LOW","reviewDueAt",java.time.OffsetDateTime.now().plusDays(90).toString(),
    "reasonCode","CHECKED","comments","Evidence checked")).andExpect(status().isOk());
  assertEquals("PREMIUM",db.queryForObject("SELECT SEGMENT_CODE FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",String.class,cif));
  assertEquals("ACTIVE",db.queryForObject("SELECT STATUS FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",String.class,cif));

  String secondCase=value(send("/cif/customers/"+cif+"/cases",admin,Map.of("caseType","PERIODIC"))
    .andExpect(status().isOk())).path("data").path("CASE_ID").asText();
  proposeProfile(admin,cif,"Case Example Rejected","PRIVATE");
  db.update("UPDATE M02_CIF_IDENTIFIER SET EXPIRES_ON=DATEADD('DAY',-1,CURRENT_DATE) WHERE PARTY_ID=? AND VERIFICATION_STATUS='VERIFIED'",
    created.path("customer").path("PARTY_ID").asText());
  send("/cif/customers/"+cif+"/identifiers",admin,Map.of("identifierType","TEST_ID","value",UUID.randomUUID().toString()))
    .andExpect(status().isOk());
  assertEquals("PREMIUM",db.queryForObject("SELECT SEGMENT_CODE FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",String.class,cif));
  prepareKycEvidence(admin,checker,secondCase);
  assertFalse(cifService.completeness(cif,secondCase,true).contains("EXPIRED_IDENTIFIER"));
  send("/cif/cases/"+secondCase+"/review",checker,Map.of("rowVersion",caseVersion(secondCase),"outcome","REJECT",
    "reasonCode","UNSUPPORTED","comments","New profile was not supported")).andExpect(status().isOk());
  assertEquals("PREMIUM",db.queryForObject("SELECT SEGMENT_CODE FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",String.class,cif));
  assertEquals("RESTRICTED",db.queryForObject("SELECT STATUS FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",String.class,cif));
 }

 private long caseVersion(String caseId) {
  return db.queryForObject("SELECT ROW_VERSION FROM M02_KYC_CASE WHERE CASE_ID=?",Long.class,caseId);
 }
 private void proposeProfile(String token,String cif,String name,String segment) throws Exception {
  long version=db.queryForObject("SELECT ROW_VERSION FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",Long.class,cif);
  mvc.perform(put("/api/v1/cif/customers/"+cif+"/profile").header("Authorization","Bearer "+token)
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("rowVersion",version,
      "legalName",name,"segmentCode",segment,"reason","Case review")))).andExpect(status().isOk());
 }
 private void prepareKycEvidence(String maker,String reviewer,String caseId) throws Exception {
  for(String type:List.of("IDENTITY","ADDRESS")) {
   var file=new org.springframework.mock.web.MockMultipartFile("file",type.toLowerCase()+".pdf","application/pdf",
     ("%PDF-1.4\n"+type).getBytes(java.nio.charset.StandardCharsets.UTF_8));
   mvc.perform(multipart("/api/v1/cif/cases/"+caseId+"/documents").file(file).param("documentType",type)
     .header("Authorization","Bearer "+maker)).andExpect(status().isOk());
  }
  String firstDocument=db.queryForObject("SELECT DOCUMENT_ID FROM M02_KYC_DOCUMENT WHERE CASE_ID=? AND DOCUMENT_TYPE='IDENTITY'",String.class,caseId);
  mvc.perform(get("/api/v1/cif/documents/"+firstDocument+"/content").header("Authorization","Bearer "+reviewer))
    .andExpect(status().isForbidden());
  send("/cif/cases/"+caseId+"/submit",maker,Map.of("rowVersion",caseVersion(caseId),"reason","Ready for review"))
    .andExpect(status().isOk());
  mvc.perform(get("/api/v1/cif/documents/"+firstDocument+"/content").header("Authorization","Bearer "+maker))
    .andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/cif/documents/"+firstDocument+"/content").header("Authorization","Bearer "+reviewer))
    .andExpect(status().isOk());
  for(String documentId:db.queryForList("SELECT DOCUMENT_ID FROM M02_KYC_DOCUMENT WHERE CASE_ID=?",String.class,caseId))
   send("/cif/documents/"+documentId+"/review",reviewer,Map.of("scanStatus","CLEAN",
     "verificationStatus","VERIFIED","reason","Scanned and checked")).andExpect(status().isOk());
 }

 @Test @Order(33) @Transactional void dueKycBlocksNewProductsBeforeManualExpirySweep() throws Exception {
  String admin=login("admin"),customer=login("customer");
  db.update("UPDATE M02_CIF_CUSTOMER SET NEXT_REVIEW_DUE_AT=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE CIF_ID='demo-cif-1'");
  mvc.perform(get("/api/v1/cif/customers/demo-cif-1/eligibility-facts").header("Authorization","Bearer "+admin))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.kycStatus").value("EXPIRED"));
  mvc.perform(get("/api/v1/cif/customers/demo-cif-1").header("Authorization","Bearer "+admin))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.customer.EFFECTIVE_KYC_STATUS").value("EXPIRED"));
  send("/accounts/self",customer,Map.of("requestId",UUID.randomUUID().toString(),"cifId","demo-cif-1",
    "productId",1,"productVersionId",1)).andExpect(status().isConflict());
  send("/loans/my/applications",customer,Map.of("cifId","demo-cif-1","productId",2,
    "productVersionId",2,"amount","12000","tenureMonths",12,"purposeCode","PERSONAL"))
    .andExpect(status().isConflict());
  var payment=new com.moneybags.payments.api.Contracts.Payment(1,"test","OUTBOUND","TEST","TEST",
    new BigDecimal("1.00"),"PENDING",1,null,null,null,null,null,null,null,null);
  assertThrows(com.moneybags.payments.api.ApiException.class,
    ()->paymentChecks.authorize(payment,"WEB",1L,"demo-cif-1"));
  db.update("UPDATE M02_CIF_CUSTOMER SET NEXT_REVIEW_DUE_AT=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE CIF_ID='demo-cif-2'");
  assertEquals("EXPIRED",accountClients.party("demo-cif-2","MUM001").kycStatus());
  long version=db.queryForObject("SELECT ROW_VERSION FROM M02_CIF_CUSTOMER WHERE CIF_ID='demo-cif-1'",Long.class);
  mvc.perform(patch("/api/v1/cif/customers/demo-cif-1/status").header("Authorization","Bearer "+admin)
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("rowVersion",version,
      "status","ACTIVE","reason","Try bypass")))).andExpect(status().isConflict())
    .andExpect(jsonPath("$.code").value("KYC_REQUIRED"));
  send("/cif/reviews/expire-due",admin,Map.of()).andExpect(status().isOk());
  assertEquals("EXPIRED",db.queryForObject("SELECT KYC_STATUS FROM M02_CIF_CUSTOMER WHERE CIF_ID='demo-cif-1'",String.class));
 }

 @Test @Order(35) @Transactional void expiredIdentifierAppearsInDirectoryAndCanBeSwept() throws Exception {
  String admin=login("admin");
  String party=db.queryForObject("SELECT PARTY_ID FROM M02_CIF_CUSTOMER WHERE CIF_ID='demo-cif-2'",String.class);
  db.update("INSERT INTO M02_CIF_IDENTIFIER(IDENTIFIER_ID,PARTY_ID,IDENTIFIER_TYPE,VALUE_CIPHERTEXT,DISPLAY_HINT,LOOKUP_HMAC,EXPIRES_ON,VERIFICATION_STATUS,VALID_FROM) VALUES (?,?,?,?,'****',?,DATEADD('DAY',-1,CURRENT_DATE),'VERIFIED',CURRENT_TIMESTAMP)",
    UUID.randomUUID().toString(),party,"TEST_ID",new byte[]{1,2,3},UUID.randomUUID().toString());
  mvc.perform(get("/api/v1/cif/customers").param("search","MB-CIF-0002").header("Authorization","Bearer "+admin))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].EFFECTIVE_KYC_STATUS").value("EXPIRED"));
  send("/cif/reviews/expire-due",admin,Map.of()).andExpect(status().isOk());
  assertEquals("EXPIRED",db.queryForObject("SELECT KYC_STATUS FROM M02_CIF_CUSTOMER WHERE CIF_ID='demo-cif-2'",String.class));
  assertEquals("RESTRICTED",db.queryForObject("SELECT STATUS FROM M02_CIF_CUSTOMER WHERE CIF_ID='demo-cif-2'",String.class));
 }

 @Test @Order(36) @Transactional void productEligibilityMatchesOpeningLoanAndDepositTerms() throws Exception {
  String admin=login("admin");
  var savings=value(send("/products/1/eligibility",admin,Map.of("cifId","demo-cif-1","channel","BRANCH")).andExpect(status().isOk())).path("data");
  assertTrue(savings.path("eligible").asBoolean(),"A zero minimum account needs no opening amount");
  var missingDeposit=value(send("/products/3/eligibility",admin,Map.of("cifId","demo-cif-1","channel","WEB")).andExpect(status().isOk())).path("data");
  assertFalse(missingDeposit.path("eligible").asBoolean());
  assertTrue(missingDeposit.path("reasonCodes").toString().contains("DEPOSIT_AMOUNT_OUTSIDE_RANGE"));
  assertTrue(missingDeposit.path("reasonCodes").toString().contains("DEPOSIT_TENURE_OUTSIDE_RANGE"));
  var validDeposit=value(send("/products/3/eligibility",admin,Map.of("cifId","demo-cif-1","channel","WEB","depositAmount","1000","tenureMonths",1)).andExpect(status().isOk())).path("data");
  assertTrue(validDeposit.path("eligible").asBoolean());
  var longDeposit=value(send("/products/3/eligibility",admin,Map.of("cifId","demo-cif-1","channel","WEB","depositAmount","1000","tenureMonths",61)).andExpect(status().isOk())).path("data");
  assertTrue(longDeposit.path("reasonCodes").toString().contains("DEPOSIT_TENURE_OUTSIDE_RANGE"));
 }

 @Test @Order(37) @Transactional void paymentRulesMatchDebitCreditAndBothDirections() {
  db.update("INSERT INTO M03_PM_TRANSACTION_RULE(PRODUCT_VERSION_ID,RULE_CODE,OPERATION_CODE,DIRECTION_CODE,ACTION_CODE,PRIORITY_NO) VALUES (1,'TEST_PAYMENT','PAYMENT','DEBIT','BLOCK',2)");
  var outbound=new com.moneybags.payments.api.Contracts.Payment(1,"test","OUTBOUND","TEST","TEST",
    new BigDecimal("1.00"),"PENDING",1,null,null,null,null,null,null,null,null);
  var inbound=new com.moneybags.payments.api.Contracts.Payment(2,"test","INBOUND","TEST","TEST",
    new BigDecimal("1.00"),"PENDING",1,null,null,null,null,null,null,null,null);
  var denied=assertThrows(com.moneybags.payments.api.ApiException.class,
    ()->paymentChecks.authorize(outbound,"WEB",1L,"demo-cif-1"));
  assertEquals("POLICY_DENIED",denied.code());
  db.update("UPDATE M03_PM_TRANSACTION_RULE SET DIRECTION_CODE='BOTH' WHERE PRODUCT_VERSION_ID=1 AND RULE_CODE='TEST_PAYMENT'");
  denied=assertThrows(com.moneybags.payments.api.ApiException.class,
    ()->paymentChecks.authorize(inbound,"WEB",1L,"demo-cif-1"));
  assertEquals("POLICY_DENIED",denied.code());
  db.update("UPDATE M03_PM_TRANSACTION_RULE SET COUNTERPARTY_CODE='SPECIAL' WHERE PRODUCT_VERSION_ID=1 AND RULE_CODE='TEST_PAYMENT'");
  var conditional=assertThrows(com.moneybags.payments.api.ApiException.class,
    ()->paymentChecks.authorize(outbound,"WEB",1L,"demo-cif-1"));
  assertEquals("POLICY_ADAPTER_REQUIRED",conditional.code());
  var invalid=new com.moneybags.payments.api.Contracts.Payment(3,"test","SIDEWAYS","TEST","TEST",
    new BigDecimal("1.00"),"PENDING",1,null,null,null,null,null,null,null,null);
  assertEquals("INVALID_PAYMENT_DIRECTION",assertThrows(com.moneybags.payments.api.ApiException.class,
    ()->paymentChecks.authorize(invalid,"WEB",1L,"demo-cif-1")).code());
 }

 @Test @Order(38) @Transactional void loanApplicationStopsWhenProductSalesEnd() throws Exception {
  db.update("UPDATE M03_PM_PRODUCT SET SALES_END_AT=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE PRODUCT_ID=2");
  String customer=login("customer");
  send("/loans/my/applications",customer,Map.of("cifId","demo-cif-1","productId",2,
    "productVersionId",2,"amount","12000","tenureMonths",12,"purposeCode","PERSONAL"))
    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_ACTIVE"));
 }

 @Test @Order(39) @Transactional void replacementVersionCanBeApprovedBeforeOldVersionRetires() throws Exception {
  String maker=login("admin"),checker=login("checker");
  String activatorId=UUID.randomUUID().toString(),activatorName="product_activator_"+UUID.randomUUID().toString().substring(0,8);
  String adminRole=db.queryForObject("SELECT ROLE_ID FROM M01_IAM_ROLE WHERE ROLE_CODE='BANK_ADMIN'",String.class);
  db.update("INSERT INTO M01_IAM_USER(USER_ID,USERNAME,USER_TYPE,STATUS,EMPLOYEE_REF) VALUES (?,?,'EMPLOYEE','ACTIVE','TEST')",activatorId,activatorName);
  db.update("INSERT INTO M01_IAM_CREDENTIAL(CREDENTIAL_ID,USER_ID,PASSWORD_HASH,HASH_SCHEME,STATUS) VALUES (?,?,?,'BCRYPT','ACTIVE')",
    UUID.randomUUID().toString(),activatorId,passwords.encode("LocalBanking!2026"));
  db.update("INSERT INTO M01_IAM_USER_ROLE(ASSIGNMENT_ID,USER_ID,ROLE_ID,SCOPE_TYPE,STATUS,VALID_FROM) VALUES (?,?,?,'GLOBAL','ACTIVE',SYSTIMESTAMP)",
    UUID.randomUUID().toString(),activatorId,adminRole);
  String activator=login(activatorName);
  String start=java.time.OffsetDateTime.now().minusMinutes(1).toString();
  JsonNode draft=value(send("/products/3/versions",maker,Map.of("sourceVersionId",3,
    "effectiveFromAt",start,"defaultTxnAction","ALLOW","changeReason","Replacement terms"))
    .andExpect(status().isOk())).path("data");
  long versionId=draft.path("version").path("PRODUCT_VERSION_ID").asLong();
  long rowVersion=draft.path("version").path("ROW_VERSION").asLong();
  JsonNode request=value(send("/products/versions/"+versionId+"/submit",maker,Map.of("rowVersion",rowVersion,
    "reason","Prepare replacement terms")) .andExpect(status().isOk())).path("data");
  long approvalId=request.path("APPROVAL_ID").asLong();
  send("/products/approvals/"+approvalId+"/decision",checker,Map.of("rowVersion",request.path("ROW_VERSION").asLong(),
    "decision","APPROVED","comment","Terms checked")) .andExpect(status().isOk());
  rowVersion=db.queryForObject("SELECT ROW_VERSION FROM M03_PM_PRODUCT_VERSION WHERE PRODUCT_VERSION_ID=?",Long.class,versionId);
  send("/products/versions/"+versionId+"/activate",activator,Map.of("rowVersion",rowVersion,
    "reason","Try overlapping sale")) .andExpect(status().isConflict())
    .andExpect(jsonPath("$.code").value("VERSION_OVERLAP"));
  db.update("UPDATE M03_PM_PRODUCT_VERSION SET VERSION_STATE='RETIRED' WHERE PRODUCT_VERSION_ID=3");
  send("/products/versions/"+versionId+"/activate",activator,Map.of("rowVersion",rowVersion,
    "reason","Old version retired")) .andExpect(status().isOk());
 }

 @Test @Order(40) @Transactional void retiredVersionKeepsHistoricalPaymentPolicyForExistingAccount() {
  var payment=new com.moneybags.payments.api.Contracts.Payment(1,"test","OUTBOUND","TEST","TEST",
    new BigDecimal("1.00"),"PENDING",1,null,null,null,null,null,null,null,null);
  db.update("UPDATE M03_PM_PRODUCT_VERSION SET VERSION_STATE='RETIRED',EFFECTIVE_TO_AT=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE PRODUCT_VERSION_ID=1");
  assertDoesNotThrow(()->paymentChecks.authorize(payment,"WEB",1L,"demo-cif-1"));
  db.update("UPDATE M03_PM_PRODUCT_VERSION SET VERSION_STATE='SUSPENDED' WHERE PRODUCT_VERSION_ID=1");
  assertEquals("ACCOUNT_NOT_ELIGIBLE",assertThrows(com.moneybags.payments.api.ApiException.class,
    ()->paymentChecks.authorize(payment,"WEB",1L,"demo-cif-1")).code());
  db.update("UPDATE M03_PM_PRODUCT_VERSION SET VERSION_STATE='RETIRED' WHERE PRODUCT_VERSION_ID=1");
  db.update("UPDATE M03_PM_PRODUCT SET STATUS='SUSPENDED' WHERE PRODUCT_ID=1");
  assertEquals("ACCOUNT_NOT_ELIGIBLE",assertThrows(com.moneybags.payments.api.ApiException.class,
    ()->paymentChecks.authorize(payment,"WEB",1L,"demo-cif-1")).code());
  db.update("UPDATE M03_PM_PRODUCT SET STATUS='RETIRED' WHERE PRODUCT_ID=1");
  assertDoesNotThrow(()->paymentChecks.authorize(payment,"WEB",1L,"demo-cif-1"));
 }

 @Test @Order(41) @Transactional void customerOffersExposeTermsWithoutInternalProductControls() throws Exception {
  String customer=login("customer");
  JsonNode offers=value(mvc.perform(get("/api/v1/products/customer-offers")
    .param("cifId","demo-cif-1").param("channel","WEB").param("currency","INR")
    .header("Authorization","Bearer "+customer)).andExpect(status().isOk())).path("data");
  boolean sawLoan=false,sawDeposit=false;
  for(JsonNode offer:offers) {
    JsonNode rules=offer.path("rules");
    assertTrue(rules.path("overrides").isMissingNode());
    assertTrue(rules.path("transactions").isMissingNode());
    assertTrue(rules.path("allocation").isMissingNode());
    String type=offer.path("product").path("PRODUCT_TYPE").asText();
    if(type.equals("LOAN")) { sawLoan=true; assertTrue(rules.path("loan").isArray()); }
    if(type.equals("DEPOSIT")) { sawDeposit=true; assertTrue(rules.path("term").isArray()); }
  }
  assertTrue(sawLoan && sawDeposit);
 }

 @Test @Order(42) @Transactional void transferUsesProductRulesForEachSideAndHonorsProductSuspension() throws Exception {
  var catalog=new com.moneybags.txn.core.AccountCatalog(db);
  db.update("UPDATE M03_PM_TRANSACTION_RULE SET DIRECTION_CODE='DEBIT',ACTION_CODE='ALLOW' WHERE PRODUCT_VERSION_ID=1 AND RULE_CODE='TRANSFER'");
  db.update("INSERT INTO M03_PM_TRANSACTION_RULE(PRODUCT_VERSION_ID,RULE_CODE,OPERATION_CODE,DIRECTION_CODE,ACTION_CODE,PRIORITY_NO) VALUES (1,'TEST_CREDIT','INTERNAL_TRANSFER','CREDIT','BLOCK',2)");
  assertDoesNotThrow(()->catalog.requireTransferAllowed(1,"BRANCH","DEBIT"));
  assertEquals("PRODUCT_RULE_DENIED",assertThrows(com.moneybags.txn.api.ApiException.class,
    ()->catalog.requireTransferAllowed(1,"BRANCH","CREDIT")).code());
  String customer=login("customer");
  send("/transactions/transfers",customer,transfer(1,2,"1.00",UUID.randomUUID().toString()))
    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_RULE_DENIED"));
  db.update("UPDATE M03_PM_TRANSACTION_RULE SET ACTION_CODE='ALLOW',COUNTERPARTY_CODE='SPECIAL' WHERE PRODUCT_VERSION_ID=1 AND RULE_CODE='TEST_CREDIT'");
  assertEquals("POLICY_ADAPTER_REQUIRED",assertThrows(com.moneybags.txn.api.ApiException.class,
    ()->catalog.requireTransferAllowed(1,"BRANCH","CREDIT")).code());
  db.update("UPDATE M03_PM_TRANSACTION_RULE SET COUNTERPARTY_CODE=NULL WHERE PRODUCT_VERSION_ID=1 AND RULE_CODE='TEST_CREDIT'");
  db.update("UPDATE M03_PM_PRODUCT SET STATUS='SUSPENDED' WHERE PRODUCT_ID=1");
  assertEquals("ACCOUNT_NOT_ACTIVE",assertThrows(com.moneybags.txn.api.ApiException.class,
    ()->catalog.requireActive(1)).code());
  db.update("UPDATE M03_PM_PRODUCT SET STATUS='RETIRED' WHERE PRODUCT_ID=1");
  assertDoesNotThrow(()->catalog.requireActive(1));
  db.update("UPDATE M03_PM_PRODUCT_VERSION SET VERSION_STATE='SUSPENDED' WHERE PRODUCT_VERSION_ID=1");
  assertEquals("ACCOUNT_NOT_ACTIVE",assertThrows(com.moneybags.txn.api.ApiException.class,
    ()->catalog.requireActive(1)).code());
 }

 @Test @Order(43) @Transactional void accountOpeningHonorsHolderAndNomineeProductTerms() throws Exception {
  String admin=login("admin");
  var joint=Map.of("cifId","demo-cif-2","role","JOINT_HOLDER","operatingInstruction","JOINTLY");
  send("/accounts",admin,Map.of("requestId",UUID.randomUUID().toString(),"primaryCifId","demo-cif-1",
    "productId",1,"productVersionId",1,"branchCode","MUM001","operationMode","JOINTLY",
    "additionalParties",List.of(joint))).andExpect(status().isConflict());
  send("/accounts/1/parties",admin,joint).andExpect(status().isConflict());
  db.update("UPDATE M03_PM_ACCOUNT_RULE SET NOMINEE_REQUIRED='Y' WHERE PRODUCT_VERSION_ID=1");
  String customer=login("customer");
  long accountId=value(send("/accounts/self",customer,Map.of("requestId",UUID.randomUUID().toString(),
    "cifId","demo-cif-1","productId",1,"productVersionId",1)).andExpect(status().isCreated())).path("id").asLong();
  send("/accounts/self/"+accountId+"/activate",customer,Map.of("requestId",UUID.randomUUID().toString()))
    .andExpect(status().isConflict());
  mvc.perform(put("/api/v1/accounts/self/"+accountId+"/nominees")
    .header("Authorization","Bearer "+customer).contentType(MediaType.APPLICATION_JSON)
    .content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"nominees",
      List.of(Map.of("name","Account Nominee","relationship","FAMILY","sharePercentage","100.00"))))))
    .andExpect(status().isNoContent());
  String activationRequest=UUID.randomUUID().toString();
  send("/accounts/self/"+accountId+"/activate",customer,Map.of("requestId",activationRequest))
    .andExpect(status().isAccepted());
  send("/accounts/self/"+accountId+"/activate",customer,Map.of("requestId",activationRequest))
    .andExpect(status().isAccepted());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M04_ACCOUNT_CONTROL_SYNC WHERE ACCOUNT_ID=? AND REQUEST_ID=?",Integer.class,
    accountId,activationRequest));
 }

 @Test @Order(44) @Transactional void nomineesAreReadableAcrossAllLinkedPrimaryCifs() throws Exception {
  String userId=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='customer'",String.class);
  db.update("INSERT INTO M01_IAM_CUSTOMER_LINK(LINK_ID,USER_ID,CIF_ID,RELATIONSHIP_TYPE,STATUS,VALID_FROM) VALUES (?,?,?,'SELF','ACTIVE',SYSTIMESTAMP)",
    UUID.randomUUID().toString(),userId,"demo-cif-2");
  String customer=login("customer");
  mvc.perform(get("/api/v1/accounts/2/nominees").header("Authorization","Bearer "+customer))
    .andExpect(status().isOk());
 }

 @Test @Order(45) @Transactional void optionalNomineesCanBeRemovedButRequiredNomineesCannot() throws Exception {
  String customer=login("customer");
  var nominee=Map.of("name","Temporary Nominee","relationship","FAMILY","sharePercentage","100.00");
  mvc.perform(put("/api/v1/accounts/self/1/nominees").header("Authorization","Bearer "+customer)
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
      "requestId",UUID.randomUUID().toString(),"nominees",List.of(nominee)))))
    .andExpect(status().isNoContent());
  var empty=Map.of("requestId",UUID.randomUUID().toString(),"nominees",List.of());
  mvc.perform(put("/api/v1/accounts/self/1/nominees").header("Authorization","Bearer "+customer)
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(empty)))
    .andExpect(status().isNoContent());
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M04_ACCOUNT_NOMINEE WHERE ACCOUNT_ID=1 AND IS_ACTIVE='Y'",Integer.class));
  db.update("UPDATE M03_PM_ACCOUNT_RULE SET NOMINEE_REQUIRED='Y' WHERE PRODUCT_VERSION_ID=1");
  mvc.perform(put("/api/v1/accounts/self/1/nominees").header("Authorization","Bearer "+customer)
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
      "requestId",UUID.randomUUID().toString(),"nominees",List.of()))))
    .andExpect(status().isConflict());
  mvc.perform(put("/api/v1/accounts/self/1/nominees").header("Authorization","Bearer "+customer)
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
      "requestId",UUID.randomUUID().toString(),"nominees",List.of(Map.of(
        "name","Invalid date","relationship","FAMILY","sharePercentage","100.00","dateOfBirth","not-a-date"))))))
    .andExpect(status().isBadRequest());
 }

 @Test @Order(46) @Transactional void accountLimitRespectsProductMinimum() throws Exception {
  db.update("UPDATE M03_PM_LIMIT_RULE SET MIN_AMOUNT=1000 WHERE PRODUCT_VERSION_ID=1 AND RULE_CODE='DAY_TRANSFER'");
  Long rule=db.queryForObject("SELECT LIMIT_RULE_ID FROM M03_PM_LIMIT_RULE WHERE PRODUCT_VERSION_ID=1 AND RULE_CODE='DAY_TRANSFER'",Long.class);
  String admin=login("admin");
  send("/accounts/1/limits",admin,Map.of("requestId",UUID.randomUUID().toString(),
    "limitType","ACCOUNT","operationCode","INTERNAL_TRANSFER","periodCode","DAY",
    "resetRuleCode","CALENDAR","timeZoneId","Asia/Kolkata","amount","500.00",
    "productLimitRuleId",rule,"effectiveFrom",java.time.LocalDate.now().toString()))
    .andExpect(status().isConflict());
  send("/accounts/1/limits",admin,Map.of("requestId",UUID.randomUUID().toString(),
    "limitType","ACCOUNT","operationCode","INTERNAL_TRANSFER","periodCode","DAY",
    "resetRuleCode","CALENDAR","timeZoneId","Asia/Kolkata","amount","1500.00",
    "productLimitRuleId",rule,"effectiveFrom","not-a-date"))
    .andExpect(status().isBadRequest());
 }

 @Test @Order(47) @Transactional void financialProjectionUsesModuleFourSequenceRatherThanLiveLedger() {
  db.update("UPDATE M04_BANK_ACCOUNT SET LEDGER_BALANCE=10,BLOCKED_BALANCE=0,LIEN_BALANCE=0,OVERDRAFT_LIMIT=0,BALANCE_SOURCE_VERSION=4 WHERE ACCOUNT_ID=1");
  var snapshot=accountStore.projectionSnapshot(1);
  assertEquals(4,snapshot.version());
  assertNotEquals(0,snapshot.ledgerBalance().compareTo(balance(1)));
  var event=new com.moneybags.account.api.Models.ProjectionEvent(UUID.randomUUID().toString(),1L,
    "LEDGER_POSTED",5L,null,null,null,null,new BigDecimal("5.00"),BigDecimal.ZERO,
    BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("15.00"),BigDecimal.ZERO,BigDecimal.ZERO,
    BigDecimal.ZERO,java.time.OffsetDateTime.now(),null);
  assertDoesNotThrow(()->com.moneybags.account.domain.ProjectionPolicy.validate(snapshot,event));
 }

 @Test @Order(48) @Transactional void versionAdoptionChecksExistingAccountAgainstTargetTerms() throws Exception {
  String customer=login("customer"),admin=login("admin");
  long accountId=value(send("/accounts/self",customer,Map.of("requestId",UUID.randomUUID().toString(),
    "cifId","demo-cif-1","productId",1,"productVersionId",1)).andExpect(status().isCreated())).path("id").asLong();
  send("/accounts/self/"+accountId+"/activate",customer,Map.of("requestId",UUID.randomUUID().toString()))
    .andExpect(status().isAccepted());
  String maker=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='admin'",String.class);
  String checker=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='checker'",String.class);
  long target=1001,approval=1001,treatment=1001;
  db.update("UPDATE M03_PM_PRODUCT_VERSION SET VERSION_STATE='RETIRED' WHERE PRODUCT_VERSION_ID=1");
  db.update("INSERT INTO M03_PM_PRODUCT_VERSION(PRODUCT_VERSION_ID,PRODUCT_ID,VERSION_NO,VERSION_STATE,EFFECTIVE_FROM_AT,DEFAULT_TXN_ACTION,CHANGE_REASON,RULE_SET_HASH,CREATED_BY_USER_ID) VALUES (?,1,2,'ACTIVE',?,'ALLOW','Nominee required',?,?)",
    target,java.time.OffsetDateTime.now().minusDays(1),"a".repeat(64),maker);
  db.update("INSERT INTO M03_PM_ACCOUNT_RULE(PRODUCT_VERSION_ID,MIN_OPENING_BALANCE,MINOR_ALLOWED,JOINT_ALLOWED,MAX_HOLDERS,NOMINEE_REQUIRED) VALUES (?,0,'N','N',1,'Y')",target);
  db.update("INSERT INTO M03_PM_AVAILABILITY(PRODUCT_VERSION_ID,CURRENCY_CODE,OFFER_FROM_AT) VALUES (?,'INR',?)",
    target,java.time.OffsetDateTime.now().minusDays(1));
  db.update("INSERT INTO M03_PM_APPROVAL(APPROVAL_ID,PRODUCT_ID,PRODUCT_VERSION_ID,ACTION_CODE,REQUEST_STATUS,MAKER_USER_ID,MAKER_SESSION_ID,CHECKER_USER_ID,CHECKER_SESSION_ID,CONTENT_HASH,REASON,CORRELATION_ID,DECIDED_AT) VALUES (?,1,?,'TREATMENT','APPROVED',?,? ,?,? ,?,'Terms reviewed',?,SYSTIMESTAMP)",
    approval,target,maker,UUID.randomUUID().toString(),checker,UUID.randomUUID().toString(),"a".repeat(64),UUID.randomUUID().toString());
  db.update("INSERT INTO M03_PM_VERSION_TREATMENT(VERSION_TREATMENT_ID,PRODUCT_ID,SOURCE_VERSION_ID,TARGET_VERSION_ID,TREATMENT_CODE,CONSENT_REQUIRED,APPROVAL_ID) VALUES (?,1,1,?,'GRANDFATHER','N',?)",
    treatment,target,approval);
  var adoption=Map.of("requestId",UUID.randomUUID().toString(),"targetVersionId",target,
    "treatmentId",treatment,"treatmentEventId",UUID.randomUUID().toString(),"reasonCode","TERMS_CHANGED");
  send("/accounts/"+accountId+"/product-version-adoptions",admin,adoption)
    .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Add a nominee before adopting this product version"));
  mvc.perform(put("/api/v1/accounts/self/"+accountId+"/nominees")
    .header("Authorization","Bearer "+customer).contentType(MediaType.APPLICATION_JSON)
    .content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"nominees",
      List.of(Map.of("name","Version Nominee","relationship","FAMILY","sharePercentage","100.00"))))))
    .andExpect(status().isNoContent());
  send("/accounts/"+accountId+"/product-version-adoptions",admin,adoption)
    .andExpect(status().isCreated());
 }

 @Test @Order(49) @Transactional void disablingNewCustomerOpeningKeepsExistingAccountActionsAvailable() throws Exception {
  String customer=login("customer");
  long accountId=value(send("/accounts/self",customer,Map.of("requestId",UUID.randomUUID().toString(),
    "cifId","demo-cif-1","productId",1,"productVersionId",1)).andExpect(status().isCreated())).path("id").asLong();
  org.springframework.test.util.ReflectionTestUtils.setField(accountService,"demoCustomerOpening",false);
  try {
   send("/accounts/self",customer,Map.of("requestId",UUID.randomUUID().toString(),
     "cifId","demo-cif-1","productId",1,"productVersionId",1)).andExpect(status().isForbidden());
   send("/accounts/self/"+accountId+"/activate",customer,Map.of("requestId",UUID.randomUUID().toString()))
     .andExpect(status().isAccepted());
   mvc.perform(put("/api/v1/accounts/self/"+accountId+"/nominees")
     .header("Authorization","Bearer "+customer).contentType(MediaType.APPLICATION_JSON)
     .content(json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"nominees",
       List.of(Map.of("name","Existing Account Nominee","relationship","FAMILY","sharePercentage","100.00"))))))
     .andExpect(status().isNoContent());
   send("/accounts/self/"+accountId+"/closures",customer,
     Map.of("requestId",UUID.randomUUID().toString(),"reasonCode","CUSTOMER_REQUEST"))
     .andExpect(status().isAccepted());
  } finally {
   org.springframework.test.util.ReflectionTestUtils.setField(accountService,"demoCustomerOpening",true);
  }
 }

 @Test @Order(50) @Transactional void ledgerAdministrationRejectsInvalidDatesMappingsAndCloseReplays() {
  var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
  var futureJournal=new com.moneybags.txn.api.Contracts.JournalRequest(UUID.randomUUID().toString(),
    "ADJUSTMENT",3L,null,null,null,null,null,today.plusDays(1),"Future posting",
    List.of(new com.moneybags.txn.api.Contracts.JournalLine(2L,null,null,null,"DR",BigDecimal.ONE,"Cash"),
      new com.moneybags.txn.api.Contracts.JournalLine(7L,null,null,null,"CR",BigDecimal.ONE,"Equity")));
  var future=assertThrows(com.moneybags.txn.api.ApiException.class,()->ledger.postJournal(futureJournal,"test"));
  assertEquals("FUTURE_VALUE_DATE",future.code());
  var mapping=assertThrows(com.moneybags.txn.api.ApiException.class,()->operationsService.createMapping(
    new com.moneybags.txn.api.OperationsContracts.MappingRequest(1L,"FEE","FEE_INCOME",1L,today,null),"test"));
  assertEquals("GL_CLASS",mapping.code());
  var missing=assertThrows(com.moneybags.txn.api.ApiException.class,()->operationsService.assessFee(
    new com.moneybags.txn.api.OperationsContracts.FeeRequest(UUID.randomUUID().toString(),999999L,null,901L,new BigDecimal("25.00"),today)));
  assertEquals("FEE_OWNER_NOT_FOUND",missing.code());
  String maker=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='admin'",String.class);
  String key=UUID.randomUUID().toString();
  var close=new com.moneybags.txn.api.OperationsContracts.CloseRequest(key,today.minusDays(1),"Month-end review");
  var first=operationsService.requestClose(close,maker);
  assertEquals(first,operationsService.requestClose(close,maker));
  var conflict=assertThrows(com.moneybags.txn.api.ApiException.class,()->operationsService.requestClose(
    new com.moneybags.txn.api.OperationsContracts.CloseRequest(key,today.minusDays(1),"Changed reason"),maker));
  assertEquals("CLOSE_KEY_CONFLICT",conflict.code());
  var futureClose=assertThrows(com.moneybags.txn.api.ApiException.class,()->operationsService.requestClose(
    new com.moneybags.txn.api.OperationsContracts.CloseRequest(UUID.randomUUID().toString(),today.plusDays(1),"Future close"),maker));
  assertEquals("FUTURE_CLOSE_DATE",futureClose.code());
  String checker=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='checker'",String.class);
  long closeId=((Number)first.get("closeId")).longValue();
  var rejection=new com.moneybags.txn.api.OperationsContracts.CloseDecision("REJECTED");
  assertEquals(operationsService.decideClose(closeId,rejection,checker),operationsService.decideClose(closeId,rejection,checker));
 }

 @Test @Order(51) void concurrentControlDeliveryReturnsOneAppliedSnapshot() throws Exception {
  var position=ledger.position(1);
  var fence=db.queryForMap("SELECT DEBIT_STATUS,CREDIT_STATUS FROM M05_POSTING_FENCE WHERE BANK_ACCOUNT_ID=1");
  var event=new com.moneybags.txn.api.Contracts.ControlRequest(UUID.randomUUID(),position.controlVersion()+1,
    (String)fence.get("DEBIT_STATUS"),(String)fence.get("CREDIT_STATUS"),position.liens(),position.blocks(),position.overdraft(),"RETRY_TEST");
  var start=new java.util.concurrent.CountDownLatch(1);
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try {
   var first=pool.submit(()->{start.await();return ledger.applyControl(1,event,"test");});
   var second=pool.submit(()->{start.await();return ledger.applyControl(1,event,"test");});
   start.countDown();
   assertEquals(event.controlVersion(),first.get(20,java.util.concurrent.TimeUnit.SECONDS).controlVersion());
   assertEquals(event.controlVersion(),second.get(20,java.util.concurrent.TimeUnit.SECONDS).controlVersion());
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M05_POSTING_FENCE_HISTORY WHERE BANK_ACCOUNT_ID=1 AND CONTROL_VERSION=?",Integer.class,event.controlVersion()));
  } finally {pool.shutdownNow();}
 }

 @Test @Order(52) void concurrentPaymentHoldDeliveryCreatesOnlyOneReservation() throws Exception {
  String key=UUID.randomUUID().toString();
  String maker=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='admin'",String.class);
  db.update("INSERT INTO M06_PAYMENT_INSTRUCTION(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,END_TO_END_ID,PAYMENT_DIRECTION,PAYMENT_KIND,SOURCE_ACCOUNT_ID,SOURCE_CIF_ID,BENEFICIARY_TOKEN_REF,BENEFICIARY_BANK_CODE,RAIL_CODE,AMOUNT,REQUESTED_EXECUTION_DATE,STATUS) VALUES (?,'WEB',?,?,?,?,?,'PAYMENT',1,'demo-cif-1','TEST_BENEFICIARY','TEST_BANK','NEFT',1.50,?,'AUTHORIZED')",
    maker,key,new byte[32],key,"hold-"+key,"OUTBOUND",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
  long paymentId=db.queryForObject("SELECT PAYMENT_ID FROM M06_PAYMENT_INSTRUCTION WHERE REQUEST_KEY=?",Long.class,key);
  var request=new com.moneybags.txn.api.Contracts.HoldRequest(1L,paymentId,new BigDecimal("1.50"),"hold-"+key,
    java.time.OffsetDateTime.now().plusHours(1));
  var start=new java.util.concurrent.CountDownLatch(1);
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try {
   var first=pool.submit(()->{start.await();return ledger.placeHold(request,maker);});
   var second=pool.submit(()->{start.await();return ledger.placeHold(request,maker);});
   start.countDown();
   assertEquals(first.get(20,java.util.concurrent.TimeUnit.SECONDS).holdId(),second.get(20,java.util.concurrent.TimeUnit.SECONDS).holdId());
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M05_FUNDS_HOLD WHERE PAYMENT_ID=?",Integer.class,paymentId));
   var duplicate=assertThrows(com.moneybags.txn.api.ApiException.class,()->ledger.placeHold(
     new com.moneybags.txn.api.Contracts.HoldRequest(1L,paymentId,new BigDecimal("1.50"),"other-"+key,request.expiresAt()),maker));
   assertEquals("PAYMENT_HOLD_EXISTS",duplicate.code());
  } finally {pool.shutdownNow();}
 }

 @Test @Order(53) void concurrentJournalReplayPostsOneBalancedJournal() throws Exception {
  String key="race-journal:"+UUID.randomUUID();
  var request=new com.moneybags.txn.api.Contracts.JournalRequest(key,"ADJUSTMENT",3L,null,null,null,null,null,
    java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")),"Concurrent replay",
    List.of(new com.moneybags.txn.api.Contracts.JournalLine(2L,null,null,null,"DR",BigDecimal.ONE,"Cash"),
      new com.moneybags.txn.api.Contracts.JournalLine(7L,null,null,null,"CR",BigDecimal.ONE,"Equity")));
  var start=new java.util.concurrent.CountDownLatch(1);
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try {
   var first=pool.submit(()->{start.await();return ledger.postJournal(request,"test");});
   var second=pool.submit(()->{start.await();return ledger.postJournal(request,"test");});
   start.countDown();
   assertEquals(first.get(20,java.util.concurrent.TimeUnit.SECONDS).journalId(),second.get(20,java.util.concurrent.TimeUnit.SECONDS).journalId());
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M05_GL_JOURNAL WHERE POSTING_KEY=?",Integer.class,key));
   assertEquals("Y",ledger.journalControl(first.get().journalId()).get("IS_BALANCED"));
  } finally {pool.shutdownNow();}
 }

 @Test @Order(54) @Transactional void paymentSettlementClearsTheOriginalSuspenseAfterAccountVersionChange() throws Exception {
  String customer=login("customer"),checker=login("checker"),admin=login("admin");
  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Rollover recipient",
    "accountToken","rollover-"+UUID.randomUUID(),"bankCode","TEST0123456"))
    .andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
  long payment=value(send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,
    "railCode","UPI","amount","10.00","requestKey",UUID.randomUUID().toString()))
    .andExpect(status().isOk())).path("paymentId").asLong();
  send("/payments/"+payment+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isOk());
  long originalSuspense=db.queryForObject("SELECT P.GL_ACCOUNT_ID FROM M05_GL_JOURNAL J JOIN M05_GL_POSTING P ON P.JOURNAL_ID=J.JOURNAL_ID WHERE J.PAYMENT_ID=? AND J.JOURNAL_TYPE='PAYMENT_PROVISIONAL' AND P.BANK_ACCOUNT_ID IS NULL",Long.class,payment);
  String maker=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='admin'",String.class);
  long version=2002;
  db.update("INSERT INTO M03_PM_PRODUCT_VERSION(PRODUCT_VERSION_ID,PRODUCT_ID,VERSION_NO,VERSION_STATE,EFFECTIVE_FROM_AT,DEFAULT_TXN_ACTION,CHANGE_REASON,RULE_SET_HASH,CREATED_BY_USER_ID) VALUES (?,1,99,'ACTIVE',?,'ALLOW','Payment rollover test',?,?)",
    version,java.time.OffsetDateTime.now().minusDays(1),"a".repeat(64),maker);
  String glCode="ALT_SUSP_"+UUID.randomUUID().toString().substring(0,8);
  db.update("INSERT INTO M05_GL_ACCOUNT(GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE) VALUES (?,'New payment suspense','LIABILITY','CR')",glCode);
  long alternateSuspense=db.queryForObject("SELECT GL_ACCOUNT_ID FROM M05_GL_ACCOUNT WHERE GL_CODE=?",Long.class,glCode);
  var effective=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(1);
  db.update("INSERT INTO M05_GL_PRODUCT_MAPPING(PRODUCT_VERSION_ID,POSTING_TYPE,GL_ROLE_CODE,GL_ACCOUNT_ID,EFFECTIVE_FROM,CREATED_BY) VALUES (?,'PAYMENT','CUSTOMER_LIABILITY',1,?,?)",version,effective,maker);
  db.update("INSERT INTO M05_GL_PRODUCT_MAPPING(PRODUCT_VERSION_ID,POSTING_TYPE,GL_ROLE_CODE,GL_ACCOUNT_ID,EFFECTIVE_FROM,CREATED_BY) VALUES (?,'PAYMENT','SETTLEMENT_SUSPENSE',?,?,?)",version,alternateSuspense,effective,maker);
  db.update("UPDATE M04_BANK_ACCOUNT SET PRODUCT_VERSION_ID=? WHERE ACCOUNT_ID=1",version);
  send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","ACCEPTED")).andExpect(status().isOk());
  send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","SETTLED")).andExpect(status().isOk());
  long settledSuspense=db.queryForObject("SELECT P.GL_ACCOUNT_ID FROM M05_GL_JOURNAL J JOIN M05_GL_POSTING P ON P.JOURNAL_ID=J.JOURNAL_ID WHERE J.PAYMENT_ID=? AND J.JOURNAL_TYPE='SETTLEMENT' AND P.ENTRY_SIDE='DR'",Long.class,payment);
  assertNotEquals(alternateSuspense,originalSuspense);
  assertEquals(originalSuspense,settledSuspense);
 }

 @Test @Order(55) @Transactional void reversalDecisionReplayDoesNotPostTwice() throws Exception {
  var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
  String maker=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='admin'",String.class);
  String checker=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='checker'",String.class);
  var transfer=new com.moneybags.txn.api.Contracts.TransferRequest(1L,2L,BigDecimal.ONE,"BRANCH",
    UUID.randomUUID().toString(),UUID.randomUUID().toString(),today);
  long original=ledger.transfer(transfer,maker).transactionId();
  var reversal=new com.moneybags.txn.api.Contracts.ReversalRequest(UUID.randomUUID().toString(),
    "Customer transfer correction",UUID.randomUUID().toString(),today);
  long id=ledger.requestReversal(original,reversal,maker).transactionId();
  String token=login("checker");
  mvc.perform(get("/api/v1/transactions/reversal-requests")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/v1/transactions/reversal-requests").header("Authorization","Bearer "+login("customer")))
    .andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/transactions/reversal-requests").header("Authorization","Bearer "+token))
    .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.TXN_ID == "+id+")].REASON_TEXT").value("Customer transfer correction"));
  String assignment=db.queryForObject("SELECT A.ASSIGNMENT_ID FROM M01_IAM_USER_ROLE A JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID WHERE A.USER_ID=? AND R.ROLE_CODE='BANK_CHECKER'",String.class,checker);
  db.update("UPDATE M01_IAM_USER_ROLE SET SCOPE_TYPE='BRANCH',SCOPE_REF='OTHER001' WHERE ASSIGNMENT_ID=?",assignment);
  mvc.perform(get("/api/v1/transactions/reversal-requests").header("Authorization","Bearer "+token))
    .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.TXN_ID == "+id+")]").isEmpty());
  db.update("UPDATE M01_IAM_USER_ROLE SET SCOPE_TYPE='GLOBAL',SCOPE_REF=NULL WHERE ASSIGNMENT_ID=?",assignment);
  send("/transactions/"+id+"/reversal-decision",login("admin"),Map.of("decision","APPROVED"))
    .andExpect(status().isForbidden());
  var approve=new com.moneybags.txn.api.Contracts.ApprovalDecision("APPROVED","Reviewed");
  assertEquals("POSTED",ledger.decideReversal(id,approve,checker).status());
  assertEquals("POSTED",ledger.decideReversal(id,approve,checker).status());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M05_GL_JOURNAL WHERE TXN_ID=? AND REVERSAL_OF_JOURNAL_ID IS NOT NULL",Integer.class,id));
  var opposite=assertThrows(com.moneybags.txn.api.ApiException.class,()->ledger.decideReversal(id,
    new com.moneybags.txn.api.Contracts.ApprovalDecision("REJECTED","Changed"),checker));
  assertEquals("APPROVAL_CLOSED",opposite.code());
  mvc.perform(get("/api/v1/transactions/reversal-requests").header("Authorization","Bearer "+token))
    .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.TXN_ID == "+id+")]").isEmpty());
  long second=ledger.transfer(new com.moneybags.txn.api.Contracts.TransferRequest(1L,2L,BigDecimal.ONE,"BRANCH",
    UUID.randomUUID().toString(),UUID.randomUUID().toString(),today),maker).transactionId();
  long rejected=ledger.requestReversal(second,new com.moneybags.txn.api.Contracts.ReversalRequest(
    UUID.randomUUID().toString(),"Reject test",UUID.randomUUID().toString(),today),maker).transactionId();
  send("/transactions/"+rejected+"/reversal-decision",token,Map.of("decision","REJECTED"))
    .andExpect(status().isOk());
  mvc.perform(get("/api/v1/transactions/reversal-requests").header("Authorization","Bearer "+token))
    .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.TXN_ID == "+rejected+")]").isEmpty());
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M05_GL_JOURNAL WHERE TXN_ID=?",Integer.class,rejected));
 }

 /** Exercise the full loan path with equal persisted offer timestamps in one transaction. */
 @Test @Order(55) @Transactional void customerLoanHandlesSameTickEvidenceBoundary() throws Exception {
  customerCanApplyOnlyForOwnCifAndSeeOwnApplication();
 }

 @Test @Order(34) @Transactional void futureDatedRelationshipsCanEndEarlyAndClosureEndsBothDirections() throws Exception {
  String admin=login("admin");
  var original=(java.time.Clock)org.springframework.test.util.ReflectionTestUtils.getField(cifService,"clock");
  var tick=db.queryForObject("SELECT CURRENT_TIMESTAMP",java.time.OffsetDateTime.class)
    .toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  org.springframework.test.util.ReflectionTestUtils.setField(cifService,"clock",java.time.Clock.fixed(tick,java.time.ZoneOffset.UTC));
  try {
   JsonNode a=value(send("/cif/customers",admin,Map.of("partyType","INDIVIDUAL","legalName","Source Example",
     "dateOfBirth","1990-01-01","homeBranchRef","MUM001")).andExpect(status().isOk())).path("data");
   JsonNode b=value(send("/cif/customers",admin,Map.of("partyType","INDIVIDUAL","legalName","Target Example",
     "dateOfBirth","1991-01-01","homeBranchRef","MUM001")).andExpect(status().isOk())).path("data");
   String source=a.path("customer").path("CIF_ID").asText(),target=b.path("customer").path("CIF_ID").asText();
   String sourceParty=a.path("customer").path("PARTY_ID").asText(),targetParty=b.path("customer").path("PARTY_ID").asText();
   var relation=Map.of("targetCifId",target,"relationshipType","GUARDIAN","operatingAuthority","NONE",
     "validTo",tick.plus(10,java.time.temporal.ChronoUnit.DAYS).atOffset(java.time.ZoneOffset.UTC).toString());
   send("/cif/customers/"+source+"/relationships",admin,relation).andExpect(status().isOk());
   send("/cif/customers/"+source+"/relationships",admin,relation).andExpect(status().isConflict())
     .andExpect(jsonPath("$.code").value("RELATIONSHIP_EXISTS"));
   String rel=db.queryForObject("SELECT RELATIONSHIP_ID FROM M02_CIF_RELATIONSHIP WHERE SOURCE_PARTY_ID=?",String.class,sourceParty);
   mvc.perform(delete("/api/v1/cif/customers/"+source+"/relationships/"+rel).header("Authorization","Bearer "+admin))
     .andExpect(status().isOk());
   var ended=db.queryForObject("SELECT VALID_TO FROM M02_CIF_RELATIONSHIP WHERE RELATIONSHIP_ID=?",java.time.OffsetDateTime.class,rel);
   assertEquals(tick.plus(1,java.time.temporal.ChronoUnit.MICROS),ended.toInstant());
   assertEquals(tick,db.queryForObject("SELECT VALID_FROM FROM M02_CIF_RELATIONSHIP WHERE RELATIONSHIP_ID=?",java.time.OffsetDateTime.class,rel).toInstant());
   // Advance the application clock while the H2 transaction clock remains fixed.
   var nextTick=tick.plus(2,java.time.temporal.ChronoUnit.MICROS);
   org.springframework.test.util.ReflectionTestUtils.setField(cifService,"clock",java.time.Clock.fixed(nextTick,java.time.ZoneOffset.UTC));
   mvc.perform(delete("/api/v1/cif/customers/"+source+"/relationships/"+rel).header("Authorization","Bearer "+admin))
     .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RELATIONSHIP_NOT_ACTIVE"));
   send("/cif/customers/"+source+"/relationships",admin,relation).andExpect(status().isOk());
   send("/cif/customers/"+target+"/relationships",admin,Map.of("targetCifId",source,"relationshipType","GUARDIAN",
     "operatingAuthority","NONE")).andExpect(status().isOk());
   long version=db.queryForObject("SELECT ROW_VERSION FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",Long.class,target);
   mvc.perform(patch("/api/v1/cif/customers/"+target+"/status").header("Authorization","Bearer "+admin)
     .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("rowVersion",version,
       "status","CLOSED","reason","Customer request")))).andExpect(status().isOk());
   var closedAt=nextTick.plus(1,java.time.temporal.ChronoUnit.MICROS).atOffset(java.time.ZoneOffset.UTC);
   assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M02_CIF_RELATIONSHIP WHERE (SOURCE_PARTY_ID=? OR TARGET_PARTY_ID=?) AND (VALID_TO IS NULL OR VALID_TO>?)",Integer.class,targetParty,targetParty,closedAt));
   assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM M02_CIF_RELATIONSHIP WHERE (SOURCE_PARTY_ID=? OR TARGET_PARTY_ID=?) AND VALID_TO>VALID_FROM",Integer.class,targetParty,targetParty));
   send("/cif/customers/"+source+"/relationships",admin,relation).andExpect(status().isConflict())
     .andExpect(jsonPath("$.code").value("CUSTOMER_CLOSED"));
  } finally {
   org.springframework.test.util.ReflectionTestUtils.setField(cifService,"clock",original);
  }
 }

 @Test @Order(56) @Transactional void profileReplacementWorksWithinOneClockTick() throws Exception {
  String admin=login("admin");
  java.time.Clock original=(java.time.Clock)org.springframework.test.util.ReflectionTestUtils.getField(cifService,"clock");
  var instant=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  org.springframework.test.util.ReflectionTestUtils.setField(cifService,"clock",java.time.Clock.fixed(instant,java.time.ZoneOffset.UTC));
  try {
   JsonNode created=value(send("/cif/customers",admin,Map.of("partyType","INDIVIDUAL","legalName","Rapid Profile",
     "dateOfBirth","1990-01-01","homeBranchRef","MUM001")).andExpect(status().isOk())).path("data");
   String cif=created.path("customer").path("CIF_ID").asText();
   String party=created.path("customer").path("PARTY_ID").asText();
   proposeProfile(admin,cif,"Rapid Profile Updated","PREMIUM");
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M02_CIF_NAME WHERE PARTY_ID=? AND VALID_TO>VALID_FROM",Integer.class,party));
   assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM M02_CIF_NAME WHERE PARTY_ID=?",Integer.class,party));
  } finally {
   org.springframework.test.util.ReflectionTestUtils.setField(cifService,"clock",original);
  }
 }

 @Test @Order(57) @Transactional void paymentDispatchVisibilityReconciliationAndRefundStayConsistent() throws Exception {
  String customer=login("customer"),checker=login("checker"),admin=login("admin"),other=login("customer2");
  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Refund recipient",
    "accountToken","refund-"+UUID.randomUUID(),"bankCode","TEST0123456"))
    .andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
  BigDecimal customerBefore=balance(1);
  BigDecimal reserveBefore=db.queryForObject("SELECT ACTIVE_HOLD_AMOUNT FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class);
  long payment=value(send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,
    "railCode","UPI","amount","3.00","requestKey",UUID.randomUUID().toString()))
    .andExpect(status().isOk())).path("paymentId").asLong();
  String approvalKey=UUID.randomUUID().toString();
  var approvalBody=Map.of("approvalKey",approvalKey,"paymentId",payment,"actionCode","MANUAL_RETURN","reasonText","Review test");
  long approval=value(send("/approvals",admin,approvalBody).andExpect(status().isOk())).path("APPROVAL_ID").asLong();
  assertEquals(approval,value(send("/approvals",admin,approvalBody).andExpect(status().isOk())).path("APPROVAL_ID").asLong());
  send("/approvals/"+approval+"/decision",checker,Map.of("decision","APPROVED")).andExpect(status().isOk());
  send("/approvals/"+approval+"/decision",checker,Map.of("decision","APPROVED")).andExpect(status().isOk());
  send("/payments/"+payment+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/payments/"+payment).header("Authorization","Bearer "+admin))
    .andExpect(status().isOk()).andExpect(jsonPath("$.amount").isNumber());
  mvc.perform(get("/api/v1/payments/"+payment).header("Authorization","Bearer "+other))
    .andExpect(status().isNotFound());
  long dispatchId=db.queryForObject("SELECT DISPATCH_ID FROM M06_RAIL_DISPATCH WHERE PAYMENT_ID=?",Long.class,payment);
  var invalid=assertThrows(com.moneybags.payments.api.ApiException.class,()->paymentDispatch.attempt(dispatchId,
    new com.moneybags.payments.api.Contracts.DispatchAttempt("INQUIRY","STARTED",null,null)));
  assertEquals("INVALID_INQUIRY_OUTCOME",invalid.code());
  send("/payments/"+payment+"/reconciliations",admin,Map.of("reconciledAmount","3.00"))
    .andExpect(status().isOk());
  assertNotEquals("MATCHED",db.queryForObject("SELECT RECONCILIATION_STATUS FROM M06_PAYMENT_RECONCILIATION WHERE PAYMENT_ID=?",String.class,payment));
  send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","REJECTED")).andExpect(status().isOk());
  send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","REFUNDED")).andExpect(status().isOk());
  assertEquals(0,customerBefore.compareTo(balance(1)));
  assertEquals(0,reserveBefore.compareTo(db.queryForObject("SELECT ACTIVE_HOLD_AMOUNT FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class)));
  assertEquals("RELEASED",db.queryForObject("SELECT STATUS FROM M07_TREASURY_LIQUIDITY_HOLD WHERE PAYMENT_ID=?",String.class,payment));
 }

 @Test @Order(58) @Transactional void clearingBatchRetriesAndCycleItemsMatchExactly() throws Exception {
  String customer=login("customer"),checker=login("checker"),admin=login("admin");
  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Clearing recipient",
    "accountToken","clearing-"+UUID.randomUUID(),"bankCode","TEST0123456"))
    .andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
  long payment=value(send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,
    "railCode","UPI","amount","4.00","requestKey",UUID.randomUUID().toString()))
    .andExpect(status().isOk())).path("paymentId").asLong();
  send("/payments/"+payment+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isOk());
  send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","ACCEPTED")).andExpect(status().isOk());
  var batchRequest=Map.of("railCode","UPI","batchReference",UUID.randomUUID().toString(),"direction","OUTBOUND");
  long batch=value(send("/clearing-batches",admin,batchRequest).andExpect(status().isOk())).path("CLEARING_BATCH_ID").asLong();
  assertEquals(batch,value(send("/clearing-batches",admin,batchRequest).andExpect(status().isOk())).path("CLEARING_BATCH_ID").asLong());
  var itemRequest=Map.of("paymentId",payment,"externalItemRef",UUID.randomUUID().toString());
  send("/clearing-batches/"+batch+"/items",admin,itemRequest).andExpect(status().isOk());
  send("/clearing-batches/"+batch+"/items",admin,itemRequest).andExpect(status().isOk());
  assertEquals(1,db.queryForObject("SELECT ITEM_COUNT FROM M06_CLEARING_BATCH WHERE CLEARING_BATCH_ID=?",Integer.class,batch));
  send("/clearing-batches/"+batch+"/close",admin,Map.of()).andExpect(status().isOk());
  String reference=UUID.randomUUID().toString();
  db.update("INSERT INTO M07_SETTLEMENT_CYCLE(RAIL_CODE,RESERVE_ACCOUNT_ID,CYCLE_REFERENCE,CLEARING_BATCH_ID,CYCLE_DIRECTION,GROSS_OUT_AMOUNT,NET_AMOUNT,NET_MOVEMENT_SIDE,STATUS,CLOSED_AT) VALUES ('UPI',1,?,?,'OUTBOUND',4,4,'OUT','CLOSED',SYSTIMESTAMP)",reference,batch);
  long cycle=db.queryForObject("SELECT SETTLEMENT_CYCLE_ID FROM M07_SETTLEMENT_CYCLE WHERE CYCLE_REFERENCE=?",Long.class,reference);
  send("/clearing-batches/"+batch+"/settlement",admin,Map.of("settlementCycleId",cycle)).andExpect(status().isConflict());
  db.update("INSERT INTO M07_SETTLEMENT_CYCLE_ITEM(SETTLEMENT_CYCLE_ID,PAYMENT_ID,DIRECTION,AMOUNT) VALUES (?,?,'OUT',4)",cycle,payment);
  send("/clearing-batches/"+batch+"/settlement",admin,Map.of("settlementCycleId",cycle)).andExpect(status().isOk());
  assertEquals("SETTLEMENT_PENDING",db.queryForObject("SELECT BATCH_STATUS FROM M06_CLEARING_BATCH WHERE CLEARING_BATCH_ID=?",String.class,batch));
 }

 @Test @Order(59) @Transactional void paymentRetryUsesOriginalInstructionAfterBeneficiaryDisabled() throws Exception {
  String customer=login("customer"),checker=login("checker");
  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Retry recipient",
    "accountToken","retry-"+UUID.randomUUID(),"bankCode","TEST0123456"))
    .andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
  String key=UUID.randomUUID().toString();
  var request=Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,"railCode","UPI","amount","7.00","requestKey",key);
  long payment=value(send("/payments/initiate",customer,request).andExpect(status().isOk())).path("paymentId").asLong();
  String oldKey=UUID.randomUUID().toString();
  String originator=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='customer'",String.class);
  String token=db.queryForObject("SELECT ACCOUNT_TOKEN FROM MBX_BENEFICIARY WHERE BENEFICIARY_ID=?",String.class,beneficiary);
  long oldPayment=value(send("/payments",customer,Map.ofEntries(
    Map.entry("originatorId",originator),Map.entry("channelCode","WEB"),Map.entry("requestKey",oldKey),
    Map.entry("correlationId","payment:"+oldKey),Map.entry("endToEndId","MB-"+oldKey),
    Map.entry("direction","OUTBOUND"),Map.entry("kind","PAYMENT"),Map.entry("sourceAccountId",1),
    Map.entry("sourceCifId","demo-cif-1"),Map.entry("beneficiaryTokenRef",token),
    Map.entry("beneficiaryBankCode","TEST0123456"),Map.entry("railCode","UPI"),Map.entry("amount","9.00"),
    Map.entry("requestedExecutionDate",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(1).toString())))
    .andExpect(status().isCreated())).path("paymentId").asLong();
  db.update("UPDATE MBX_BENEFICIARY SET STATUS='DISABLED' WHERE BENEFICIARY_ID=?",beneficiary);
  assertEquals(payment,value(send("/payments/initiate",customer,request).andExpect(status().isOk())).path("paymentId").asLong());
  assertEquals(oldPayment,value(send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,
    "railCode","UPI","amount","9.00","requestKey",oldKey)).andExpect(status().isOk())).path("paymentId").asLong());
  send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,"railCode","UPI","amount","8.00","requestKey",key))
    .andExpect(status().isConflict());
  send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,"railCode","UPI","amount","7.00","requestKey",UUID.randomUUID().toString()))
    .andExpect(status().isNotFound());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION WHERE ORIGINATOR_ID=(SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='customer') AND REQUEST_KEY=?",Integer.class,key));
  send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,"railCode","UPI","amount","7.00","requestKey","x".repeat(93)))
    .andExpect(status().isBadRequest());
 }

 @Test @Order(60) @Transactional void treasuryRejectsWrongGlCycleAndSettlementJournal() throws Exception {
  String customer=login("customer"),checker=login("checker"),admin=login("admin");
  send("/treasury/reserve-accounts",admin,Map.of("code","BAD-"+UUID.randomUUID(),"accountType","RBI_CURRENT",
    "externalAccountRef","TEST-"+UUID.randomUUID(),"glAccountId",3,"safetyBuffer","0.00","warningThreshold","0.00"))
    .andExpect(status().isUnprocessableEntity());
  long cycle=value(send("/treasury/settlement-cycles",admin,Map.of("railCode","UPI","reserveAccountId",1,
    "cycleReference",UUID.randomUUID().toString(),"direction","OUTBOUND")).andExpect(status().isCreated())).path("id").asLong();
  send("/treasury/settlement-cycles/"+cycle+"/close",admin,Map.of()).andExpect(status().isUnprocessableEntity());
  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Treasury recipient",
    "accountToken","treasury-"+UUID.randomUUID(),"bankCode","TEST0123456"))
    .andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
  long payment=value(send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,
    "railCode","UPI","amount","5.00","requestKey",UUID.randomUUID().toString()))
    .andExpect(status().isOk())).path("paymentId").asLong();
  send("/payments/"+payment+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isOk());
  var evidence=new HashMap<String,Object>();
  evidence.put("reserveAccountId",1); evidence.put("paymentId",payment); evidence.put("railCode","UPI");
  evidence.put("evidenceType","RAIL_CONFIRMATION"); evidence.put("evidenceStatus","VERIFIED");
  evidence.put("movementSide","OUT"); evidence.put("amount","6.00");
  evidence.put("externalSettlementRef","BAD-"+UUID.randomUUID()); evidence.put("evidencePayload","bad payment amount");
  evidence.put("occurredAt",java.time.OffsetDateTime.now().toString());
  send("/treasury/settlement-evidence",admin,evidence).andExpect(status().isConflict());
  evidence.put("amount","5.00"); evidence.put("externalSettlementRef","GOOD-"+UUID.randomUUID());
  evidence.put("evidencePayload","payment " + payment);
  long evidenceId=value(send("/treasury/settlement-evidence",admin,evidence).andExpect(status().isCreated())).path("id").asLong();
  assertEquals(evidenceId,value(send("/treasury/settlement-evidence",admin,evidence).andExpect(status().isCreated())).path("id").asLong());
  evidence.put("evidenceRef","different evidence location");
  send("/treasury/settlement-evidence",admin,evidence).andExpect(status().isConflict());
  mvc.perform(get("/api/v1/treasury/settlement-evidence/"+evidenceId).header("Authorization","Bearer "+admin))
    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(evidenceId));
  mvc.perform(get("/api/v1/treasury/settlement-cycles/"+cycle).header("Authorization","Bearer "+admin))
    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(cycle));
  long unrelatedJournal=db.queryForObject("SELECT MIN(JOURNAL_ID) FROM M05_GL_JOURNAL WHERE JOURNAL_TYPE<>'SETTLEMENT'",Long.class);
  BigDecimal before=db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class);
  send("/treasury/reserve-movements/confirm",admin,Map.of("evidenceId",evidenceId,"glJournalId",unrelatedJournal))
    .andExpect(status().isConflict());
  assertEquals(0,before.compareTo(db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class)));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER WHERE EVIDENCE_ID=?",Integer.class,evidenceId));
 }

 @Test @Order(61) @Transactional void cycleMovementSettlesPaymentOnceAndBatchAfterFinality() throws Exception {
  String customer=login("customer"),checker=login("checker"),admin=login("admin");
  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Cycle recipient",
    "accountToken","cycle-"+UUID.randomUUID(),"bankCode","TEST0123456"))
    .andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
  long payment=value(send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,
    "railCode","UPI","amount","5.00","requestKey",UUID.randomUUID().toString()))
    .andExpect(status().isOk())).path("paymentId").asLong();
  send("/payments/"+payment+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isOk());
  send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","ACCEPTED")).andExpect(status().isOk());
  long batch=value(send("/clearing-batches",admin,Map.of("railCode","UPI","batchReference",UUID.randomUUID().toString(),
    "direction","OUTBOUND")).andExpect(status().isOk())).path("CLEARING_BATCH_ID").asLong();
  send("/clearing-batches/"+batch+"/items",admin,Map.of("paymentId",payment,"externalItemRef",UUID.randomUUID().toString()))
    .andExpect(status().isOk());
  send("/clearing-batches/"+batch+"/close",admin,Map.of()).andExpect(status().isOk());
  long cycle=value(send("/treasury/settlement-cycles",admin,Map.of("railCode","UPI","reserveAccountId",1,
    "cycleReference",UUID.randomUUID().toString(),"clearingBatchId",batch,"direction","OUTBOUND"))
    .andExpect(status().isCreated())).path("id").asLong();
  send("/treasury/settlement-cycles/"+cycle+"/items",admin,Map.of("items",List.of(Map.of("paymentId",payment,
    "direction","OUT","amount","5.00")))).andExpect(status().isOk());
  send("/treasury/settlement-cycles/"+cycle+"/close",admin,Map.of()).andExpect(status().isOk());
  long evidence=value(send("/treasury/settlement-evidence",admin,Map.of("reserveAccountId",1,
    "settlementCycleId",cycle,"railCode","UPI","evidenceType","CLEARING_CONFIRMATION",
    "evidenceStatus","VERIFIED","movementSide","OUT","amount","5.00",
    "externalSettlementRef","CYCLE-"+UUID.randomUUID(),"evidencePayload","verified cycle " + cycle,
    "occurredAt",java.time.OffsetDateTime.now().toString())).andExpect(status().isCreated())).path("id").asLong();
  var journal=ledger.postJournal(new com.moneybags.txn.api.Contracts.JournalRequest("cycle-settle:"+cycle,
    "SETTLEMENT",null,null,cycle,null,null,null,java.time.LocalDate.now(),"Cycle settlement",List.of(
      new com.moneybags.txn.api.Contracts.JournalLine(3L,null,null,null,"DR",new BigDecimal("5.00"),"Clear suspense"),
      new com.moneybags.txn.api.Contracts.JournalLine(6L,null,null,null,"CR",new BigDecimal("5.00"),"Reserve settlement"))),"admin");
  BigDecimal reserveBefore=db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class);
  long entry=value(send("/treasury/reserve-movements/confirm",admin,Map.of("evidenceId",evidence,"glJournalId",journal.journalId()))
    .andExpect(status().isOk())).path("id").asLong();
  assertEquals(0,reserveBefore.subtract(new BigDecimal("5.00")).compareTo(db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class)));
  assertEquals("CONSUMED",db.queryForObject("SELECT STATUS FROM M07_TREASURY_LIQUIDITY_HOLD WHERE PAYMENT_ID=?",String.class,payment));
  assertEquals(entry,value(send("/treasury/reserve-movements/confirm",admin,Map.of("evidenceId",evidence,"glJournalId",journal.journalId()))
    .andExpect(status().isOk())).path("id").asLong());
  send("/clearing-batches/"+batch+"/settlement",admin,Map.of("settlementCycleId",cycle)).andExpect(status().isOk());
  assertEquals("SETTLEMENT_PENDING",db.queryForObject("SELECT BATCH_STATUS FROM M06_CLEARING_BATCH WHERE CLEARING_BATCH_ID=?",String.class,batch));
  long railEvidence=paymentService.evidence(payment,new com.moneybags.payments.api.Contracts.RailEvidence(
    "SIM-"+payment,"SETTLED-CYCLE-"+cycle,"SETTLEMENT","SETTLED",null,"simulation://cycle/"+cycle,
    java.util.HexFormat.of().formatHex(com.moneybags.integration.CustomerHashService.digest("cycle-"+cycle)),
    java.time.OffsetDateTime.now()));
  var current=paymentService.get(payment);
  paymentService.transition(payment,new com.moneybags.payments.api.Contracts.Transition("SETTLED",current.version(),
    railEvidence,journal.journalId(),entry,null,null,"SIMULATED"));
  assertEquals("SETTLED",db.queryForObject("SELECT BATCH_STATUS FROM M06_CLEARING_BATCH WHERE CLEARING_BATCH_ID=?",String.class,batch));
  send("/clearing-batches/"+batch+"/settlement",admin,Map.of("settlementCycleId",cycle)).andExpect(status().isOk());
  assertEquals("SETTLED",db.queryForObject("SELECT BATCH_STATUS FROM M06_CLEARING_BATCH WHERE CLEARING_BATCH_ID=?",String.class,batch));
 }

 @Test @Order(62) @Transactional void individuallySettledPaymentFinalizesCycleWithoutAnotherReserveMovement() throws Exception {
  String customer=login("customer"),checker=login("checker"),admin=login("admin");
  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Individual settlement recipient",
    "accountToken","individual-"+UUID.randomUUID(),"bankCode","TEST0123456"))
    .andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
  long payment=value(send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,
    "railCode","UPI","amount","6.00","requestKey",UUID.randomUUID().toString()))
    .andExpect(status().isOk())).path("paymentId").asLong();
  send("/payments/"+payment+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isOk());
  send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","ACCEPTED")).andExpect(status().isOk());
  long batch=value(send("/clearing-batches",admin,Map.of("railCode","UPI","batchReference",UUID.randomUUID().toString(),
    "direction","OUTBOUND")).andExpect(status().isOk())).path("CLEARING_BATCH_ID").asLong();
  send("/clearing-batches/"+batch+"/items",admin,Map.of("paymentId",payment,"externalItemRef",UUID.randomUUID().toString()))
    .andExpect(status().isOk());
  send("/clearing-batches/"+batch+"/close",admin,Map.of()).andExpect(status().isOk());
  long cycle=value(send("/treasury/settlement-cycles",admin,Map.of("railCode","UPI","reserveAccountId",1,
    "cycleReference",UUID.randomUUID().toString(),"clearingBatchId",batch,"direction","OUTBOUND"))
    .andExpect(status().isCreated())).path("id").asLong();
  send("/treasury/settlement-cycles/"+cycle+"/items",admin,Map.of("items",List.of(Map.of("paymentId",payment,
    "direction","OUT","amount","6.00")))).andExpect(status().isOk());
  send("/treasury/settlement-cycles/"+cycle+"/close",admin,Map.of()).andExpect(status().isOk());
  send("/treasury/settlement-cycles/"+cycle+"/finalize",admin,Map.of()).andExpect(status().isConflict());
  BigDecimal before=db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class);
  send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","SETTLED")).andExpect(status().isOk());
  int movements=db.queryForObject("SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER WHERE PAYMENT_ID=?",Integer.class,payment);
  send("/treasury/settlement-cycles/"+cycle+"/finalize",admin,Map.of()).andExpect(status().isOk())
    .andExpect(jsonPath("$.status").value("SETTLED"));
  send("/treasury/settlement-cycles/"+cycle+"/finalize",admin,Map.of()).andExpect(status().isOk());
  assertEquals(movements,db.queryForObject("SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER WHERE PAYMENT_ID=?",Integer.class,payment));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER WHERE SETTLEMENT_CYCLE_ID=?",Integer.class,cycle));
  assertEquals(0,before.subtract(new BigDecimal("6.00")).compareTo(db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class)));
  send("/clearing-batches/"+batch+"/settlement",admin,Map.of("settlementCycleId",cycle)).andExpect(status().isOk());
  assertEquals("SETTLED",db.queryForObject("SELECT BATCH_STATUS FROM M06_CLEARING_BATCH WHERE CLEARING_BATCH_ID=?",String.class,batch));
 }

 @Test @Order(63) @Transactional void loanOfferAccountsEvidenceAndServicingRespectCurrentState() throws Exception {
  String admin=login("admin"),checker=login("checker"),customer=login("customer");
  var request=Map.of("cifId","demo-cif-1","productId",2,"productVersionId",2,"branchCode","MUM001",
    "channelCode","BRANCH","amount","12000.001","tenureMonths",12,"purposeCode","PERSONAL");
  send("/loans/applications",admin,request).andExpect(status().isBadRequest());
  var valid=new HashMap<String,Object>(request);valid.put("amount","12000.00");
  db.update("UPDATE M03_PM_LOAN_RULE SET REPAYMENT_FREQUENCY='WEEKLY' WHERE PRODUCT_VERSION_ID=2");
  send("/loans/applications",admin,valid).andExpect(status().isConflict());
  db.update("UPDATE M03_PM_LOAN_RULE SET REPAYMENT_FREQUENCY='MONTHLY' WHERE PRODUCT_VERSION_ID=2");
  db.update("UPDATE M03_PM_INTEREST_RULE SET INTEREST_METHOD='REDUCING_BALANCE' WHERE PRODUCT_VERSION_ID=2 AND INTEREST_TYPE='FIXED'");
  long app=value(send("/loans/applications",admin,valid).andExpect(status().isOk())).path("data").path("id").asLong();
  send("/loans/applications/"+app+"/submit",admin,Map.of()).andExpect(status().isOk());
  send("/loans/applications/"+app+"/assessments",admin,Map.of("monthlyIncome","50000","monthlyObligations","5000",
    "recommendation","APPROVE","reasonCodes","AFFORDABLE")).andExpect(status().isOk());
  send("/loans/applications/"+app+"/decisions",checker,Map.of("code","APPROVE","reasonCodes","APPROVED",
    "sanctionedAmount","12000","sanctionedTenureMonths",12,"annualRatePct","12",
    "authorityCode","LOAN_SANCTION","authorityEvidenceRef","TEST-AUTHORITY")).andExpect(status().isOk());
  long staleDoc=value(send("/loans/applications/"+app+"/documents",admin,Map.of("type","SIGNED_OFFER",
    "storageReference","test://old-document","sha256Hex","a".repeat(64))).andExpect(status().isOk()))
    .path("data").path("documentRefId").asLong();
  long offer=value(send("/loans/applications/"+app+"/offers",admin,Map.of("documentRef","test://offer",
    "expiresAt",java.time.OffsetDateTime.now().plusDays(7).toString())).andExpect(status().isOk()))
    .path("data").path("offerId").asLong();
  var selected=Map.of("disbursementAccountId",1,"repaymentAccountId",1);
  db.update("UPDATE M04_BANK_ACCOUNT SET ACCOUNT_STATUS='FROZEN' WHERE ACCOUNT_ID=1");
  send("/loans/offers/"+offer+"/accept",customer,selected).andExpect(status().isConflict());
  db.update("UPDATE M04_BANK_ACCOUNT SET ACCOUNT_STATUS='ACTIVE' WHERE ACCOUNT_ID=1");
  send("/loans/offers/"+offer+"/accept",customer,selected).andExpect(status().isOk());
  send("/loans/offers/"+offer+"/accept",customer,selected).andExpect(status().isOk());
  String adminId=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='admin'",String.class);
  String accountNumber="LNTEST"+UUID.randomUUID().toString().substring(0,12);
  db.update("INSERT INTO M04_BANK_ACCOUNT(ACCOUNT_NUMBER,PRIMARY_CIF_ID,PRODUCT_ID,PRODUCT_VERSION_ID,BRANCH_CODE,CURRENCY_CODE,LIFECYCLE_STATUS,ACCOUNT_STATUS,ACCOUNT_OPERATION_MODE,MAJORITY_REVIEW_STATUS,OPEN_REQUEST_ID,CREATED_BY_USER_ID,ACTIVATED_AT) VALUES (?,'demo-cif-1',1,1,'MUM001','INR','ACTIVE','ACTIVE','SELF_OPERATED','NOT_APPLICABLE',?,?,SYSTIMESTAMP)",
    accountNumber,UUID.randomUUID().toString(),adminId);
  long alternate=db.queryForObject("SELECT ACCOUNT_ID FROM M04_BANK_ACCOUNT WHERE ACCOUNT_NUMBER=?",Long.class,accountNumber);
  db.update("INSERT INTO M04_ACCOUNT_PARTY(ACCOUNT_ID,CIF_ID,PARTY_ROLE,OPERATING_INSTRUCTION,IS_ACTIVE,EFFECTIVE_FROM,CREATED_BY_USER_ID) VALUES (?,'demo-cif-1','PRIMARY_HOLDER','SELF','Y',SYSDATE,?)",alternate,adminId);
  send("/loans/offers/"+offer+"/accept",customer,Map.of("disbursementAccountId",alternate,"repaymentAccountId",1))
    .andExpect(status().isOk());
  assertEquals(alternate,db.queryForObject("SELECT DISBURSEMENT_ACCOUNT_ID FROM M08_LOAN_OFFER WHERE OFFER_ID=?",Long.class,offer));
  send("/loans/offers/"+offer+"/accept",customer,selected).andExpect(status().isOk());
  send("/loans/applications/"+app+"/documents/"+staleDoc+"/verify",checker,Map.of()).andExpect(status().isOk());
  send("/loans/applications/"+app+"/convert",admin,Map.of()).andExpect(status().isConflict());
  long signed=value(send("/loans/applications/"+app+"/documents",admin,Map.of("type","SIGNED_OFFER",
    "storageReference","test://current-document","sha256Hex","b".repeat(64))).andExpect(status().isOk()))
    .path("data").path("documentRefId").asLong();
  send("/loans/applications/"+app+"/documents/"+signed+"/verify",checker,Map.of()).andExpect(status().isOk());
  send("/loans/offers/"+offer+"/accept",customer,Map.of("disbursementAccountId",alternate,"repaymentAccountId",1))
    .andExpect(status().isOk());
  send("/loans/offers/"+offer+"/accept",customer,selected).andExpect(status().isOk());
  send("/loans/applications/"+app+"/convert",admin,Map.of()).andExpect(status().isConflict());
  long correctedSigned=value(send("/loans/applications/"+app+"/documents",admin,Map.of("type","SIGNED_OFFER",
    "storageReference","test://corrected-document","sha256Hex","c".repeat(64))).andExpect(status().isOk()))
    .path("data").path("documentRefId").asLong();
  send("/loans/applications/"+app+"/documents/"+correctedSigned+"/verify",checker,Map.of()).andExpect(status().isOk());
  // Production evidence ordering must work without manually advancing document timestamps.
  assertEquals(1,db.queryForObject("""
    SELECT COUNT(*) FROM M08_LOAN_DOCUMENT_REF WHERE DOCUMENT_REF_ID=? AND CREATED_AT>
      (SELECT MAX(OCCURRED_AT) FROM M08_SECURITY_AUDIT_EVENT WHERE ACTION_CODE='OFFER_ACCOUNTS_UPDATED'
        AND RESOURCE_TYPE='OFFER' AND RESOURCE_ID=?)
    """,Integer.class,correctedSigned,String.valueOf(offer)));
  long pendingDoc=value(send("/loans/applications/"+app+"/documents",admin,Map.of("type","INCOME_PROOF",
    "storageReference","test://unverified","sha256Hex","e".repeat(64))).andExpect(status().isOk()))
    .path("data").path("documentRefId").asLong();
  db.update("UPDATE M04_BANK_ACCOUNT SET ACCOUNT_STATUS='FROZEN' WHERE ACCOUNT_ID=1");
  send("/loans/applications/"+app+"/convert",admin,Map.of()).andExpect(status().isConflict());
  db.update("UPDATE M04_BANK_ACCOUNT SET ACCOUNT_STATUS='ACTIVE' WHERE ACCOUNT_ID=1");
  long facility=value(send("/loans/applications/"+app+"/convert",admin,Map.of()).andExpect(status().isOk()))
    .path("data").path("facilityId").asLong();
  send("/loans/applications/"+app+"/documents",admin,Map.of("type","SIGNED_OFFER",
    "storageReference","test://too-late","sha256Hex","d".repeat(64))).andExpect(status().isConflict());
  send("/loans/applications/"+app+"/documents/"+pendingDoc+"/verify",checker,Map.of()).andExpect(status().isConflict());
  String key=UUID.randomUUID().toString();
  long disbursement=value(send("/loans/facilities/"+facility+"/disbursements",admin,Map.of("requestKey",key))
    .andExpect(status().isOk())).path("disbursementId").asLong();
  assertEquals(disbursement,value(send("/loans/facilities/"+facility+"/disbursements",admin,Map.of("requestKey",key))
    .andExpect(status().isOk())).path("disbursementId").asLong());
  send("/loans/facilities/"+facility+"/disbursements",admin,Map.of("requestKey",UUID.randomUUID().toString()))
    .andExpect(status().isConflict());
  send("/loans/facilities/"+facility+"/repayments",admin,Map.of("requestKey",UUID.randomUUID().toString(),"amount","1"))
    .andExpect(status().isConflict());
  send("/loans/disbursements/"+disbursement+"/approve",checker,Map.of("authorityCode","LOAN_SANCTION"))
    .andExpect(status().isOk());
  db.update("UPDATE M08_LOAN_SCHEDULE_ITEM SET DUE_DATE=? WHERE FACILITY_ID=? AND INSTALLMENT_NO=1",
    java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")),facility);
  var currentCollections=value(mvc.perform(get("/api/v1/loans/collections").header("Authorization","Bearer "+admin))
    .andExpect(status().isOk()));
  assertFalse(java.util.stream.StreamSupport.stream(currentCollections.spliterator(),false)
    .anyMatch(row->row.path("FACILITY_ID").asLong()==facility));
  db.update("UPDATE M08_LOAN_SCHEDULE_ITEM SET DUE_DATE=? WHERE FACILITY_ID=? AND INSTALLMENT_NO=1",
    java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(1),facility);
  var overdue=value(mvc.perform(get("/api/v1/loans/collections").header("Authorization","Bearer "+admin))
    .andExpect(status().isOk()));
  assertTrue(java.util.stream.StreamSupport.stream(overdue.spliterator(),false)
    .anyMatch(row->row.path("FACILITY_ID").asLong()==facility));
  String role=UUID.randomUUID().toString(),user=UUID.randomUUID().toString();
  String username="loan_collector_"+UUID.randomUUID().toString().substring(0,8);
  db.update("INSERT INTO M01_IAM_ROLE(ROLE_ID,ROLE_CODE,DISPLAY_NAME,STATUS,SENSITIVE_FLAG) VALUES (?,?,'Loan collector','ACTIVE','N')",
    role,"LOAN_COLLECTOR_"+username);
  db.update("INSERT INTO M01_IAM_USER(USER_ID,USERNAME,USER_TYPE,STATUS,EMPLOYEE_REF) VALUES (?,?,'EMPLOYEE','ACTIVE','TEST')",user,username);
  db.update("INSERT INTO M01_IAM_CREDENTIAL(CREDENTIAL_ID,USER_ID,PASSWORD_HASH,HASH_SCHEME,STATUS) VALUES (?,?,?,'BCRYPT','ACTIVE')",
    UUID.randomUUID().toString(),user,passwords.encode("LocalBanking!2026"));
  db.update("INSERT INTO M01_IAM_ROLE_PERMISSION(ROLE_ID,PERMISSION_ID) SELECT ?,PERMISSION_ID FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE='LOAN_SERVICE'",role);
  db.update("INSERT INTO M01_IAM_USER_ROLE(ASSIGNMENT_ID,USER_ID,ROLE_ID,SCOPE_TYPE,SCOPE_REF,STATUS,VALID_FROM) VALUES (?,?,?,'BRANCH','MUM001','ACTIVE',SYSTIMESTAMP)",
    UUID.randomUUID().toString(),user,role);
  String collector=login(username);
  var visible=value(mvc.perform(get("/api/v1/loans/collections").header("Authorization","Bearer "+collector))
    .andExpect(status().isOk()));
  assertTrue(java.util.stream.StreamSupport.stream(visible.spliterator(),false)
    .anyMatch(row->row.path("FACILITY_ID").asLong()==facility));
  db.update("UPDATE M08_LOAN_FACILITY SET BRANCH_CODE='TEST001' WHERE FACILITY_ID=?",facility);
  var hidden=value(mvc.perform(get("/api/v1/loans/collections").header("Authorization","Bearer "+collector))
    .andExpect(status().isOk()));
  assertFalse(java.util.stream.StreamSupport.stream(hidden.spliterator(),false)
    .anyMatch(row->row.path("FACILITY_ID").asLong()==facility));
  db.update("UPDATE M08_LOAN_FACILITY SET BRANCH_CODE='MUM001' WHERE FACILITY_ID=?",facility);
  send("/loans/facilities/"+facility+"/accruals",admin,Map.of()).andExpect(status().isOk());
  db.update("UPDATE M04_BANK_ACCOUNT SET ACCOUNT_STATUS='FROZEN' WHERE ACCOUNT_ID=1");
  send("/loans/facilities/"+facility+"/repayments",admin,Map.of("requestKey",UUID.randomUUID().toString(),"amount","1"))
    .andExpect(status().isConflict());
  long second=value(send("/loans/applications",admin,valid).andExpect(status().isOk())).path("data").path("id").asLong();
  send("/loans/applications/"+second+"/submit",admin,Map.of()).andExpect(status().isOk());
  send("/loans/applications/"+second+"/assessments",admin,Map.of("monthlyIncome","50000","monthlyObligations","5000",
    "recommendation","APPROVE","reasonCodes","AFFORDABLE")).andExpect(status().isOk());
  send("/loans/applications/"+second+"/decisions",checker,Map.of("code","APPROVE","reasonCodes","APPROVED",
    "sanctionedAmount","12000","sanctionedTenureMonths",12,"annualRatePct","12",
    "authorityCode","LOAN_SANCTION","authorityEvidenceRef","TEST-AUTHORITY")).andExpect(status().isOk());
  long expiring=value(send("/loans/applications/"+second+"/offers",admin,Map.of("documentRef","test://expiring-offer",
    "expiresAt",java.time.OffsetDateTime.now().plusDays(7).toString())).andExpect(status().isOk()))
    .path("data").path("offerId").asLong();
  send("/loans/offers/"+expiring+"/expire",admin,Map.of()).andExpect(status().isConflict());
  db.update("UPDATE M08_LOAN_OFFER SET ISSUED_AT=?,EXPIRES_AT=? WHERE OFFER_ID=?",
    java.time.OffsetDateTime.now().minusDays(3),java.time.OffsetDateTime.now().minusDays(1),expiring);
  send("/loans/offers/"+expiring+"/expire",admin,Map.of()).andExpect(status().isOk());
  send("/loans/offers/"+expiring+"/expire",admin,Map.of()).andExpect(status().isOk());
  assertEquals("EXPIRED",db.queryForObject("SELECT STATUS FROM M08_LOAN_APPLICATION WHERE APPLICATION_ID=?",String.class,second));
  send("/loans/offers/"+expiring+"/accept",customer,selected).andExpect(status().isConflict());
 }

 @Test @Order(64) @Transactional void privacyHoldCategoryRespectsTargetService() {
  String id=UUID.randomUUID().toString();
  db.update("""
    INSERT INTO M09_PRIVACY_LEGAL_HOLD
      (LEGAL_HOLD_ID,HOLD_REFERENCE,HOLD_STATUS,AUTHORITY_REFERENCE,REASON,
       TARGET_SERVICE,RECORD_CATEGORY,STARTED_AT,REVIEW_DUE_AT,CREATED_BY,APPROVED_BY,IDEMPOTENCY_KEY)
    VALUES (?,?,'ACTIVE','court','Preserve records','LOANS','ACCOUNT_RECORD',
            SYSTIMESTAMP,SYSTIMESTAMP,'maker','checker',?)
    """,id,"HOLD-"+id,"hold-"+id);
  assertTrue(legalHolds.hasActiveMatch(new com.moneybags.privacy.hold.LegalHoldRepository.HoldTarget(
    null,null,"LOANS","FACILITY","123","ACCOUNT_RECORD",null)));
  assertFalse(legalHolds.hasActiveMatch(new com.moneybags.privacy.hold.LegalHoldRepository.HoldTarget(
    null,null,"ACCOUNTS","ACCOUNT","123","ACCOUNT_RECORD",null)));
 }
 @Test @Order(65) void statementRejectsFutureCutAndAuditsActualDownload() throws Exception {
  String customer=login("customer");
  String future=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(1).toString();
  var invalid=Map.of("accountId",1,"customerCifId","demo-cif-1","statementType","CURRENT",
    "periodStart",future,"periodEnd",future,"format","PDF","channel","WEB");
  send("/reporting/statement-requests",customer,invalid).andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("FUTURE_PERIOD"));
  String historical=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(2).toString();
  var valid=Map.of("accountId",1,"customerCifId","demo-cif-1","statementType","CURRENT",
    "periodStart",historical,"periodEnd",historical,"format","PDF","channel","WEB");
  String requestId=value(send("/reporting/statement-requests",customer,valid).andExpect(status().isCreated())).path("requestId").asText();
  String statementId=value(mvc.perform(post("/api/v1/reporting/statement-requests/"+requestId+"/process")
    .header("Authorization","Bearer "+customer)).andExpect(status().isOk())).path("statementId").asText();
  int before=db.queryForObject("SELECT COUNT(*) FROM M10_STATEMENT_ACCESS_AUDIT WHERE STATEMENT_ID=? AND EVENT_TYPE='DOWNLOAD'",Integer.class,statementId);
  mvc.perform(get("/api/v1/reporting/statements/"+statementId+"/download")
    .header("Authorization","Bearer "+customer)).andExpect(status().isOk());
  assertEquals(before+1,db.queryForObject("SELECT COUNT(*) FROM M10_STATEMENT_ACCESS_AUDIT WHERE STATEMENT_ID=? AND EVENT_TYPE='DOWNLOAD'",Integer.class,statementId));
 }
 @Test @Order(66) @Transactional void privacyAuditRejectsChangedSourceEventReplay() throws Exception {
  var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
  context.setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(
    "M05_LEDGER","unused","SCOPE_PRIVACY_INTERNAL"));
  org.springframework.security.core.context.SecurityContextHolder.setContext(context);
  try {
   String sourceId=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
   var event=new com.moneybags.privacy.audit.AuditController.AuditInput(
     "M05_LEDGER","POSTINGS",sourceId,"TransactionPosted",java.time.OffsetDateTime.now(),
     "SUCCESS",null,null,null,null,null,null,null,null,null,null,null,null,null,Map.of());
   String hash=com.moneybags.statements.Hashing.sha256(json.writeValueAsString(event));
   db.update("""
     INSERT INTO M09_PRIVACY_AUDIT_EVENT
       (AUDIT_EVENT_ID,SOURCE_SERVICE,SOURCE_STREAM,SOURCE_EVENT_ID,EVENT_TYPE,OCCURRED_AT,
        OUTCOME,EVENT_METADATA,EVENT_SHA256,RETENTION_UNTIL,CREATED_BY)
     VALUES (?,?,?,?,?,?,?,'{}',?,?,?)
     """,id,"M05_LEDGER","POSTINGS",sourceId,"TransactionPosted",event.occurredAt(),
       "SUCCESS",hash,event.occurredAt().plusYears(1),"M05_LEDGER");
   assertEquals("DUPLICATE",privacyAudit.ingest(event).get("status"));
   var changed=new com.moneybags.privacy.audit.AuditController.AuditInput(
     "M05_LEDGER","POSTINGS",sourceId,"TransactionPosted",event.occurredAt(),
     "DENIED",null,null,null,null,null,null,null,null,null,null,null,null,null,Map.of());
   assertEquals("AUDIT_EVENT_CONFLICT",assertThrows(com.moneybags.privacy.common.ApiException.class,
     ()->privacyAudit.ingest(changed)).code());
  } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
 }
 @Test @Order(67) @Transactional void privacyCaseIdentityReviewerMustBeIndependent() {
  String id=UUID.randomUUID().toString();
  db.update("""
    INSERT INTO M09_PRIVACY_COMPLIANCE_CASE
      (COMPLIANCE_CASE_ID,CASE_REFERENCE,CASE_TYPE,CASE_STATUS,PRIORITY,
       CREATED_BY,UPDATED_BY,IDEMPOTENCY_KEY,VERSION_NO)
    VALUES (?,?,'ACCESS_REQUEST','IDENTITY_PENDING','NORMAL','maker','maker',?,1)
    """,id,"PRIV-"+id,"case-"+id);
  assertEquals(0,privacyCases.verifyIdentity(id,1,"maker"));
  assertEquals(1,privacyCases.verifyIdentity(id,1,"checker"));
 }
 @Test @Order(68) @Transactional void statementStaffPurposeMustBeCurrentlyEffective() throws Exception {
  String id=UUID.randomUUID().toString(),code="EXPIRED_"+id.substring(0,8);
  db.update("""
    INSERT INTO M09_PRIVACY_PROCESSING_PURPOSE
      (PROCESSING_PURPOSE_ID,PURPOSE_CODE,PURPOSE_VERSION,PURPOSE_NAME,OWNER_SERVICE,
       LEGAL_BASIS_CODE,STATUS,EFFECTIVE_FROM,EFFECTIVE_TO,APPROVED_AT,APPROVED_BY,
       CREATED_BY,UPDATED_BY)
    VALUES (?,?,1,'Expired reporting','M10_STATEMENTS','CONSENT','ACTIVE',?,?,
            SYSTIMESTAMP,'checker','maker','checker')
    """,id,code,java.time.OffsetDateTime.now().minusDays(3),java.time.OffsetDateTime.now().minusDays(1));
  String admin=login("admin"),day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(31).toString();
  var body=Map.of("accountId",1,"customerCifId","demo-cif-1","statementType","AD_HOC",
    "periodStart",day,"periodEnd",day,"format","PDF","channel","WEB","purposeCode",code);
  mvc.perform(post("/api/v1/reporting/statement-requests")
    .header("Authorization","Bearer "+admin).header("X-Customer-Hash",HASH)
    .header("Idempotency-Key",UUID.randomUUID().toString())
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
    .andExpect(status().isForbidden());
  String activeCode="ACTIVE_"+id.substring(0,8);
  db.update("""
    INSERT INTO M09_PRIVACY_PROCESSING_PURPOSE
      (PROCESSING_PURPOSE_ID,PURPOSE_CODE,PURPOSE_VERSION,PURPOSE_NAME,OWNER_SERVICE,
       LEGAL_BASIS_CODE,STATUS,EFFECTIVE_FROM,EFFECTIVE_TO,APPROVED_AT,APPROVED_BY,
       CREATED_BY,UPDATED_BY)
    VALUES (?,?,1,'Active reporting','M10_STATEMENTS','CONSENT','ACTIVE',?,NULL,
            SYSTIMESTAMP,'checker','maker','checker')
    """,UUID.randomUUID().toString(),activeCode,java.time.OffsetDateTime.now().minusDays(1));
  var active=Map.of("accountId",1,"customerCifId","demo-cif-1","statementType","AD_HOC",
    "periodStart",day,"periodEnd",day,"format","PDF","channel","WEB","purposeCode",activeCode);
  mvc.perform(post("/api/v1/reporting/statement-requests")
    .header("Authorization","Bearer "+admin).header("X-Customer-Hash",HASH)
    .header("Idempotency-Key",UUID.randomUUID().toString())
    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(active)))
    .andExpect(status().isCreated());
 }
 @Test @Order(100) @Transactional void fixedDepositPlacementDoesNotConsumeTransferAllowance()throws Exception{
  String customer=login("customer");
  db.update("DELETE FROM M03_PM_LIMIT_RULE WHERE PRODUCT_VERSION_ID=1 AND OPERATION_CODE='INTERNAL_TRANSFER'");
  db.update("DELETE FROM M04_ACCOUNT_LIMIT WHERE ACCOUNT_ID=1 AND OPERATION_CODE='INTERNAL_TRANSFER'");
  BigDecimal used=db.queryForObject("SELECT COALESCE(SUM(AMOUNT),0) FROM M05_TXN_TRANSACTION_LOG WHERE SOURCE_ACCOUNT_ID=1 AND TARGET_ACCOUNT_ID IS NOT NULL AND VALUE_DATE=? AND TXN_TYPE='INTERNAL_TRANSFER' AND STATUS IN ('POSTED','REVERSED')",BigDecimal.class,java.time.LocalDate.now());
  db.update("INSERT INTO M03_PM_LIMIT_RULE(PRODUCT_VERSION_ID,RULE_CODE,OPERATION_CODE,PERIOD_CODE,MAX_AMOUNT,CURRENCY_CODE) VALUES (1,'FD_TRANSFER_REGRESSION','INTERNAL_TRANSFER','DAY',?,'INR')",used.add(new BigDecimal("50")));
  db.update("INSERT INTO M04_ACCOUNT_LIMIT(ACCOUNT_ID,CHANGE_REQUEST_ID,LIMIT_TYPE,OPERATION_CODE,PERIOD_CODE,RESET_RULE_CODE,TIME_ZONE_ID,LIMIT_AMOUNT,EFFECTIVE_FROM,IS_ACTIVE,CREATED_BY_USER_ID) VALUES (1,?,'CUSTOMER','INTERNAL_TRANSFER','DAY','CALENDAR_DAY','Asia/Kolkata',?,SYSDATE,'Y',?)",UUID.randomUUID().toString(),used.add(new BigDecimal("50")),db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='admin'",String.class));
  send("/term-deposits",customer,Map.of("productId",3,"productVersionId",3,"fundingAccountId",1,"amount","1000.00","tenureMonths",1,"requestKey",UUID.randomUUID().toString())).andExpect(status().isOk());
  BigDecimal before=balance(1);
  send("/transactions/transfers",customer,transfer(1,2,"10",UUID.randomUUID().toString())).andExpect(status().isOk());
  assertEquals(0,before.subtract(new BigDecimal("10")).compareTo(balance(1)));
  send("/transactions/transfers",customer,transfer(1,2,"50",UUID.randomUUID().toString())).andExpect(status().isConflict());
 }

 @Test @Order(101) @Transactional void consentReadersSeeOnlyCurrentApprovedPurposeNotices()throws Exception{
  var now=java.time.OffsetDateTime.now();
  String prefix="CATALOG_"+UUID.randomUUID().toString().substring(0,8);
  String currentCode=prefix+"_CURRENT";
  Object[][] versions={
   {currentCode,1,"ACTIVE",now.minusDays(2),null},
   {currentCode,2,"ACTIVE",now.minusDays(1),null},
   {currentCode,3,"ACTIVE",now.plusDays(1),null},
   {prefix+"_DRAFT",1,"DRAFT",now.minusDays(1),null},
   {prefix+"_SUSPENDED",1,"SUSPENDED",now.minusDays(1),null},
   {prefix+"_EXPIRED",1,"ACTIVE",now.minusDays(2),now.minusDays(1)}
  };
  for(var row:versions)db.update("""
    INSERT INTO M09_PRIVACY_PROCESSING_PURPOSE
      (PROCESSING_PURPOSE_ID,PURPOSE_CODE,PURPOSE_VERSION,PURPOSE_NAME,OWNER_SERVICE,
       LEGAL_BASIS_CODE,NOTICE_URI,NOTICE_SHA256,STATUS,EFFECTIVE_FROM,EFFECTIVE_TO,
       APPROVED_AT,APPROVED_BY,CREATED_BY,UPDATED_BY)
    VALUES (?,?,?,'Approved customer notice','CIF','CONSENT','https://bank.example/privacy',
            ?,?,?,?,SYSTIMESTAMP,'checker','maker','checker')
    """,UUID.randomUUID().toString(),row[0],row[1],"a".repeat(64),row[2],row[3],row[4]);
  mvc.perform(get("/api/v1/privacy/purposes/available")).andExpect(status().isUnauthorized());
  for(String role:List.of("customer","customer2","checker","admin")){
   String token=login(role);
   var response=mvc.perform(get("/api/v1/privacy/purposes/available")
    .header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn();
   var catalog=json.readTree(response.getResponse().getContentAsString());
   var matching=new ArrayList<JsonNode>();
   catalog.forEach(row->{if(row.path("code").asText().startsWith(prefix))matching.add(row);});
   assertEquals(1,matching.size());
   assertEquals(currentCode,matching.get(0).path("code").asText());
   assertEquals(2,matching.get(0).path("version").asInt());
   var fields=new HashSet<String>();
   matching.get(0).fieldNames().forEachRemaining(fields::add);
   assertEquals(Set.of("code","version","name","legalBasisCode","noticeUri","noticeSha256"),fields);
   if(role.startsWith("customer"))mvc.perform(get("/api/v1/privacy/purposes")
    .header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  }
 }
 @Test @Order(102) @Transactional void numberTransferDoesNotRequireReadingRecipientAndChecksSenderFirst()throws Exception{
  String customer=login("customer"),other=login("customer2");
  BigDecimal before=balance(1),otherBefore=balance(2);
  var body=Map.of("sourceAccountNumber","MB000000001","targetAccountNumber","MB000000002",
    "amount","1.00","requestKey",UUID.randomUUID().toString(),"correlationId",UUID.randomUUID().toString());
  var result=send("/transactions/transfers/by-number",customer,body).andExpect(status().isOk());
  assertEquals("POSTED",value(result).path("status").asText());
  assertEquals(0,before.subtract(BigDecimal.ONE).compareTo(balance(1)));
  assertEquals(0,otherBefore.add(BigDecimal.ONE).compareTo(balance(2)));
  send("/transactions/transfers/by-number",customer,body).andExpect(status().isOk());
  assertEquals(0,before.subtract(BigDecimal.ONE).compareTo(balance(1)));
  long txn=value(result).path("transactionId").asLong();
  mvc.perform(get("/api/v1/banking/transactions").param("accountId","1").header("Authorization","Bearer "+customer))
    .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.transactionId == "+txn+")].accountRole").value(org.hamcrest.Matchers.contains("SOURCE")));
  mvc.perform(get("/api/v1/banking/transactions").param("accountId","2").header("Authorization","Bearer "+other))
    .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.transactionId == "+txn+")].accountRole").value(org.hamcrest.Matchers.contains("TARGET")));
  send("/transactions/transfers/by-number",customer,Map.of("sourceAccountNumber","MB000000001",
    "targetAccountNumber","MB000000002","amount",before.add(new BigDecimal("1000000")).toPlainString(),
    "requestKey",UUID.randomUUID().toString(),"correlationId",UUID.randomUUID().toString())).andExpect(status().is4xxClientError());
  assertEquals(0,before.subtract(BigDecimal.ONE).compareTo(balance(1)));
  assertEquals(0,otherBefore.add(BigDecimal.ONE).compareTo(balance(2)));
  mvc.perform(get("/api/v1/accounts/by-number/MB000000002").header("Authorization","Bearer "+customer))
    .andExpect(status().isForbidden());
  send("/transactions/transfers/by-number",other,Map.of("sourceAccountNumber","MB000000001",
    "targetAccountNumber","NONEXISTENT","amount","1.00","requestKey",UUID.randomUUID().toString(),"correlationId",UUID.randomUUID().toString()))
    .andExpect(status().isForbidden());
  assertEquals(0,before.subtract(BigDecimal.ONE).compareTo(balance(1)));
 }

 @Test @Order(103) @Transactional void disabledSimulationReportsAvailabilityAndRejectsWithoutCreatingPayment()throws Exception{
  String customer=login("customer");
  int before=db.queryForObject("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION",Integer.class);
  org.springframework.test.util.ReflectionTestUtils.setField(paymentWorkflow,"simulation",false);
  org.springframework.test.util.ReflectionTestUtils.setField(paymentService,"simulation",false);
  try{
   mvc.perform(get("/api/v1/payments/availability")).andExpect(status().isUnauthorized());
   mvc.perform(get("/api/v1/payments/availability").header("Authorization","Bearer "+customer))
     .andExpect(status().isOk()).andExpect(jsonPath("$.initiationEnabled").value(false)).andExpect(jsonPath("$.mode").value("UNAVAILABLE"));
   send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId","not-used",
     "railCode","UPI","amount","1.00","requestKey",UUID.randomUUID().toString()))
     .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SIMULATION_DISABLED"));
   String originator=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='customer'",String.class);
   send("/payments",customer,Map.ofEntries(Map.entry("originatorId",originator),Map.entry("channelCode","WEB"),
     Map.entry("requestKey",UUID.randomUUID().toString()),Map.entry("correlationId",UUID.randomUUID().toString()),
     Map.entry("endToEndId",UUID.randomUUID().toString()),Map.entry("direction","OUTBOUND"),Map.entry("kind","PAYMENT"),
     Map.entry("sourceAccountId",1),Map.entry("sourceCifId","demo-cif-1"),Map.entry("beneficiaryAccountToken","unused"),
     Map.entry("beneficiaryBankCode","TEST0123456"),Map.entry("railCode","UPI"),Map.entry("amount","1.00"),
     Map.entry("requestedExecutionDate",java.time.LocalDate.now().toString())))
     .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SIMULATION_DISABLED"));
   assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION",Integer.class));
  }finally{
   org.springframework.test.util.ReflectionTestUtils.setField(paymentWorkflow,"simulation",true);
   org.springframework.test.util.ReflectionTestUtils.setField(paymentService,"simulation",true);
  }
 }
 @Test @Order(104) @Transactional void privacyReadinessTracksEffectiveApprovedAuditPolicy()throws Exception{
  String customer=login("customer");
  mvc.perform(get("/api/v1/privacy/consents/availability")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/v1/privacy/consents/availability").header("Authorization","Bearer "+customer))
    .andExpect(status().isOk()).andExpect(jsonPath("$.decisionsEnabled").value(true));
  db.update("UPDATE M09_PRIVACY_RETENTION_SCHEDULE SET EFFECTIVE_TO=? WHERE OWNING_SERVICE='M09_PRIVACY' AND RECORD_CATEGORY='AUDIT_EVENT'",java.time.OffsetDateTime.now().minusSeconds(1));
  mvc.perform(get("/api/v1/privacy/consents/availability").header("Authorization","Bearer "+customer))
    .andExpect(status().isOk()).andExpect(jsonPath("$.decisionsEnabled").value(false));
  mvc.perform(get("/api/v1/privacy/purposes/available").header("Authorization","Bearer "+customer)).andExpect(status().isOk());
 }
 @Test @Order(105) void failedPaymentAuthorizationRollsBackAndUnknownRefundReplaysOnce()throws Exception{
  String customer=login("customer"),other=login("customer2"),checker=login("checker"),admin=login("admin");
  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Deep payment review",
    "accountToken","deep-"+UUID.randomUUID(),"bankCode","TEST0123456")).andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",customer,Map.of()).andExpect(status().isForbidden());
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
  BigDecimal before=balance(1),reserve=reserveBalance("CONFIRMED_BALANCE"),held=reserveBalance("ACTIVE_HOLD_AMOUNT");
  long unfunded=value(send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,
    "railCode","NEFT","amount",before.add(new BigDecimal("100000")).toPlainString(),"requestKey",UUID.randomUUID().toString()))
    .andExpect(status().isOk())).path("paymentId").asLong();
  send("/payments/"+unfunded+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isConflict());
  assertEquals("RECEIVED",db.queryForObject("SELECT STATUS FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",String.class,unfunded));
  for(String table:List.of("M05_FUNDS_HOLD","M07_TREASURY_LIQUIDITY_HOLD","M05_GL_JOURNAL","M06_RAIL_DISPATCH"))
   assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE PAYMENT_ID=?",Integer.class,unfunded));
  assertEquals(0,before.compareTo(balance(1)));assertEquals(0,held.compareTo(reserveBalance("ACTIVE_HOLD_AMOUNT")));
  var request=Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,"railCode","IMPS","amount","2.00","requestKey",UUID.randomUUID().toString());
  long payment=value(send("/payments/initiate",customer,request).andExpect(status().isOk())).path("paymentId").asLong();
  assertEquals(payment,value(send("/payments/initiate",customer,request).andExpect(status().isOk())).path("paymentId").asLong());
  send("/payments/"+payment+"/authorize-simulation",customer,Map.of("reserveAccountId",1)).andExpect(status().isForbidden());
  send("/payments/"+payment+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isOk());
  send("/payments/"+payment+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isOk());
  assertEquals(0,before.subtract(new BigDecimal("2")).compareTo(balance(1)));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M05_GL_JOURNAL WHERE PAYMENT_ID=? AND JOURNAL_TYPE='PAYMENT_PROVISIONAL'",Integer.class,payment));
  long dispatch=db.queryForObject("SELECT DISPATCH_ID FROM M06_RAIL_DISPATCH WHERE PAYMENT_ID=?",Long.class,payment);
  for(String token:List.of(customer,other))mvc.perform(get("/api/v1/dispatches/"+dispatch+"/attempts").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/dispatches/"+dispatch+"/attempts").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$[0].OUTCOME_CODE").value("SENT"));
  send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","UNKNOWN")).andExpect(status().isOk());
  assertEquals(0,before.subtract(new BigDecimal("2")).compareTo(balance(1)));
  send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","REFUNDED")).andExpect(status().isOk());
  send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","REFUNDED")).andExpect(status().isOk());
  assertEquals(0,before.compareTo(balance(1)));assertEquals(0,reserve.compareTo(reserveBalance("CONFIRMED_BALANCE")));
  assertEquals(0,held.compareTo(reserveBalance("ACTIVE_HOLD_AMOUNT")));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M05_GL_JOURNAL WHERE PAYMENT_ID=? AND JOURNAL_TYPE='PAYMENT_REFUND'",Integer.class,payment));
  int evidence=db.queryForObject("SELECT COUNT(*) FROM M06_RAIL_STATUS_EVIDENCE WHERE PAYMENT_ID=?",Integer.class,payment);
  send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","SETTLED")).andExpect(status().isConflict());
  assertEquals(evidence,db.queryForObject("SELECT COUNT(*) FROM M06_RAIL_STATUS_EVIDENCE WHERE PAYMENT_ID=?",Integer.class,payment));
  assertEquals(0,before.compareTo(balance(1)));
 }
 @Test @Order(106) @Transactional void allFourSimulatedRailsSettleOnceWithBalancedJournalsAndReserveMovement()throws Exception{
  String customer=login("customer"),checker=login("checker"),admin=login("admin");
  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Four rail test",
    "accountToken","rails-"+UUID.randomUUID(),"bankCode","TEST0123456")).andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
  String fundingKey=UUID.randomUUID().toString(),adminId=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='admin'",String.class);
  BigDecimal funding=new BigDecimal("300000.00");
  db.update("INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,TARGET_ACCOUNT_ID,PRODUCT_VERSION_ID,AMOUNT,VALUE_DATE) VALUES (?,'BRANCH',?,?,'four-rail-fixture','ADJUSTMENT','POSTED',1,1,?,?)",
    adminId,fundingKey,com.moneybags.integration.CustomerHashService.digest(fundingKey),funding,java.time.LocalDate.now());
  long txn=db.queryForObject("SELECT TXN_ID FROM M05_TXN_TRANSACTION_LOG WHERE REQUEST_KEY=?",Long.class,fundingKey);
  ledger.postJournal(new com.moneybags.txn.api.Contracts.JournalRequest(fundingKey,"ADJUSTMENT",txn,null,null,null,null,null,
    java.time.LocalDate.now(),"Isolated synthetic funding",List.of(
      new com.moneybags.txn.api.Contracts.JournalLine(7L,null,null,null,"DR",funding,"Fixture equity"),
      new com.moneybags.txn.api.Contracts.JournalLine(1L,1L,null,null,"CR",funding,"Fixture customer funds"))),adminId);
  BigDecimal before=balance(1),reserve=reserveBalance("CONFIRMED_BALANCE"),held=reserveBalance("ACTIVE_HOLD_AMOUNT"),total=BigDecimal.ZERO;
  for(String rail:List.of("UPI","IMPS","NEFT","RTGS")){
   BigDecimal amount=new BigDecimal(rail.equals("RTGS")?"200000.00":"1.00");
   long payment=value(send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,
     "railCode",rail,"amount",amount.toPlainString(),"requestKey",UUID.randomUUID().toString())).andExpect(status().isOk())).path("paymentId").asLong();
   send("/payments/"+payment+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isOk());
   send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","ACCEPTED")).andExpect(status().isOk());
   send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","SETTLED")).andExpect(status().isOk());
   send("/payments/"+payment+"/simulate-outcome",admin,Map.of("outcome","SETTLED")).andExpect(status().isOk());
   total=total.add(amount);
   assertEquals(0,before.subtract(total).compareTo(balance(1)));
   assertEquals(0,reserve.subtract(total).compareTo(reserveBalance("CONFIRMED_BALANCE")));
   assertEquals(0,held.compareTo(reserveBalance("ACTIVE_HOLD_AMOUNT")));
   assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM M05_GL_JOURNAL WHERE PAYMENT_ID=?",Integer.class,payment));
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER WHERE PAYMENT_ID=?",Integer.class,payment));
   long debitJournal=db.queryForObject("SELECT JOURNAL_ID FROM M05_GL_JOURNAL WHERE PAYMENT_ID=? AND JOURNAL_TYPE='PAYMENT_PROVISIONAL'",Long.class,payment);
   long settlementJournal=db.queryForObject("SELECT JOURNAL_ID FROM M05_GL_JOURNAL WHERE PAYMENT_ID=? AND JOURNAL_TYPE='SETTLEMENT'",Long.class,payment);
   long railEvidence=db.queryForObject("SELECT EVIDENCE_ID FROM M06_RAIL_STATUS_EVIDENCE WHERE PAYMENT_ID=? AND RAIL_STATUS='SETTLED'",Long.class,payment);
   long treasuryEntry=db.queryForObject("SELECT TREASURY_ENTRY_ID FROM M07_CENTRAL_TREASURY_LEDGER WHERE PAYMENT_ID=?",Long.class,payment);
   String reference=db.queryForObject("SELECT EXTERNAL_SETTLEMENT_REF FROM M07_CENTRAL_TREASURY_LEDGER WHERE PAYMENT_ID=?",String.class,payment);
   send("/payments/"+payment+"/reconciliations",admin,Map.of("reconciledAmount",amount.toPlainString(),
     "customerJournalId",debitJournal,"settlementJournalId",settlementJournal,"railEvidenceId",railEvidence,
     "treasuryEntryId",treasuryEntry,"externalSettlementRef",reference)).andExpect(status().isOk());
   assertEquals("MATCHED",db.queryForObject("SELECT RECONCILIATION_STATUS FROM M06_PAYMENT_RECONCILIATION WHERE PAYMENT_ID=?",String.class,payment));
  }
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M05_V_GL_JOURNAL_CONTROL WHERE IS_BALANCED='N'",Integer.class));
 }
 @Test @Order(107) @Transactional void branchPaymentReaderCannotBrowseBankWideOrForeignAccountPaymentEvidence()throws Exception{
  String role=UUID.randomUUID().toString(),user=UUID.randomUUID().toString(),name="payment_branch_"+UUID.randomUUID().toString().substring(0,8);
  db.update("INSERT INTO M01_IAM_ROLE(ROLE_ID,ROLE_CODE,DISPLAY_NAME,STATUS,SENSITIVE_FLAG) VALUES (?,?,'Branch payment reader','ACTIVE','N')",role,name);
  db.update("INSERT INTO M01_IAM_USER(USER_ID,USERNAME,USER_TYPE,STATUS,EMPLOYEE_REF) VALUES (?,?,'EMPLOYEE','ACTIVE','TEST')",user,name);
  db.update("INSERT INTO M01_IAM_CREDENTIAL(CREDENTIAL_ID,USER_ID,PASSWORD_HASH,HASH_SCHEME,STATUS) VALUES (?,?,?,'BCRYPT','ACTIVE')",UUID.randomUUID().toString(),user,passwords.encode("LocalBanking!2026"));
  db.update("INSERT INTO M01_IAM_ROLE_PERMISSION(ROLE_ID,PERMISSION_ID) SELECT ?,PERMISSION_ID FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE='PAYMENT_READ'",role);
  String assignment=UUID.randomUUID().toString();
  db.update("INSERT INTO M01_IAM_USER_ROLE(ASSIGNMENT_ID,USER_ID,ROLE_ID,SCOPE_TYPE,SCOPE_REF,STATUS,VALID_FROM) VALUES (?,?,?,'BRANCH','TEST001','ACTIVE',?)",assignment,user,role,java.time.OffsetDateTime.now().minusSeconds(1));
  String token=login(name);
  String customer=login("customer"),checker=login("checker");
  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Scope test",
    "accountToken","scope-"+UUID.randomUUID(),"bankCode","TEST0123456")).andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
  long payment=value(send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,
    "railCode","UPI","amount","1.00","requestKey",UUID.randomUUID().toString())).andExpect(status().isOk())).path("paymentId").asLong();
  mvc.perform(get("/api/v1/payments").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  for(String suffix:List.of("","/history","/rail-evidence"))mvc.perform(get("/api/v1/payments/"+payment+suffix).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  db.update("UPDATE M01_IAM_USER_ROLE SET SCOPE_REF='MUM001' WHERE ASSIGNMENT_ID=?",assignment);
  token=login(name);
  mvc.perform(get("/api/v1/payments/"+payment).header("Authorization","Bearer "+token)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/payments").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
 }
 BigDecimal reserveBalance(String column){return db.queryForObject("SELECT "+column+" FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class);}
}
