package com.moneybags;
import com.moneybags.integration.PaymentPinService;
import com.moneybags.iam.security.UserPrincipal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
/** Oracle verification uses a disposable customer identity; no real customer PIN or balance is changed. */
@EnabledIfEnvironmentVariable(named="RUN_ALPHA_PAYMENT_TESTS",matches="true")
@SpringBootTest(properties={"moneybags.bootstrap.enabled=false","moneybags.deposit-interest.enabled=false","spring.sql.init.mode=never","moneybags.payment-simulation.enabled=false"})
class OraclePaymentPinSmokeTest {
 @Autowired PaymentPinService pins;@Autowired JdbcTemplate db;@Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;String user;
 @BeforeEach void create(){
  // Recover identities owned by an earlier interrupted smoke run. Never remove
  // append-only audit records or an identity linked to real banking records.
  for(String orphan:db.queryForList("SELECT U.USER_ID FROM M01_IAM_USER U WHERE U.USERNAME='pin-smoke-'||U.USER_ID AND U.USER_TYPE='CUSTOMER' AND NOT EXISTS (SELECT 1 FROM M01_IAM_USER_ROLE R WHERE R.USER_ID=U.USER_ID) AND NOT EXISTS (SELECT 1 FROM M01_IAM_CUSTOMER_LINK L WHERE L.USER_ID=U.USER_ID) AND NOT EXISTS (SELECT 1 FROM M01_IAM_SESSION S WHERE S.USER_ID=U.USER_ID)",String.class))cleanIdentity(orphan);
  user=UUID.randomUUID().toString();
  db.update("INSERT INTO M01_IAM_USER(USER_ID,USERNAME,USER_TYPE,STATUS) VALUES (?,?,'CUSTOMER','ACTIVE')",user,"pin-smoke-"+user);
  db.update("INSERT INTO M01_IAM_CREDENTIAL(CREDENTIAL_ID,USER_ID,PASSWORD_HASH,HASH_SCHEME,STATUS) VALUES (?,?,?,'BCRYPT','ACTIVE')",UUID.randomUUID().toString(),user,encoder.encode("PinSmoke!2026"));
  var actor=new UserPrincipal(user,"pin-smoke","CUSTOMER",UUID.randomUUID().toString(),1L,List.of(),List.of(),List.of());
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of()));
 }
 void cleanIdentity(String identity){db.update("DELETE FROM MBX_PAYMENT_PIN WHERE USER_ID=?",identity);db.update("DELETE FROM M01_IAM_CREDENTIAL WHERE USER_ID=?",identity);db.update("DELETE FROM M01_IAM_USER WHERE USER_ID=?",identity);}
 @AfterEach void clean(){try{if(user!=null)cleanIdentity(user);}finally{SecurityContextHolder.clearContext();}}
 @Test void nullableOracleLockTimestampsAndPasswordResetWork(){
  assertEquals(false,pins.status().get("configured"));pins.setup("134679");pins.verify("134679");
  for(int n=0;n<5;n++)assertThrows(com.moneybags.common.api.BusinessException.class,()->pins.verify("999999"));
  assertEquals(true,pins.status().get("locked"));assertThrows(com.moneybags.common.api.BusinessException.class,()->pins.verify("134679"));
  assertThrows(com.moneybags.common.api.BusinessException.class,()->pins.reset("incorrect","000123"));pins.reset("PinSmoke!2026","000123");
  assertEquals(false,pins.status().get("locked"));assertThrows(com.moneybags.common.api.BusinessException.class,()->pins.verify("134679"));pins.verify("000123");
  String hash=db.queryForObject("SELECT PIN_HASH FROM MBX_PAYMENT_PIN WHERE USER_ID=?",String.class,user);assertNotEquals("000123",hash);assertTrue(encoder.matches("000123",hash));
 }
}
