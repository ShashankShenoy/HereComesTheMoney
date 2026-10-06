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
import java.util.*;
import java.math.BigDecimal;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegratedContextTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
 static final String HASH=("customer-demo-hash-"+"x".repeat(43)).substring(0,43);
 String login(String user)throws Exception{
  var r=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",user,"password","LocalBanking!2026","clientId","MONEYBAGS_WEB")))).andExpect(status().isOk()).andReturn();
  return json.readTree(r.getResponse().getContentAsString()).path("data").path("accessToken").asText();
 }
 ResultActions send(String path,String token,Object body)throws Exception{return mvc.perform(post("/api/v1"+path).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));}
 JsonNode value(ResultActions result)throws Exception{return json.readTree(result.andReturn().getResponse().getContentAsString());}
 BigDecimal balance(long id){return db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=?",BigDecimal.class,id);}
 Map<String,Object> transfer(long from,long to,String amount,String key){return Map.of("sourceAccountId",from,"targetAccountId",to,"amount",amount,"channelCode","BRANCH","requestKey",key,"correlationId","test-"+key,"valueDate",java.time.LocalDate.now().toString());}
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
 @Test @Order(4) void newAccountsActivateThroughRealLedgerFence()throws Exception{
  String admin=login("admin");String request=UUID.randomUUID().toString();
  long id=value(send("/accounts",admin,Map.of("requestId",request,"primaryCifId","demo-cif-1","productId",1,"productVersionId",1,"branchCode","MUM001","operationMode","SELF_OPERATED","additionalParties",List.of())).andExpect(status().isCreated())).path("id").asLong();
  send("/accounts/"+id+"/activate",admin,Map.of("requestId",UUID.randomUUID().toString())).andExpect(status().isAccepted());
  assertEquals("ACTIVE",db.queryForObject("SELECT LIFECYCLE_STATUS FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?",String.class,id));
  assertEquals("OPEN",db.queryForObject("SELECT DEBIT_STATUS FROM M05_POSTING_FENCE WHERE BANK_ACCOUNT_ID=?",String.class,id));
 }
 @Test @Order(5) void tellerPostingIsBalancedAndIdempotent()throws Exception{
  String admin=login("admin"),checker=login("checker");
  String till=value(send("/teller/tills",admin,Map.of("branchCode","MUM001","cashGlId",2)).andExpect(status().isOk())).path("tillId").asText();
  BigDecimal before=balance(1);
  var command=Map.of("requestKey",UUID.randomUUID().toString(),"tillId",till,"accountId",1,"direction","DEPOSIT","amount","100.00","reason","Test deposit");
  send("/teller/cash",admin,command).andExpect(status().isOk());
  send("/teller/cash",admin,command).andExpect(status().isOk());
  assertEquals(0,before.add(new BigDecimal("100")).compareTo(balance(1)));
  send("/teller/tills/"+till+"/close",admin,Map.of("countedCash","100","reason","Close till")).andExpect(status().isConflict());
  send("/teller/tills/"+till+"/close",checker,Map.of("countedCash","100","reason","Independent close")).andExpect(status().isOk());
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
  String beneficiary=value(send("/beneficiaries",customer,Map.of("displayName","Test recipient","accountToken","test-recipient-token","bankCode","TEST0123456")).andExpect(status().isOk())).path("beneficiaryId").asText();
  send("/beneficiaries/"+beneficiary+"/verify",checker,Map.of()).andExpect(status().isOk());
  BigDecimal before=balance(1);
  long payment=value(send("/payments/initiate",customer,Map.of("sourceAccountId",1,"beneficiaryId",beneficiary,"railCode","UPI","amount","500","requestKey",UUID.randomUUID().toString())).andExpect(status().isOk())).path("paymentId").asLong();
  send("/payments/"+payment+"/authorize-simulation",checker,Map.of("reserveAccountId",1)).andExpect(status().isOk());
  assertEquals(0,before.subtract(new BigDecimal("500")).compareTo(balance(1)));
  send("/payments/"+payment+"/simulate-outcome",checker,Map.of("outcome","ACCEPTED")).andExpect(status().isOk());
  send("/payments/"+payment+"/simulate-outcome",checker,Map.of("outcome","SETTLED")).andExpect(status().isOk());
  send("/payments/"+payment+"/simulate-outcome",checker,Map.of("outcome","SETTLED")).andExpect(status().isOk());
  assertEquals("SETTLED",db.queryForObject("SELECT STATUS FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",String.class,payment));
  assertEquals(0,new BigDecimal("999500").compareTo(db.queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=1",BigDecimal.class)));
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
  send("/loans/offers/"+offer+"/accept",customer,Map.of("disbursementAccountId",1,"repaymentAccountId",1)).andExpect(status().isOk());
  long doc=value(send("/loans/applications/"+id+"/documents",admin,Map.of("type","SIGNED_OFFER","storageReference","demo://signed-offer","sha256Hex","c".repeat(64))).andExpect(status().isOk())).path("data").path("documentRefId").asLong();
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

 @Test @Order(15) void assistantMcpEnforcesScopeAndExactConfirmation()throws Exception{
  String customer=login("customer"),other=login("customer2"),checker=login("checker");
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
  JsonNode checkerList=mcp(checker,"tools/list",null,Map.of()).path("result").path("tools");
  assertTrue(checkerList.toString().contains("search_customers"));
  assertFalse(checkerList.toString().contains("open_add_beneficiary_form"));
  assertFalse(checkerList.toString().contains("draft_payment"));
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
  String verifyId=toolValue(mcp(checker,"tools/call","draft_beneficiary_verification",Map.of("beneficiaryId",beneficiary))).path("intentId").asText();
  send("/assistant/intents/"+verifyId+"/confirm",customer,Map.of()).andExpect(status().isForbidden());
  send("/assistant/intents/"+verifyId+"/confirm",checker,Map.of()).andExpect(status().isOk())
    .andExpect(jsonPath("$.action").value("BENEFICIARY_VERIFY"));
  send("/assistant/intents/"+verifyId+"/confirm",checker,Map.of()).andExpect(status().isOk())
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

}
