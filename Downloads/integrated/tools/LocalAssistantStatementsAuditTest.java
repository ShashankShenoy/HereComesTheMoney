package com.moneybags;

import com.fasterxml.jackson.databind.*;
import com.moneybags.assistant.OpenAiResponsesClient;
import com.moneybags.common.api.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.nio.file.*;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated H2 checks. Model is a Mockito stub: no provider, Oracle or live app calls. */
@SpringBootTest(classes=MoneybagsApplication.class,properties={
 "spring.datasource.url=jdbc:h2:mem:assistantqa;MODE=Oracle;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH",
 "moneybags.deposit-interest.enabled=false","moneybags.payment-simulation.clearing-enabled=false",
 "moneybags.cif.key-file=.runtime/local-assistant-audit/cif.key",
 "moneybags.auth.mfa-key-file=.runtime/local-assistant-audit/mfa.key",
 "moneybags.documents.root=.runtime/local-assistant-audit/documents"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("local") @Transactional
class LocalAssistantStatementsAuditTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
 @MockitoBean OpenAiResponsesClient model;
 @BeforeEach void modelSetup(){when(model.configured()).thenReturn(true);}
 String login(String username)throws Exception{
  var r=postJson("/auth/login",null,Map.of("username",username,"password","LocalBanking!2026","clientId","LOCAL_QA"));
  assertEquals(200,r.getResponse().getStatus());return tree(r).path("data").path("accessToken").asText();
 }
 MvcResult postJson(String path,String token,Object body)throws Exception{
  var req=post("/api/v1"+path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)).header("Idempotency-Key",UUID.randomUUID().toString());
  if(token!=null)req.header("Authorization","Bearer "+token);
  return mvc.perform(req).andReturn();
 }
 JsonNode tree(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString());}
 JsonNode textReply(String text){return json.valueToTree(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",text))))));}
 JsonNode toolReply(String name,Object args){return json.valueToTree(Map.of("status","completed","output",List.of(Map.of("type","function_call","name",name,"call_id",UUID.randomUUID().toString(),"arguments",json.valueToTree(args).toString()))));}
 @Test void customerChatReturnsAnswerAndReusesOnlyOwnConversation()throws Exception{
  var inputs=new ArrayList<String>();when(model.create(anyMap())).thenAnswer(inv->{inputs.add(json.writeValueAsString(((Map<?,?>)inv.getArgument(0)).get("input")));return textReply("Synthetic local reply");});
  String customer=login("customer"),other=login("customer2");
  var first=postJson("/assistant/chat",customer,Map.of("message","Unique local customer history"));assertEquals(200,first.getResponse().getStatus());
  var reply=tree(first);String id=reply.path("conversationId").asText();assertFalse(id.isBlank());assertEquals("Synthetic local reply",reply.path("answer").asText());assertTrue(reply.path("pendingActions").isEmpty());
  assertEquals(200,postJson("/assistant/chat",customer,Map.of("message","Follow up","conversationId",id)).getResponse().getStatus());
  assertTrue(inputs.get(1).contains("Unique local customer history"));
  assertEquals(200,postJson("/assistant/chat",other,Map.of("message","Other customer","conversationId",id)).getResponse().getStatus());
  assertFalse(inputs.get(2).contains("Unique local customer history"));
 }
 @Test void modelToolLoopReturnsOwnAccountsWithoutPendingActions()throws Exception{
  when(model.create(anyMap())).thenReturn(toolReply("list_my_accounts",Map.of()),textReply("Your synthetic account is available."));
  var r=postJson("/assistant/chat",login("customer"),Map.of("message","List my accounts"));assertEquals(200,r.getResponse().getStatus());assertTrue(tree(r).path("pendingActions").isEmpty());verify(model,times(2)).create(anyMap());
 }
 @Test void forbiddenToolReadIsReturnedAsAnErrorToModel()throws Exception{
  var inputs=new ArrayList<String>();var count=new int[]{0};when(model.create(anyMap())).thenAnswer(inv->{inputs.add(json.writeValueAsString(((Map<?,?>)inv.getArgument(0)).get("input")));return count[0]++==0?toolReply("get_account_position",Map.of("accountId",2)):textReply("Access denied");});
  var r=postJson("/assistant/chat",login("customer"),Map.of("message","Read account 2"));assertEquals(200,r.getResponse().getStatus());assertTrue(inputs.get(1).contains("error"));assertFalse(inputs.get(1).contains("postedBalance"));
 }
 @Test void invalidInputsAndProviderFailureHaveUsefulErrors()throws Exception{
  String customer=login("customer");
  for(Object body:List.of(Map.of("message",""),Map.of("message","x".repeat(2001)),Map.of("message","Hello","conversationId","invalid")))assertEquals(400,postJson("/assistant/chat",customer,body).getResponse().getStatus());
  when(model.create(anyMap())).thenThrow(new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"MODEL_RATE_LIMITED","Try later"));
  var r=postJson("/assistant/chat",customer,Map.of("message","Hello"));assertEquals(429,r.getResponse().getStatus());assertEquals("MODEL_RATE_LIMITED",tree(r).path("code").asText());
 }
 @Test void repeatedModelToolCallsAreBounded()throws Exception{
  when(model.create(anyMap())).thenReturn(toolReply("list_my_accounts",Map.of()));
  var r=postJson("/assistant/chat",login("customer"),Map.of("message","List accounts"));assertEquals(200,r.getResponse().getStatus());verify(model,times(6)).create(anyMap());assertFalse(tree(r).path("answer").asText().isBlank());
 }
 @Test void checkerCanUseReadOnlyChat()throws Exception{
  when(model.create(anyMap())).thenReturn(textReply("Synthetic checker reply"));
  var r=postJson("/assistant/chat",login("checker"),Map.of("message","Hello"));
  assertEquals(200,r.getResponse().getStatus(),"Checker read-only chat: "+r.getResponse().getContentAsString());
 }
 @Test void checkerCanDiscoverReadOnlyMcpTools()throws Exception{
  var r=mvc.perform(post("/api/v1/assistant/mcp").header("Authorization","Bearer "+login("checker")).header("MCP-Protocol-Version","2026-07-28").header("Mcp-Method","tools/list").contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}")).andReturn();
  assertEquals(200,r.getResponse().getStatus(),"Checker tool discovery: "+r.getResponse().getContentAsString());
  String catalogue=r.getResponse().getContentAsString();assertFalse(catalogue.contains("draft_beneficiary_verification"));assertFalse(catalogue.contains("draft_payment"));
  var denied=postJson("/assistant/intents/"+UUID.randomUUID()+"/confirm",login("checker"),Map.of());assertEquals(403,denied.getResponse().getStatus());
 }
 @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
 void staffStatementsWorkAfterIndependentPolicyApproval()throws Exception{
  String admin=login("admin"),checker=login("checker"),now=java.time.OffsetDateTime.now().minusMinutes(1).toString();
  db.update("UPDATE M10_MASKING_PROFILE SET STATUS='RETIRED' WHERE AUDIENCE_CODE='STAFF'");
  db.update("UPDATE M10_NARRATION_CATALOG SET APPROVAL_STATUS='RETIRED' WHERE AUDIENCE_CODE='STAFF'");
  var purpose=postJson("/privacy/purposes",admin,Map.of("code","LOCAL_STAFF_STATEMENTS","version",1,"name","Account holder requested statements","ownerService","M10_STATEMENTS","legalBasisCode","CONTRACT","effectiveFrom",now));
  assertEquals(201,purpose.getResponse().getStatus(),purpose.getResponse().getContentAsString());var p=tree(purpose);
  mvc.perform(post("/api/v1/privacy/purposes/"+p.path("id").asText()+"/approval").header("Authorization","Bearer "+admin).header("If-Match",p.path("versionNo").asText())).andExpect(status().isConflict());
  mvc.perform(post("/api/v1/privacy/purposes/"+p.path("id").asText()+"/approval").header("Authorization","Bearer "+checker).header("If-Match",p.path("versionNo").asText())).andExpect(status().isOk());
  var mask=postJson("/catalog/masking-profiles",admin,Map.of("code","LOCAL_STAFF_WEB","version",1,"channel","WEB","audience","STAFF","rulesJson","{\"showBalances\":true,\"showSourceReferences\":false}"));assertEquals(200,mask.getResponse().getStatus());String maskId=tree(mask).path("id").asText();
  assertEquals(409,postJson("/catalog/masking-profiles/"+maskId+"/approve",admin,Map.of()).getResponse().getStatus());assertEquals(204,postJson("/catalog/masking-profiles/"+maskId+"/approve",checker,Map.of()).getResponse().getStatus());
  for(String code:List.of("NORMAL","REVERSAL","RETURN","ADJUSTMENT")){
   var n=postJson("/catalog/narrations",admin,Map.of("code",code,"version",2,"locale","en-IN","audience","STAFF","text","Account transaction","effectiveFrom",now));assertEquals(200,n.getResponse().getStatus());assertEquals(204,postJson("/catalog/narrations/"+tree(n).path("id").asText()+"/approve",checker,Map.of()).getResponse().getStatus());
  }
  String day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();String proof=("customer-demo-hash-"+"x".repeat(43)).substring(0,43);
  var request=mvc.perform(post("/api/v1/reporting/statement-requests").header("Authorization","Bearer "+admin).header("X-Customer-Hash",proof).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("accountId",1,"statementType","STAFF_ASSISTED","periodStart",day,"periodEnd",day,"format","PDF","locale","en-IN","channel","WEB","purposeCode","LOCAL_STAFF_STATEMENTS","caseReference","LOCAL-QA")))).andExpect(status().isCreated()).andReturn();
  var issued=mvc.perform(post("/api/v1/reporting/statement-requests/"+tree(request).path("requestId").asText()+"/process").header("Authorization","Bearer "+admin).header("X-Customer-Hash",proof)).andExpect(status().isOk()).andReturn();assertEquals("COMPLETED",tree(issued).path("status").asText());
  for(String format:List.of("PDF","CSV","HTML"))mvc.perform(get("/api/v1/reporting/statements/"+tree(issued).path("statementId").asText()+"/download?format="+format).header("Authorization","Bearer "+admin).header("X-Customer-Hash",proof)).andExpect(status().isOk());
 }
 @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
 void localStatementBalancesDownloadsAndOwnership()throws Exception{
  String customer=login("customer"),other=login("customer2"),day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
  var body=Map.of("accountId",1,"statementType","AD_HOC","periodStart",day,"periodEnd",day,"locale","en-IN","format","PDF","channel","WEB");
  var request=postJson("/reporting/statement-requests",customer,body);assertEquals(201,request.getResponse().getStatus());String requestId=tree(request).path("requestId").asText();
  // Use separate committed H2 requests, as the audit writer rechecks referenced request rows.
  // Include synthetic movements to exercise debit signs and running balances.
  assertEquals(200,postJson("/auth/payment-pin/setup",customer,Map.of("pin","134679")).getResponse().getStatus());
  for(int i=0;i<10;i++){
   String key=UUID.randomUUID().toString();
   var movement=mvc.perform(post("/api/v1/transactions/transfers").header("Authorization","Bearer "+customer).header("X-Payment-Pin","134679").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("sourceAccountId",1,"targetAccountId",2,"amount","1.00","channelCode","BRANCH","requestKey",key,"correlationId",key,"valueDate",day)))).andReturn();
   assertEquals(200,movement.getResponse().getStatus(),movement.getResponse().getContentAsString());
  }
  var issued=postJson("/reporting/statement-requests/"+requestId+"/process",customer,Map.of());assertEquals(200,issued.getResponse().getStatus(),issued.getResponse().getContentAsString()+" cause="+issued.getResolvedException());assertEquals("COMPLETED",tree(issued).path("status").asText());String id=tree(issued).path("statementId").asText();
  var data=mvc.perform(get("/api/v1/reporting/statements/"+id).header("Authorization","Bearer "+customer)).andExpect(status().isOk()).andReturn();JsonNode s=tree(data);BigDecimal balance=s.path("openingBalance").decimalValue();
  for(JsonNode line:s.path("lines")){balance=balance.add(line.path("amount").decimalValue());assertEquals(0,balance.compareTo(line.path("balanceAfter").decimalValue()));}assertEquals(0,balance.compareTo(s.path("closingBalance").decimalValue()));
  Path out=Path.of(".runtime/local-assistant-audit");Files.createDirectories(out);Files.writeString(out.resolve("statement.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(s));
  for(String format:List.of("PDF","CSV","HTML")){
   var download=mvc.perform(get("/api/v1/reporting/statements/"+id+"/download?format="+format).header("Authorization","Bearer "+customer)).andExpect(status().isOk()).andReturn();assertTrue(download.getResponse().getContentAsByteArray().length>100);Files.write(out.resolve("statement."+format.toLowerCase()),download.getResponse().getContentAsByteArray());
   mvc.perform(get("/api/v1/reporting/statements/"+id+"/download?format="+format).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
  }
  // Exercise document pagination without exceeding the product's real transaction velocity limit.
  var document=s.deepCopy();var rows=json.createArrayNode();for(int i=0;i<80;i++){var row=s.path("lines").get(0).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)row).put("lineNo",i+1);rows.add(row);}
  ((com.fasterxml.jackson.databind.node.ObjectNode)document).set("lines",rows);
  Files.write(out.resolve("pagination.pdf"),com.moneybags.statements.StatementDocument.render(json.treeToValue(document,com.moneybags.statements.ApiModels.StatementView.class),"PDF").bytes());
 }
 record Loan(String admin,String checker,String customer,long app,long facility,long disbursement){}
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({"0.01,12","12000,1"})
 void sanctionedTermsStayWithinProductMinimums(String amount,int tenure)throws Exception{
  db.update("UPDATE M03_PM_LOAN_RULE SET MIN_TENURE_MONTHS=12 WHERE PRODUCT_VERSION_ID=2");
  String admin=login("admin"),checker=login("checker");
  long app=expectId(postJson("/loans/applications",admin,Map.of("cifId","demo-cif-1","productId",2,"productVersionId",2,"branchCode","MUM001","channelCode","BRANCH","amount","12000","tenureMonths",12,"purposeCode","PERSONAL")),"id");
  assertEquals(200,postJson("/loans/applications/"+app+"/submit",admin,Map.of()).getResponse().getStatus());
  assertEquals(200,postJson("/loans/applications/"+app+"/assessments",admin,Map.of("monthlyIncome","50000","monthlyObligations","5000","recommendation","APPROVE","reasonCodes","AFFORDABLE")).getResponse().getStatus());
  var decision=postJson("/loans/applications/"+app+"/decisions",checker,Map.of("code","APPROVE","reasonCodes","APPROVED","sanctionedAmount",amount,"sanctionedTenureMonths",tenure,"annualRatePct","12","authorityCode","LOAN_SANCTION","authorityEvidenceRef","LOCAL-QA"));
  assertTrue(Set.of(400,409,422).contains(decision.getResponse().getStatus()),"Sanction below product minimum accepted: "+decision.getResponse().getContentAsString());
 }
 long expectId(MvcResult result,String field)throws Exception{assertEquals(200,result.getResponse().getStatus(),result.getResponse().getContentAsString());JsonNode n=tree(result);if(n.has("data"))n=n.path("data");long id=n.path(field).asLong();assertTrue(id>0,"Missing "+field+": "+n);return id;}
 Loan loan()throws Exception{
  String admin=login("admin"),checker=login("checker"),customer=login("customer");
  long app=expectId(postJson("/loans/applications",admin,Map.of("cifId","demo-cif-1","productId",2,"productVersionId",2,"branchCode","MUM001","channelCode","BRANCH","amount","12000","tenureMonths",1,"purposeCode","PERSONAL")),"id");
  assertEquals(200,postJson("/loans/applications/"+app+"/submit",admin,Map.of()).getResponse().getStatus());
  assertEquals(200,postJson("/loans/applications/"+app+"/assessments",admin,Map.of("monthlyIncome","50000","monthlyObligations","5000","recommendation","APPROVE","reasonCodes","AFFORDABLE")).getResponse().getStatus());
  var decision=Map.of("code","APPROVE","reasonCodes","APPROVED","sanctionedAmount","12000","sanctionedTenureMonths",1,"annualRatePct","12","authorityCode","LOAN_SANCTION","authorityEvidenceRef","LOCAL-QA");
  var self=postJson("/loans/applications/"+app+"/decisions",admin,decision);assertTrue(Set.of(403,409).contains(self.getResponse().getStatus()));
  assertEquals(200,postJson("/loans/applications/"+app+"/decisions",checker,decision).getResponse().getStatus());
  long offer=expectId(postJson("/loans/applications/"+app+"/offers",admin,Map.of("documentRef","test://local-offer","expiresAt",java.time.OffsetDateTime.now().plusDays(7).toString())),"offerId");
  assertEquals(200,postJson("/loans/offers/"+offer+"/accept",customer,Map.of("disbursementAccountId",1,"repaymentAccountId",1)).getResponse().getStatus());
  assertEquals(409,postJson("/loans/applications/"+app+"/convert",admin,Map.of()).getResponse().getStatus());
  long doc=expectId(postJson("/loans/applications/"+app+"/documents",admin,Map.of("type","SIGNED_OFFER","storageReference","test://local-signed","sha256Hex","f".repeat(64))),"documentRefId");
  assertEquals(200,postJson("/loans/applications/"+app+"/documents/"+doc+"/verify",checker,Map.of()).getResponse().getStatus());
  long facility=expectId(postJson("/loans/applications/"+app+"/convert",admin,Map.of()),"facilityId");
  long disbursement=expectId(postJson("/loans/facilities/"+facility+"/disbursements",admin,Map.of("requestKey",UUID.randomUUID().toString())),"disbursementId");
  return new Loan(admin,checker,customer,app,facility,disbursement);
 }
 @Test void loanLifecycleRepaysDueAmountClosesAndCannotDebitTwice()throws Exception{
  Loan l=loan();String root="/loans/facilities/"+l.facility();
  BigDecimal before=db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=1",BigDecimal.class);
  assertEquals(200,postJson("/loans/disbursements/"+l.disbursement()+"/approve",l.checker(),Map.of("authorityCode","LOAN_SANCTION")).getResponse().getStatus());
  assertEquals(200,postJson("/loans/disbursements/"+l.disbursement()+"/approve",l.checker(),Map.of("authorityCode","LOAN_SANCTION")).getResponse().getStatus());
  assertEquals(0,before.add(new BigDecimal("12000")).compareTo(db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=1",BigDecimal.class)));
  assertEquals(409,postJson(root+"/repayments",l.admin(),Map.of("requestKey",UUID.randomUUID().toString(),"amount","1")).getResponse().getStatus());
  db.update("UPDATE M08_LOAN_SCHEDULE_ITEM SET DUE_DATE=? WHERE FACILITY_ID=?",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")),l.facility());
  assertEquals(200,postJson(root+"/accruals",l.admin(),Map.of()).getResponse().getStatus());int accruals=db.queryForObject("SELECT COUNT(*) FROM M08_LOAN_ACCRUAL WHERE FACILITY_ID=?",Integer.class,l.facility());
  assertEquals(200,postJson(root+"/accruals",l.admin(),Map.of()).getResponse().getStatus());assertEquals(accruals,db.queryForObject("SELECT COUNT(*) FROM M08_LOAN_ACCRUAL WHERE FACILITY_ID=?",Integer.class,l.facility()));
  BigDecimal due=db.queryForObject("SELECT SUM(PRINCIPAL_DUE+INTEREST_DUE) FROM M08_LOAN_SCHEDULE_ITEM WHERE FACILITY_ID=?",BigDecimal.class,l.facility());
  var repayment=Map.of("requestKey",UUID.randomUUID().toString(),"amount",due.toPlainString());
  var paid=postJson(root+"/repayments",l.admin(),repayment);assertEquals(200,paid.getResponse().getStatus(),paid.getResponse().getContentAsString());
  BigDecimal after=db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=1",BigDecimal.class);
  var replay=postJson(root+"/repayments",l.admin(),repayment);assertEquals(200,replay.getResponse().getStatus(),replay.getResponse().getContentAsString());
  assertEquals(0,after.compareTo(db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=1",BigDecimal.class)));
  assertEquals("CLOSED",db.queryForObject("SELECT STATUS FROM M08_LOAN_FACILITY WHERE FACILITY_ID=?",String.class,l.facility()));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M08_LOAN_PAYMENT WHERE FACILITY_ID=?",Integer.class,l.facility()));
 }
}
