package com.moneybags.integration;

import com.moneybags.common.api.BusinessException;
import java.sql.Types;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** PIN failure counters commit independently of a rejected/rolled-back payment. */
@Service
public class PaymentPinService {
 private final JdbcTemplate db;private final com.moneybags.common.database.BusinessRepository views;private final PasswordEncoder passwords;private final Clock clock;private final TransactionTemplate attempts;
 public PaymentPinService(JdbcTemplate db,com.moneybags.common.database.BusinessRepository views,PasswordEncoder passwords,Clock clock,PlatformTransactionManager manager){
  this.db=db;this.views=views;this.passwords=passwords;this.clock=clock;attempts=new TransactionTemplate(manager);attempts.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 private record Result(String code,String message){}
 private String customer(){var actor=CurrentActor.get();if(!"CUSTOMER".equals(actor.userType()))throw new BusinessException(HttpStatus.FORBIDDEN,"CUSTOMER_ONLY","Payment PIN belongs to a signed-in customer");return actor.userId();}
 public Map<String,Object> status(){
  var rows=views.rows("SELECT LOCKED_UNTIL FROM MBX_PAYMENT_PIN WHERE USER_ID=?",customer());
  var result=new LinkedHashMap<String,Object>();result.put("configured",!rows.isEmpty());result.put("digits",6);
  result.put("locked",!rows.isEmpty()&&future(rows.get(0).get("LOCKED_UNTIL")));return result;
 }
 @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW) public void setup(String pin){
  String user=customer();validate(pin);db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USER_ID=? FOR UPDATE",String.class,user);
  if(db.queryForObject("SELECT COUNT(*) FROM MBX_PAYMENT_PIN WHERE USER_ID=?",Integer.class,user)>0)throw new BusinessException(HttpStatus.CONFLICT,"PIN_ALREADY_SET","Use Reset payment PIN to change your PIN");
  initialize(user,pin);audit(user,"PAYMENT_PIN_SET");
 }
 /** Signup uses this inside its user-creation transaction; no shared default PIN. */
 public void initialize(String user,String pin){validate(pin);db.update("INSERT INTO MBX_PAYMENT_PIN(USER_ID,PIN_HASH) VALUES (?,?)",user,passwords.encode(pin));}
 public void verify(String pin){
  String user=customer();Result result=attempts.execute(tx->{
   var rows=views.rows("SELECT PIN_HASH,FAILED_ATTEMPTS,LOCKED_UNTIL FROM MBX_PAYMENT_PIN WHERE USER_ID=? FOR UPDATE",user);
   if(rows.isEmpty())return new Result("PIN_SETUP_REQUIRED","Set up your payment PIN before sending money");
   var row=rows.get(0);if(future(row.get("LOCKED_UNTIL")))return new Result("PIN_LOCKED","Payment PIN is locked for up to 15 minutes. Try later or reset it in My security");
   if(pin==null||pin.isBlank())return new Result("PIN_REQUIRED","Enter your six-digit payment PIN to confirm this payment or transfer");
   int failed=row.get("LOCKED_UNTIL")!=null?0:((Number)row.get("FAILED_ATTEMPTS")).intValue();
   boolean matches=pin.matches("[0-9]{6}")&&passwords.matches(pin,(String)row.get("PIN_HASH"));
   if(matches){db.update("UPDATE MBX_PAYMENT_PIN SET FAILED_ATTEMPTS=0,LOCKED_UNTIL=NULL WHERE USER_ID=?",user);return new Result(null,null);}
   failed++;OffsetDateTime until=failed>=5?OffsetDateTime.now(clock).plusMinutes(15):null;
   db.update("UPDATE MBX_PAYMENT_PIN SET FAILED_ATTEMPTS=?,LOCKED_UNTIL=? WHERE USER_ID=?",new Object[]{failed,until,user},new int[]{Types.INTEGER,Types.TIMESTAMP_WITH_TIMEZONE,Types.VARCHAR});
   audit(user,"PAYMENT_PIN_REJECTED");return new Result(failed>=5?"PIN_LOCKED":"PIN_INVALID",failed>=5?"Payment PIN locked for 15 minutes. Reset it in My security or try later":"Incorrect payment PIN");
  });reject(result);
 }
 public void reset(String password,String newPin){
  String user=customer();validate(newPin);Result result=attempts.execute(tx->{
   var rows=views.rows("SELECT RESET_FAILED_ATTEMPTS,RESET_LOCKED_UNTIL FROM MBX_PAYMENT_PIN WHERE USER_ID=? FOR UPDATE",user);
   if(rows.isEmpty())return new Result("PIN_SETUP_REQUIRED","Set up your payment PIN first");var row=rows.get(0);
   if(future(row.get("RESET_LOCKED_UNTIL")))return new Result("PIN_RESET_LOCKED","PIN reset is locked for up to 15 minutes after incorrect password attempts");
   var credentials=db.queryForList("SELECT PASSWORD_HASH FROM M01_IAM_CREDENTIAL WHERE USER_ID=? AND STATUS='ACTIVE' AND HASH_SCHEME='BCRYPT' ORDER BY CHANGED_AT DESC FETCH FIRST 1 ROWS ONLY",String.class,user);
   boolean matches=password!=null&&password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72&&!credentials.isEmpty()&&passwords.matches(password,credentials.get(0));
   if(!matches){int failed=row.get("RESET_LOCKED_UNTIL")!=null?1:((Number)row.get("RESET_FAILED_ATTEMPTS")).intValue()+1;OffsetDateTime until=failed>=5?OffsetDateTime.now(clock).plusMinutes(15):null;
    db.update("UPDATE MBX_PAYMENT_PIN SET RESET_FAILED_ATTEMPTS=?,RESET_LOCKED_UNTIL=? WHERE USER_ID=?",new Object[]{failed,until,user},new int[]{Types.INTEGER,Types.TIMESTAMP_WITH_TIMEZONE,Types.VARCHAR});
    audit(user,"PAYMENT_PIN_RESET_REJECTED");return new Result(failed>=5?"PIN_RESET_LOCKED":"PASSWORD_INVALID",failed>=5?"PIN reset locked for 15 minutes":"Your current login password is incorrect");}
   db.update("UPDATE MBX_PAYMENT_PIN SET PIN_HASH=?,FAILED_ATTEMPTS=0,LOCKED_UNTIL=NULL,RESET_FAILED_ATTEMPTS=0,RESET_LOCKED_UNTIL=NULL,CHANGED_AT=? WHERE USER_ID=?",passwords.encode(newPin),OffsetDateTime.now(clock),user);
   audit(user,"PAYMENT_PIN_RESET");return new Result(null,null);
  });reject(result);
 }
 private boolean future(Object value){if(value==null)return false;OffsetDateTime time=value instanceof OffsetDateTime t?t:value instanceof java.sql.Timestamp t?t.toInstant().atOffset(ZoneOffset.UTC):OffsetDateTime.parse(value.toString());return time.toInstant().isAfter(clock.instant());}
 private void reject(Result result){if(result.code()!=null)throw new BusinessException(HttpStatus.FORBIDDEN,result.code(),result.message());}
 public static void validate(String pin){if(pin==null||!pin.matches("[0-9]{6}"))throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_PIN_FORMAT","Choose a six-digit payment PIN");}
 private void audit(String user,String action){db.update("INSERT INTO MBX_AUDIT(EVENT_ID,ACTOR_ID,ACTION_CODE,RESOURCE_TYPE,RESOURCE_ID,REASON_CODE) VALUES (?,?,?,'USER',?,'PAYMENT_PIN_SECURITY')",UUID.randomUUID().toString(),user,action,user);}
}
