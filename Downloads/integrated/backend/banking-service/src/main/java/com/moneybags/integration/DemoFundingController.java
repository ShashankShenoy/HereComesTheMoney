package com.moneybags.integration;

import com.moneybags.common.api.BusinessException;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.txn.core.LedgerService;
import com.moneybags.txn.api.Contracts.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Explicit synthetic funding for demonstration accounts, separate from physical cash. */
@RestController @RequestMapping("/api/v1/accounts/demo-funding")
public class DemoFundingController {
 private final BusinessRepository db;private final BankingAccess access;private final LedgerService ledger;
 private final CustomerHashService audit;private final Clock clock;private final boolean enabled;
 public DemoFundingController(BusinessRepository db,BankingAccess access,LedgerService ledger,CustomerHashService audit,Clock clock,
  @Value("${moneybags.demo-funding.enabled:false}")boolean enabled){this.db=db;this.access=access;this.ledger=ledger;this.audit=audit;this.clock=clock;this.enabled=enabled;}
 public record Funding(@NotBlank @Size(max=100)String requestKey,@NotNull @Positive Long accountId,
  @NotNull @DecimalMin("0.01") @DecimalMax("10000000.00") @Digits(integer=8,fraction=2)BigDecimal amount){}
 @GetMapping("/availability") public Map<String,Boolean> availability(){return Map.of("enabled",enabled&&"EMPLOYEE".equals(CurrentActor.get().userType())&&CurrentActor.get().permissions().contains("TXN_POST"));}
 @PostMapping @Transactional public Map<String,Object> fund(@Valid @RequestBody Funding r){
  if(!enabled)throw new BusinessException(HttpStatus.CONFLICT,"DEMO_FUNDING_DISABLED","Demo money is disabled in this deployment");
  if(!"EMPLOYEE".equals(CurrentActor.get().userType()))throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","Only authorized bank staff can add demo money");
  var account=access.account("TXN_POST",r.accountId());
  db.one("SELECT ACCOUNT_ID FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=? FOR UPDATE",r.accountId());
  String actor=CurrentActor.get().userId(),requestKey="demo:"+r.requestKey();
  byte[] hash=CustomerHashService.digest(r.accountId()+"|"+r.amount().stripTrailingZeros().toPlainString());
  var previous=db.rows("SELECT TXN_ID,REQUEST_HASH FROM M05_TXN_TRANSACTION_LOG WHERE ORIGINATOR_ID=? AND CHANNEL_CODE='BRANCH' AND REQUEST_KEY=?",actor,requestKey);
  if(!previous.isEmpty()){
   if(!java.security.MessageDigest.isEqual(hash,(byte[])previous.get(0).get("REQUEST_HASH")))throw new BusinessException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","This request was already used for a different deposit");
   return receipt(((Number)previous.get(0).get("TXN_ID")).longValue(),r.accountId());
  }
  account=db.one("SELECT PRODUCT_VERSION_ID,LIFECYCLE_STATUS,CURRENCY_CODE FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?",r.accountId());
  if(!"INR".equals(account.get("CURRENCY_CODE"))||!Set.of("ACTIVE","PENDING_OPEN").contains(account.get("LIFECYCLE_STATUS")))throw new BusinessException(HttpStatus.CONFLICT,"ACCOUNT_STATUS","Choose an active or pending INR account");
  boolean opening="PENDING_OPEN".equals(account.get("LIFECYCLE_STATUS"));
  var mapping=db.one("SELECT GL_ACCOUNT_ID FROM M05_GL_PRODUCT_MAPPING WHERE PRODUCT_VERSION_ID=? AND POSTING_TYPE='DEPOSIT' AND GL_ROLE_CODE='CUSTOMER_LIABILITY' AND STATUS='ACTIVE' AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>=SYSDATE)",account.get("PRODUCT_VERSION_ID"));
  long demoGl=demoGl();LocalDate date=LocalDate.now(clock);String type=opening?"OPENING_FUNDING":"DEPOSIT";
  var key=new GeneratedKeyHolder();Object version=account.get("PRODUCT_VERSION_ID");
  db.jdbc().update(con->{var ps=con.prepareStatement("INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,TARGET_ACCOUNT_ID,PRODUCT_VERSION_ID,AMOUNT,VALUE_DATE) VALUES (?,'BRANCH',?,?,?,?,'VALIDATED',?,?,?,?)",new String[]{"TXN_ID"});Object[] values={actor,requestKey,hash,UUID.randomUUID().toString(),type,r.accountId(),version,r.amount(),date};for(int i=0;i<values.length;i++)ps.setObject(i+1,values[i]);return ps;},key);
  long txn=Objects.requireNonNull(key.getKey()).longValue();
  var journal=new JournalRequest("demo-funding:"+txn,type,txn,null,null,null,null,null,date,"Demo money added — simulated funds",List.of(
   new JournalLine(demoGl,null,null,null,"DR",r.amount(),"Simulated demo funding"),
   new JournalLine(((Number)mapping.get("GL_ACCOUNT_ID")).longValue(),r.accountId(),null,null,"CR",r.amount(),"Demo money added")));
  if(opening)ledger.postDemoOpeningFunding(journal,actor);else ledger.postJournal(journal,actor);
  db.jdbc().update("UPDATE M05_TXN_TRANSACTION_LOG SET STATUS='POSTED' WHERE TXN_ID=?",txn);
  for(String[] transition:List.of(new String[]{null,"RECEIVED"},new String[]{"RECEIVED","VALIDATED"},new String[]{"VALIDATED","POSTED"}))db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,?,?,?,'DEMO_FUNDING')",txn,transition[0],transition[1],actor);
  audit.audit("DEMO_MONEY_ADDED","TRANSACTION",Long.toString(txn),"SIMULATED_FUNDS");
  return receipt(txn,r.accountId());
 }
 private Map<String,Object> receipt(long txn,long account){return Map.of("simulated",true,"transaction",ledger.transaction(txn),"balance",ledger.position(account));}
 private long demoGl(){
  // Serialize first-use setup across demo funding requests without touching vaults or reserves.
  db.one("SELECT PRODUCT_ID FROM M03_PM_PRODUCT WHERE PRODUCT_ID=(SELECT MIN(PRODUCT_ID) FROM M03_PM_PRODUCT) FOR UPDATE");
  var rows=db.rows("SELECT GL_ACCOUNT_ID FROM M05_GL_ACCOUNT WHERE GL_CODE='DEMO_FUNDING'");
  if(rows.isEmpty())db.jdbc().update("INSERT INTO M05_GL_ACCOUNT(GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE,CURRENCY_CODE,ACTIVE_FLAG) VALUES ('DEMO_FUNDING','Simulated demo funding','EXPENSE','DR','INR','Y')");
  return ((Number)db.one("SELECT GL_ACCOUNT_ID FROM M05_GL_ACCOUNT WHERE GL_CODE='DEMO_FUNDING' AND ACCOUNT_CLASS='EXPENSE' AND NORMAL_SIDE='DR' AND CURRENCY_CODE='INR' AND ACTIVE_FLAG='Y'").get("GL_ACCOUNT_ID")).longValue();
 }
}
