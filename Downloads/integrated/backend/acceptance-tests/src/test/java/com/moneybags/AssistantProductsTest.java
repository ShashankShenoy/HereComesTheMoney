package com.moneybags;

import com.fasterxml.jackson.databind.*;
import com.moneybags.assistant.OpenAiResponsesClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(classes=MoneybagsApplication.class,properties={
 "spring.datasource.url=jdbc:h2:mem:assistantproducts;MODE=Oracle;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH",
 "moneybags.deposit-interest.enabled=true","moneybags.payment-simulation.clearing-enabled=false",
 "moneybags.cif.key-file=target/assistant-products/cif.key","moneybags.auth.mfa-key-file=target/assistant-products/mfa.key",
 "moneybags.documents.root=target/assistant-products/documents"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("local") @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssistantProductsTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
 @MockitoBean OpenAiResponsesClient model;
 static final Set<String> PRODUCT_TOOLS=Set.of("list_my_product_offers","list_my_loan_applications","get_loan_application","list_loan_offers","list_loan_facilities","get_loan_details","get_loan_schedule","get_loan_prepayment_quote","list_credit_card_products","list_credit_card_applications","list_credit_cards","get_credit_card","list_credit_card_transactions","list_credit_card_statements","list_my_fixed_deposits","get_fixed_deposit","open_loan_application_form","open_loan_offer_acceptance_form","open_loan_repayment_form","open_loan_prepayment_form","open_credit_card_application_form","open_credit_card_control_form","open_credit_card_repayment_form","open_credit_card_purchase_form","open_credit_card_billing_form","open_fixed_deposit_form");
 static Collection<String> tools(){return PRODUCT_TOOLS;}
 String customer,other,admin,checker,card;long application,facility,deposit;
 JsonNode post(String path,String token,Object body)throws Exception{
  var req=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1"+path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)).header("Idempotency-Key",UUID.randomUUID().toString()).header("X-Payment-Pin","134679");
  if(token!=null)req.header("Authorization","Bearer "+token);
  var result=mvc.perform(req).andReturn();assertTrue(Set.of(200,201).contains(result.getResponse().getStatus()),path+": "+result.getResponse().getContentAsString());
  var value=json.readTree(result.getResponse().getContentAsString());return value.has("data")?value.path("data"):value;
 }
 String login(String name)throws Exception{return post("/auth/login",null,Map.of("username",name,"password","LocalBanking!2026","clientId","ASSISTANT_PRODUCTS_QA")).path("accessToken").asText();}
 @BeforeAll void fixtures()throws Exception{
  customer=login("customer");other=login("customer2");admin=login("admin");checker=login("checker");post("/auth/payment-pin/setup",customer,Map.of("pin","134679"));
  application=post("/loans/my/applications",customer,Map.of("cifId","demo-cif-1","productId",2,"productVersionId",2,"amount","12000","tenureMonths",12,"purposeCode","PERSONAL")).path("id").asLong();
  post("/loans/applications/"+application+"/assessments",admin,Map.of("monthlyIncome","50000","monthlyObligations","5000","recommendation","APPROVE","reasonCodes","QA_AFFORDABLE"));
  post("/loans/applications/"+application+"/decisions",checker,Map.of("code","APPROVE","reasonCodes","APPROVED","sanctionedAmount","12000","sanctionedTenureMonths",12,"annualRatePct","12","authorityCode","LOAN_SANCTION","authorityEvidenceRef","LOCAL_QA"));
  long offer=post("/loans/applications/"+application+"/offers",admin,Map.of("documentRef","test://assistant-offer","expiresAt",OffsetDateTime.now().plusDays(7).toString())).path("offerId").asLong();
  post("/loans/offers/"+offer+"/accept",customer,Map.of("disbursementAccountId",1,"repaymentAccountId",1));
  long doc=post("/loans/applications/"+application+"/documents",admin,Map.of("type","SIGNED_OFFER","storageReference","test://assistant-signed","sha256Hex","a".repeat(64))).path("documentRefId").asLong();
  post("/loans/applications/"+application+"/documents/"+doc+"/verify",checker,Map.of());
  facility=post("/loans/applications/"+application+"/convert",admin,Map.of()).path("facilityId").asLong();
  long disbursement=post("/loans/facilities/"+facility+"/disbursements",admin,Map.of("requestKey",UUID.randomUUID().toString())).path("disbursementId").asLong();
  post("/loans/disbursements/"+disbursement+"/approve",checker,Map.of("authorityCode","LOAN_SANCTION"));
  db.update("UPDATE M03_PM_LOAN_RULE SET PREPAYMENT_ALLOWED='Y',PARTIAL_PREPAYMENT_ALLOWED='Y',FORECLOSURE_ALLOWED='Y' WHERE PRODUCT_VERSION_ID=2");
  var app=post("/credit-cards/applications",customer,Map.of("requestKey",UUID.randomUUID().toString(),"productId","demo-credit-classic","cifId","demo-cif-1","repaymentAccountId",1,"requestedLimit","10000","termsAccepted",true));
  var approved=post("/credit-cards/applications/"+app.path("APPLICATION_ID").asText()+"/decision",checker,Map.of("requestKey",UUID.randomUUID().toString(),"rowVersion",app.path("ROW_VERSION").asLong(),"decision","APPROVED","reason","Independent QA approval","approvedLimit","10000"));
  card=approved.path("CARD_ID").asText();
  var activated=post("/credit-cards/"+card+"/controls",customer,Map.of("requestKey",UUID.randomUUID().toString(),"rowVersion",1,"action","ACTIVATE","reason","Customer confirmation"));
  assertEquals("ACTIVE",activated.path("STATUS").asText());
  post("/credit-cards/"+card+"/purchases",customer,Map.of("requestKey",UUID.randomUUID().toString(),"amount","100","merchantName","Synthetic QA merchant"));
  deposit=post("/term-deposits",customer,Map.of("productId",3,"productVersionId",3,"fundingAccountId",1,"amount","1000","tenureMonths",1,"requestKey",UUID.randomUUID().toString())).path("TERM_DEPOSIT_ID").asLong();
  assertTrue(facility>0&&deposit>0);assertFalse(card.isBlank());
 }
 Object arguments(String name){return switch(name){
  case "get_loan_application","list_loan_offers"->Map.of("applicationId",application);
  case "get_loan_details","get_loan_schedule","get_loan_prepayment_quote"->Map.of("facilityId",facility);
  case "get_credit_card","list_credit_card_statements"->Map.of("cardId",card);
  case "list_credit_card_transactions"->Map.of("cardId",card,"limit",100);
  case "get_fixed_deposit"->Map.of("depositId",deposit);default->Map.of();};}
 JsonNode rpc(String token,String name,Object args)throws Exception{
  var req=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/assistant/mcp").header("Authorization","Bearer "+token).header("MCP-Protocol-Version","2026-07-28").header("Mcp-Method","tools/call").header("Mcp-Name",name).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("jsonrpc","2.0","id",1,"method","tools/call","params",Map.of("name",name,"arguments",args))));
  var result=mvc.perform(req).andReturn();assertEquals(200,result.getResponse().getStatus());return json.readTree(result.getResponse().getContentAsString());
 }
 JsonNode tool(String token,String name,Object args)throws Exception{
  var rpc=rpc(token,name,args);assertFalse(rpc.path("result").path("isError").asBoolean(),rpc.toString());return json.readTree(rpc.path("result").path("content").get(0).path("text").asText());
 }
 @ParameterizedTest @MethodSource("tools") void everyProductCapabilityWorksThroughChat(String name)throws Exception{
  when(model.configured()).thenReturn(true);var outputs=new ArrayList<JsonNode>();int[] round={0};
  when(model.create(anyMap())).thenAnswer(inv->{
   if(round[0]++==0)return json.valueToTree(Map.of("output",List.of(Map.of("type","function_call","name",name,"call_id","qa-call","arguments",json.writeValueAsString(arguments(name))))));
   var input=json.valueToTree(((Map<?,?>)inv.getArgument(0)).get("input"));for(var item:input)if("function_call_output".equals(item.path("type").asText()))outputs.add(json.readTree(item.path("output").asText()));
   return json.valueToTree(Map.of("output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text","Synthetic product result"))))));
  });
  BigDecimal before=db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=1",BigDecimal.class);
  String token=Set.of("open_loan_repayment_form","open_loan_prepayment_form").contains(name)?admin:customer;
  var reply=post("/assistant/chat",token,Map.of("message","Exercise "+name));assertEquals(1,outputs.size());var value=outputs.get(0);assertFalse(value.has("error"),name+": "+value);
  assertFalse(value.toString().contains("CARD_REFERENCE"));
  if(name.startsWith("open_")){assertEquals("BANKING_WORKFLOW",value.path("uiAction").asText());assertEquals(1,reply.path("pendingActions").size());}
  else assertTrue(reply.path("pendingActions").isEmpty());
  assertEquals(0,before.compareTo(db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=1",BigDecimal.class)));
  if(name.equals("get_loan_details"))assertEquals(facility,value.path("FACILITY_ID").asLong());
  if(name.equals("get_credit_card"))assertEquals(card,value.path("CARD_ID").asText());
  if(name.equals("get_fixed_deposit"))assertFalse(value.has("REQUEST_KEY"));
  if(name.equals("get_loan_schedule"))assertEquals(12,value.path("totalInstallments").asInt());
 }
 @Test void otherCustomerCannotReadLoanCardOrDepositByIdentifier()throws Exception{
  for(String name:List.of("get_loan_application","list_loan_offers","get_loan_details","get_loan_schedule","get_loan_prepayment_quote","get_credit_card","list_credit_card_transactions","list_credit_card_statements","get_fixed_deposit"))
   assertTrue(rpc(other,name,arguments(name)).path("result").path("isError").asBoolean(),name);
  assertTrue(tool(other,"list_credit_cards",Map.of()).isEmpty());assertTrue(tool(other,"list_my_fixed_deposits",Map.of()).isEmpty());
 }
 @Test void checkerCanReadPermittedProductsButCannotOpenCustomerWriteForms()throws Exception{
  assertEquals(facility,tool(checker,"get_loan_details",Map.of("facilityId",facility)).path("FACILITY_ID").asLong());
  assertEquals(card,tool(checker,"get_credit_card",Map.of("cardId",card)).path("CARD_ID").asText());
  for(String name:PRODUCT_TOOLS.stream().filter(n->n.startsWith("open_")).toList())assertTrue(rpc(checker,name,Map.of()).path("result").path("isError").asBoolean());
  for(String name:List.of("open_loan_repayment_form","open_loan_prepayment_form"))assertTrue(rpc(customer,name,Map.of()).path("result").path("isError").asBoolean());
 }
 @Test void existingCommandsStillApplyOwnershipLimitsAndReplayWithoutDoubleDebit()throws Exception{
  String key=UUID.randomUUID().toString();var body=Map.of("requestKey",key,"amount","10");
  BigDecimal before=db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=1",BigDecimal.class);
  post("/credit-cards/"+card+"/repayments",customer,body);post("/credit-cards/"+card+"/repayments",customer,body);
  assertEquals(0,before.subtract(BigDecimal.TEN).compareTo(db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=1",BigDecimal.class)));
 }
}
