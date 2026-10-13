package com.moneybags;
import com.moneybags.payments.core.*;
import com.moneybags.integration.*;
import com.moneybags.iam.security.UserPrincipal;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in ALPHA verification: financial test data and IAM sessions are rolled back. */
@EnabledIfEnvironmentVariable(named="RUN_ALPHA_PAYMENT_TESTS",matches="true")
@SpringBootTest(properties={"moneybags.payment-simulation.enabled=true","moneybags.payment-simulation.clearing-enabled=true","moneybags.payment-simulation.poll-ms=3600000","moneybags.deposit-interest.enabled=false","moneybags.bootstrap.enabled=false","spring.sql.init.mode=never"})
@Transactional
class OracleSettlementSmokeTest {
 @Autowired JdbcTemplate db;@Autowired SettlementSimulator simulator;@Autowired PaymentWorkflowController workflow;
 long account,reserve;String number,cif,admin,checker,beneficiary;
 @BeforeEach void setup(){
  var a=db.queryForList("SELECT A.ACCOUNT_ID,A.ACCOUNT_NUMBER,A.PRIMARY_CIF_ID FROM M04_BANK_ACCOUNT A JOIN M02_CIF_CUSTOMER C ON C.CIF_ID=A.PRIMARY_CIF_ID WHERE A.ACCOUNT_STATUS='ACTIVE' AND C.KYC_STATUS='VERIFIED' AND EXISTS (SELECT 1 FROM M05_GL_PRODUCT_MAPPING M WHERE M.PRODUCT_VERSION_ID=A.PRODUCT_VERSION_ID AND M.POSTING_TYPE='PAYMENT' AND M.GL_ROLE_CODE='SETTLEMENT_SUSPENSE' AND M.STATUS='ACTIVE') ORDER BY A.ACCOUNT_ID FETCH FIRST 1 ROWS ONLY").get(0);
  account=((Number)a.get("ACCOUNT_ID")).longValue();number=(String)a.get("ACCOUNT_NUMBER");cif=(String)a.get("PRIMARY_CIF_ID");
  admin=user("BANK_ADMIN");checker=user("BANK_CHECKER");as(admin);
  simulator.lock();reserve=simulator.ownReserve();
  beneficiary=UUID.randomUUID().toString();db.update("INSERT INTO MBX_BENEFICIARY(BENEFICIARY_ID,OWNER_USER_ID,DISPLAY_NAME,ACCOUNT_TOKEN,BANK_CODE,STATUS,VERIFIED_BY,VERIFIED_AT) VALUES (?,?,'Oracle rollback test','EXTERNAL-ROLLBACK','HDFC0000001','ACTIVE',?,SYSTIMESTAMP)",beneficiary,admin,checker);
 }
 String user(String role){return db.queryForList("SELECT U.USER_ID FROM M01_IAM_USER U JOIN M01_IAM_USER_ROLE A ON A.USER_ID=U.USER_ID JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID WHERE U.STATUS='ACTIVE' AND U.USER_TYPE='EMPLOYEE' AND R.ROLE_CODE=? AND A.STATUS='ACTIVE' AND A.SCOPE_TYPE='GLOBAL' AND A.VALID_FROM<=SYSTIMESTAMP AND (A.VALID_TO IS NULL OR A.VALID_TO>SYSTIMESTAMP) ORDER BY U.USER_ID",role).stream().map(r->(String)r.get("USER_ID")).findFirst().orElseThrow();}
 void as(String user){
  String session=UUID.randomUUID().toString();OffsetDateTime now=OffsetDateTime.now();
  db.update("INSERT INTO M01_IAM_SESSION(SESSION_ID,USER_ID,CLIENT_ID,AUTH_LEVEL,IDLE_EXPIRES_AT,ABSOLUTE_EXPIRES_AT) VALUES (?,?,'MONEYBAGS_WEB',?,?,?)",session,user,"MFA@"+now.toEpochSecond(),now.plusHours(1),now.plusHours(2));
  var roles=db.queryForList("SELECT R.ROLE_CODE FROM M01_IAM_USER_ROLE A JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID WHERE A.USER_ID=? AND A.STATUS='ACTIVE'",String.class,user);
  var permissions=db.queryForList("SELECT DISTINCT P.PERMISSION_CODE FROM M01_IAM_USER_ROLE A JOIN M01_IAM_ROLE_PERMISSION RP ON RP.ROLE_ID=A.ROLE_ID JOIN M01_IAM_PERMISSION P ON P.PERMISSION_ID=RP.PERMISSION_ID WHERE A.USER_ID=? AND A.STATUS='ACTIVE'",String.class,user);
  var principal=new UserPrincipal(user,"oracle-rollback","EMPLOYEE",session,1L,roles,permissions,List.of());
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,permissions.stream().map(SimpleGrantedAuthority::new).toList()));
 }
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 SettlementSimulator.Incoming incoming(String rail,String amount,String ref){String branch=db.queryForObject("SELECT BRANCH_CODE FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?",String.class,account);return new SettlementSimulator.Incoming("SBI",number,branch,"MCPB0000002",rail,new BigDecimal(amount),ref,"Oracle rollback test");}
 BigDecimal position(){return db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=?",BigDecimal.class,account);}
 @Test void incomingNeftRtgsAndImmediateCreditRoundTrip(){
  for(String rail:List.of("UPI","IMPS","NEFT","RTGS")){
   String ref=UUID.randomUUID().toString(),amount=rail.equals("RTGS")?"200000":"1";BigDecimal before=position();
   var p=simulator.incoming(incoming(rail,amount,ref));
   if(rail.equals("NEFT"))assertEquals(0,before.compareTo(position()));else assertEquals(0,before.add(new BigDecimal(amount)).compareTo(position()));
   if(p.settlementCycleId()!=null){simulator.closeCycle(p.settlementCycleId(),admin);simulator.settleCycle(p.settlementCycleId(),admin);simulator.settleCycle(p.settlementCycleId(),admin);}
   assertEquals(0,before.add(new BigDecimal(amount)).compareTo(position()));
   assertEquals(p.paymentId(),simulator.incoming(incoming(rail,amount,ref)).paymentId());
   assertEquals(0,db.queryForObject("SELECT SUM(M.NET_AMOUNT) FROM MBX_SIM_MOVEMENT M JOIN MBX_SIM_PAYMENT P ON P.SETTLEMENT_REFERENCE=M.SETTLEMENT_REFERENCE WHERE P.PAYMENT_ID=?",BigDecimal.class,p.paymentId()).signum());
  }
 }
 @Test void automaticOutgoingAndIndependentApprovalRoundTrip(){
  simulator.incoming(incoming("RTGS","1200000",UUID.randomUUID().toString()));
  for(String rail:List.of("UPI","IMPS","NEFT","RTGS")){
   String amount=rail.equals("RTGS")?"200000":"1";BigDecimal before=position();
   var p=workflow.initiate(new PaymentWorkflowController.Initiate(account,beneficiary,rail,new BigDecimal(amount),UUID.randomUUID().toString()));
   assertNotEquals("RECEIVED",p.status());assertEquals(0,before.subtract(new BigDecimal(amount)).compareTo(position()));
   if(p.settlementCycleId()!=null){simulator.closeCycle(p.settlementCycleId(),admin);simulator.settleCycle(p.settlementCycleId(),admin);}
   assertEquals("SETTLED",db.queryForObject("SELECT STATUS FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",String.class,p.paymentId()));
  }
  var p=workflow.initiate(new PaymentWorkflowController.Initiate(account,beneficiary,"NEFT",new BigDecimal("100000.01"),UUID.randomUUID().toString()));
  assertEquals("RECEIVED",p.status());as(checker);p=simulator.approve(p.paymentId(),reserve);assertNotNull(p.clearingBatchId());as(admin);simulator.closeCycle(p.settlementCycleId(),admin);simulator.settleCycle(p.settlementCycleId(),admin);
 }
}
