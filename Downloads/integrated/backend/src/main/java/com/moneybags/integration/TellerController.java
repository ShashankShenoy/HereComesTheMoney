package com.moneybags.integration;

import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.common.api.BusinessException;
import com.moneybags.txn.core.LedgerService;
import com.moneybags.txn.api.Contracts.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/teller")
public class TellerController {
 private final BusinessRepository db;private final BankingAccess access;private final LedgerService ledger;private final CustomerHashService audit;
 public TellerController(BusinessRepository db,BankingAccess access,LedgerService ledger,CustomerHashService audit){this.db=db;this.access=access;this.ledger=ledger;this.audit=audit;}
 public record OpenTill(@NotBlank String branchCode,@NotNull Long cashGlId){}
 public record CashCommand(@NotBlank @Size(max=100)String requestKey,@NotBlank String tillId,@NotNull Long accountId,@NotBlank @Pattern(regexp="DEPOSIT|WITHDRAWAL")String direction,@NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2)BigDecimal amount,@NotBlank @Size(max=160)String reason){}
 public record CloseTill(@NotNull @DecimalMin("0")BigDecimal countedCash,@NotBlank String reason){}
 @GetMapping("/tills") public List<Map<String,Object>> tills(){
  if(CurrentActor.get().permissions().contains("TELLER_READ")){
   access.global("TELLER_READ");
   return db.rows("SELECT * FROM MBX_TELLER_TILL ORDER BY BUSINESS_DATE DESC FETCH FIRST 100 ROWS ONLY");
  }
  CurrentActor.require("TELLER_OPERATE");
  return db.rows("SELECT * FROM MBX_TELLER_TILL WHERE TELLER_USER_ID=? ORDER BY BUSINESS_DATE DESC FETCH FIRST 100 ROWS ONLY",CurrentActor.get().userId());
 }
 @PostMapping("/tills") @Transactional public Map<String,String> open(@Valid @RequestBody OpenTill r){
  CurrentActor.require("TELLER_OPERATE");
  if(db.count("SELECT COUNT(*) FROM M05_GL_ACCOUNT WHERE GL_ACCOUNT_ID=? AND ACCOUNT_CLASS='ASSET' AND ACTIVE_FLAG='Y'",r.cashGlId())!=1)throw fail("Till must reference an active cash asset GL");
  String id=UUID.randomUUID().toString();
  db.jdbc().update("INSERT INTO MBX_TELLER_TILL(TILL_ID,TELLER_USER_ID,BRANCH_CODE,BUSINESS_DATE,CASH_GL_ID) VALUES (?,?,?,?,?)",id,CurrentActor.get().userId(),r.branchCode(),LocalDate.now(ZoneId.of("Asia/Kolkata")),r.cashGlId());
  audit.audit("TILL_OPENED","TILL",id,"ZERO_OPENING");return Map.of("tillId",id,"status","OPEN");
 }
 @PostMapping("/cash") @Transactional public TransactionView cash(@Valid @RequestBody CashCommand r){
  CurrentActor.require("TELLER_OPERATE");var a=access.account("TXN_POST",r.accountId());
  var till=db.one("SELECT * FROM MBX_TELLER_TILL WHERE TILL_ID=? FOR UPDATE",r.tillId());
  if(!CurrentActor.get().userId().equals(till.get("TELLER_USER_ID"))||!a.get("BRANCH_CODE").equals(till.get("BRANCH_CODE")))throw fail("Use your own till in the account branch");
  String canonical=r.tillId()+"|"+r.accountId()+"|"+r.direction()+"|"+r.amount().stripTrailingZeros().toPlainString()+"|"+r.reason();
  byte[] hash=CustomerHashService.digest(canonical);
  var old=db.rows("SELECT TXN_ID,REQUEST_HASH FROM MBX_CASH_OPERATION WHERE ACTOR_ID=? AND REQUEST_KEY=?",CurrentActor.get().userId(),r.requestKey());
  if(!old.isEmpty()){if(!java.security.MessageDigest.isEqual((byte[])old.get(0).get("REQUEST_HASH"),hash))throw fail("Idempotency key was used for different cash details");return ledger.transaction(((Number)old.get(0).get("TXN_ID")).longValue());}
  if(!"OPEN".equals(till.get("STATUS"))||!LocalDate.now(ZoneId.of("Asia/Kolkata")).equals(BusinessRepository.localDate(till.get("BUSINESS_DATE"))))throw fail("Till is closed or belongs to a different business date");
  boolean deposit=r.direction().equals("DEPOSIT");
  if(!"ACTIVE".equals(a.get("LIFECYCLE_STATUS")))throw fail("Account must be active");
  BigDecimal balance=(BigDecimal)till.get("CASH_BALANCE");
  if(!deposit&&balance.compareTo(r.amount())<0)throw fail("Insufficient cash in the teller till");
  var gl=db.one("SELECT GL_ACCOUNT_ID FROM M05_GL_PRODUCT_MAPPING WHERE PRODUCT_VERSION_ID=? AND POSTING_TYPE=? AND GL_ROLE_CODE='CUSTOMER_LIABILITY' AND STATUS='ACTIVE' AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>=SYSDATE)",a.get("PRODUCT_VERSION_ID"),r.direction());
  var key=new GeneratedKeyHolder();String actor=CurrentActor.get().userId();LocalDate date=LocalDate.now(ZoneId.of("Asia/Kolkata"));
  db.jdbc().update(con->{var ps=con.prepareStatement("INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID,PRODUCT_VERSION_ID,AMOUNT,VALUE_DATE) VALUES (?,'TELLER',?,?,?,?,'VALIDATED',?,?,?,?,?)",new String[]{"TXN_ID"});Object[] values={actor,r.requestKey(),hash,UUID.randomUUID().toString(),r.direction(),deposit?null:r.accountId(),deposit?r.accountId():null,a.get("PRODUCT_VERSION_ID"),r.amount(),date};for(int i=0;i<values.length;i++)ps.setObject(i+1,values[i]);return ps;},key);
  long txn=Objects.requireNonNull(key.getKey()).longValue();
  long customerGl=((Number)gl.get("GL_ACCOUNT_ID")).longValue(),cashGl=((Number)till.get("CASH_GL_ID")).longValue();
  var journal=ledger.postJournal(new JournalRequest("cash:"+txn,r.direction(),txn,null,null,null,null,null,date,r.reason(),List.of(
   new JournalLine(cashGl,null,null,null,deposit?"DR":"CR",r.amount(),"Teller cash"),
   new JournalLine(customerGl,r.accountId(),null,null,deposit?"CR":"DR",r.amount(),r.reason()))),actor);
  db.jdbc().update("UPDATE M05_TXN_TRANSACTION_LOG SET STATUS='POSTED' WHERE TXN_ID=?",txn);
  db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,NULL,'RECEIVED',?,'CASH_RECEIVED')",txn,actor);
  db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,'RECEIVED','VALIDATED',?,'TILL_VALIDATED')",txn,actor);
  db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,'VALIDATED','POSTED',?,'CASH_POSTED')",txn,actor);
  db.jdbc().update("UPDATE MBX_TELLER_TILL SET CASH_BALANCE=CASH_BALANCE+? WHERE TILL_ID=?",deposit?r.amount():r.amount().negate(),r.tillId());
  db.jdbc().update("INSERT INTO MBX_CASH_OPERATION(OPERATION_ID,REQUEST_KEY,ACTOR_ID,ACCOUNT_ID,TILL_ID,DIRECTION,AMOUNT,TXN_ID,JOURNAL_ID,REQUEST_HASH) VALUES (?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),r.requestKey(),actor,r.accountId(),r.tillId(),r.direction(),r.amount(),txn,journal.journalId(),hash);
  audit.audit("CASH_"+r.direction(),"TRANSACTION",Long.toString(txn),r.reason());return ledger.transaction(txn);
 }
 @PostMapping("/tills/{id}/close") @Transactional public Map<String,String> close(@PathVariable String id,@Valid @RequestBody CloseTill r){
  access.global("TELLER_APPROVE");var till=db.one("SELECT * FROM MBX_TELLER_TILL WHERE TILL_ID=? FOR UPDATE",id);
  if(till.get("TELLER_USER_ID").equals(CurrentActor.get().userId()))throw fail("A distinct checker must close the till");
  if(!"OPEN".equals(till.get("STATUS"))||r.countedCash().compareTo((BigDecimal)till.get("CASH_BALANCE"))!=0)throw fail("Till must be open and counted cash must match recorded cash");
  db.jdbc().update("UPDATE MBX_TELLER_TILL SET STATUS='CLOSED',CLOSED_BY=? WHERE TILL_ID=?",CurrentActor.get().userId(),id);
  audit.audit("TILL_CLOSED","TILL",id,r.reason());return Map.of("tillId",id,"status","CLOSED");
 }
 private BusinessException fail(String message){return new BusinessException(HttpStatus.CONFLICT,"CASH_CONTROL",message);}
}
