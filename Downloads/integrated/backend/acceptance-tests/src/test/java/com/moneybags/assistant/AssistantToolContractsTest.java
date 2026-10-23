package com.moneybags.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.integration.*;
import com.moneybags.cif.service.CifService;
import com.moneybags.txn.core.LedgerService;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.statements.StatementPort;
import com.moneybags.statements.ApiModels.*;
import com.moneybags.common.api.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantToolContractsTest {
 void actor(String type,List<String> roles,List<String> permissions){
  var principal=new UserPrincipal("qa","qa",type,"qa-session",1L,roles,permissions,List.of("qa-cif"));
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of()));
 }
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 AssistantTools tools(){return new AssistantTools(mock(BankingController.class),mock(BankingOperationsController.class),mock(BankingAccess.class),mock(CustomerHashService.class),mock(LedgerService.class),mock(CifService.class),mock(AssistantIntentService.class),mock(AssistantCustomerTools.class),mock(AssistantProductTools.class));}
 @Test void everyToolRequiresItsDeclaredPermissionAndAudience(){
  var tools=tools();var permissions=List.of("ACCOUNT_READ","TXN_READ","PAYMENT_CREATE","TXN_POST","PAYMENT_READ","STATEMENT_READ","CIF_READ","BENEFICIARY_VERIFY","PRODUCT_READ","LOAN_READ","LOAN_APPLY","LOAN_ACCEPT","LOAN_SERVICE","CC_READ","CC_APPLY","CC_MANAGE","CC_REPAY","CC_SPEND");
  for(String type:List.of("CUSTOMER","EMPLOYEE")){
   actor(type,List.of(),permissions);var available=tools.available();assertFalse(available.isEmpty());
   for(var def:available){
    actor(type,List.of(),permissions.stream().filter(p->!p.equals(def.permission())).toList());
    assertTrue(tools.available().stream().noneMatch(candidate->candidate.name().equals(def.name())),def.name());
   }
  }
  actor("CUSTOMER",List.of(),List.of());assertTrue(tools.available().isEmpty());
  actor("SYSTEM",List.of(),permissions);assertTrue(tools.available().isEmpty());
  actor("EMPLOYEE",List.of("BANK_CHECKER"),permissions);assertTrue(tools.available().stream().noneMatch(def->def.name().startsWith("draft_")));
 }
 @Test void strictSchemasAdvertiseActualBoundsAndEnums(){
  actor("CUSTOMER",List.of(),List.of("TXN_READ","PAYMENT_CREATE","STATEMENT_READ"));
  var json=new ObjectMapper();var definitions=tools().available();
  var payment=json.valueToTree(definitions.stream().filter(d->d.name().equals("draft_payment")).findFirst().orElseThrow().model());
  assertTrue(payment.path("strict").asBoolean());assertEquals(4,payment.path("parameters").path("properties").path("rail").path("enum").size());
  var transactions=json.valueToTree(definitions.stream().filter(d->d.name().equals("list_recent_transactions")).findFirst().orElseThrow().model());
  assertEquals(100,transactions.path("parameters").path("properties").path("limit").path("maximum").asInt());
 }
 @Test void statementHeadersAndPreviewsBoundTheDataSentToTheModel(){
  actor("CUSTOMER",List.of(),List.of("STATEMENT_READ"));
  var port=mock(StatementPort.class);var access=mock(BankingAccess.class);
  var service=new AssistantCustomerTools(mock(BusinessRepository.class),access,port,"");
  var lines=new ArrayList<LineView>();for(int i=0;i<80;i++)lines.add(new LineView(i+1,1L,1L,OffsetDateTime.now(),LocalDate.now(),"NORMAL",BigDecimal.ONE,BigDecimal.ONE,"Synthetic narration"));
  var view=new StatementView("statement","number",1L,"ISSUED",1,LocalDate.now(),LocalDate.now(),BigDecimal.ZERO,BigDecimal.ONE,"INR","en-IN",lines);
  when(port.statements(any(),eq(1L),eq(100))).thenReturn(List.of(view));when(port.statement(any(),eq("statement"))).thenReturn(view);
  var headers=service.statements(1,100);assertFalse(headers.get(0).containsKey("lines"));
  var preview=service.statement("statement");assertEquals(80,preview.get("totalLines"));assertEquals(true,preview.get("linesTruncated"));assertEquals(50,((List<?>)preview.get("lines")).size());
 }
 @Test void disabledExternalPaymentsFailBeforeCreatingAPendingProposal(){
  actor("CUSTOMER",List.of(),List.of("PAYMENT_CREATE"));var db=mock(BusinessRepository.class);
  var intents=new AssistantIntentService(db,mock(BankingAccess.class),mock(PaymentInitiationPort.class),mock(BankingService.class),mock(BankingOperationsController.class),mock(CustomerHashService.class),new ObjectMapper(),Clock.systemUTC(),false,"");
  var error=assertThrows(BusinessException.class,()->intents.draftPayment(1,"recipient","NEFT",BigDecimal.ONE));
  assertEquals("SIMULATION_DISABLED",error.code());verifyNoInteractions(db);
 }
 @Test void ledgerDenialsKeepTheirUsefulDomainErrorCode(){
  actor("CUSTOMER",List.of(),List.of("TXN_READ"));var ledger=mock(LedgerService.class);
  when(ledger.position(1)).thenThrow(new com.moneybags.txn.api.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"POSITION_NOT_FOUND","Account position is unavailable"));
  var tools=new AssistantTools(mock(BankingController.class),mock(BankingOperationsController.class),mock(BankingAccess.class),mock(CustomerHashService.class),ledger,mock(CifService.class),mock(AssistantIntentService.class),mock(AssistantCustomerTools.class),mock(AssistantProductTools.class));
  var args=new ObjectMapper().valueToTree(Map.of("accountId",1));var error=assertThrows(BusinessException.class,()->tools.invoke("get_account_position",args,null));
  assertEquals("POSITION_NOT_FOUND",error.code());
 }
}
