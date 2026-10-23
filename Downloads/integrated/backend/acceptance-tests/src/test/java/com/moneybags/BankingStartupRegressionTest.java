package com.moneybags;

import com.moneybags.common.database.BusinessRepository;
import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.integration.*;
import com.moneybags.txn.api.ApiException;
import com.moneybags.txn.core.LedgerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankingStartupRegressionTest {
 @AfterEach void clearActor(){SecurityContextHolder.clearContext();}
 private void actor(String type,String... permissions){
  var user=new UserPrincipal("teller-1","tester",type,"session",1L,List.of(),List.of(permissions),List.of());
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
 }
 private BankingController dashboard(LedgerService ledger,String status){
  var controller=spy(new BankingController(mock(BusinessRepository.class),mock(BankingAccess.class),mock(CustomerHashService.class),ledger));
  doReturn(List.of(Map.of("ACCOUNT_ID",1L,"ACCOUNT_NUMBER","NEW0001","ACCOUNT_STATUS",status,"CURRENCY_CODE","INR"))).when(controller).accounts();
  doReturn(List.of()).when(controller).transactions(1L,null,null);
  return controller;
 }
 @Test void pendingAccountShowsZeroBeforeLedgerInitialization(){
  actor("CUSTOMER","TXN_READ");var ledger=mock(LedgerService.class);
  when(ledger.position(1)).thenThrow(new ApiException(HttpStatus.NOT_FOUND,"POSITION_NOT_FOUND","Not initialized"));
  var result=dashboard(ledger,"PENDING_OPEN").myDashboard();
  var account=(Map<?,?>)((List<?>)result.get("accounts")).get(0);
  assertEquals(BigDecimal.ZERO,account.get("posted"));assertEquals(BigDecimal.ZERO,account.get("spendable"));
 }
 @Test void activeAccountDoesNotHideAMissingLedgerPosition(){
  actor("CUSTOMER","TXN_READ");var ledger=mock(LedgerService.class);
  when(ledger.position(1)).thenThrow(new ApiException(HttpStatus.NOT_FOUND,"POSITION_NOT_FOUND","Not initialized"));
  assertThrows(ApiException.class,()->dashboard(ledger,"ACTIVE").myDashboard());
 }
 @Test void pendingAccountWithFundingShowsItsActualLedgerBalance(){
  actor("CUSTOMER","TXN_READ");var ledger=mock(LedgerService.class);
  var funded=new BigDecimal("500.00");
  when(ledger.position(1)).thenReturn(new com.moneybags.txn.api.Contracts.PositionView(1,funded,BigDecimal.ZERO,
   BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,funded,1,1));
  var result=dashboard(ledger,"PENDING_OPEN").myDashboard();
  var account=(Map<?,?>)((List<?>)result.get("accounts")).get(0);
  assertEquals(funded,account.get("posted"));assertEquals(funded,account.get("spendable"));
 }
 @Test void pendingAccountDoesNotHideOtherLedgerFailures(){
  actor("CUSTOMER","TXN_READ");var ledger=mock(LedgerService.class);
  when(ledger.position(1)).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","Denied"));
  assertThrows(ApiException.class,()->dashboard(ledger,"PENDING_OPEN").myDashboard());
 }
 private Map<String,Object> till(String id,String owner,String status,LocalDate date){
  return Map.of("TILL_ID",id,"TELLER_USER_ID",owner,"STATUS",status,"BUSINESS_DATE",date);
 }
 @Test void cashEligibilityUsesIndianMidnightAndTillOwnership(){
  actor("EMPLOYEE","TELLER_READ","TELLER_OPERATE");var db=mock(BusinessRepository.class);
  LocalDate today=LocalDate.of(2026,10,23);
  when(db.rows("SELECT * FROM MBX_TELLER_TILL ORDER BY BUSINESS_DATE DESC FETCH FIRST 100 ROWS ONLY"))
   .thenReturn(List.of(till("today","teller-1","OPEN",today),till("yesterday","teller-1","OPEN",today.minusDays(1)),
    till("other","teller-2","OPEN",today),till("closed","teller-1","CLOSED",today)));
  // Still October 22 in UTC; India has crossed midnight.
  var clock=Clock.fixed(Instant.parse("2026-10-22T18:31:00Z"),ZoneOffset.UTC);
  var controller=new TellerController(db,mock(BankingAccess.class),mock(LedgerService.class),null,mock(CustomerHashService.class),clock);
  assertEquals(List.of(true,false,false,false),controller.tills().stream().map(row->row.get("CAN_POST_CASH")).toList());
 }
}
