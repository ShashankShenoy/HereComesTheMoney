package com.moneybags.integration;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.common.api.BusinessException;
import com.moneybags.txn.core.LedgerService;
import com.moneybags.txn.api.Contracts.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.http.HttpStatus;
import io.swagger.v3.oas.annotations.Operation;
import java.util.*;
import java.math.BigDecimal;
import java.time.*;

@RestController @RequestMapping("/api/v1/fees")
public class FeeCollectionController {
 private final BusinessRepository db;private final BankingAccess access;private final LedgerService ledger;private final CustomerHashService hashes;
 public FeeCollectionController(BusinessRepository db,BankingAccess access,LedgerService ledger,CustomerHashService hashes){this.db=db;this.access=access;this.ledger=ledger;this.hashes=hashes;}
 @GetMapping @Operation(summary="List assessed account fees")
 public List<Map<String,Object>> list(@RequestParam long accountId,@RequestHeader(value="X-Customer-Hash",required=false)String hash){access.account("TXN_READ",accountId);hashes.require(accountId,hash);return db.rows("SELECT FEE_ASSESSMENT_ID,PRODUCT_FEE_RULE_ID,ASSESSED_AMOUNT,PAID_AMOUNT,WAIVED_AMOUNT,STATUS,DUE_DATE FROM M05_FEE_ASSESSMENT WHERE BANK_ACCOUNT_ID=? ORDER BY ASSESSED_AT DESC",accountId);}
 @PostMapping("/{id}/collect") @Transactional @Operation(summary="Collect an assessed fee into the ledger")
 public TransactionView collect(@PathVariable long id){
  var f=db.one("SELECT * FROM M05_FEE_ASSESSMENT WHERE FEE_ASSESSMENT_ID=? FOR UPDATE",id);
  if(!(f.get("BANK_ACCOUNT_ID") instanceof Number account))throw fail("Loan fees need the loan receivable allocation workflow");
  var a=access.account("FEE_ASSESS",account.longValue());
  if("PAID".equals(f.get("STATUS"))){long txn=((Number)db.one("SELECT TXN_ID FROM M05_GL_JOURNAL WHERE JOURNAL_ID=?",f.get("LAST_JOURNAL_ID")).get("TXN_ID")).longValue();return ledger.transaction(txn);}
  if(!"ACTIVE".equals(a.get("LIFECYCLE_STATUS"))||!Set.of("ASSESSED","PART_PAID").contains(f.get("STATUS"))||BusinessRepository.localDate(f.get("DUE_DATE")).isAfter(LocalDate.now(ZoneId.of("Asia/Kolkata"))))throw fail("Fee is not due on an active account");
  BigDecimal amount=((BigDecimal)f.get("ASSESSED_AMOUNT")).subtract((BigDecimal)f.get("PAID_AMOUNT")).subtract((BigDecimal)f.get("WAIVED_AMOUNT"));
  if(amount.signum()<=0)throw fail("No fee remains to collect");
  long liability=gl(a.get("PRODUCT_VERSION_ID"),"CUSTOMER_LIABILITY"),income=gl(a.get("PRODUCT_VERSION_ID"),"FEE_INCOME");
  if(db.count("SELECT COUNT(*) FROM M05_GL_ACCOUNT WHERE GL_ACCOUNT_ID=? AND ACCOUNT_CLASS='INCOME'",income)!=1)throw fail("Fee mapping requires an income GL");
  String actor=CurrentActor.get().userId(),key="fee:"+id;var holder=new GeneratedKeyHolder();
  db.jdbc().update(c->{var p=c.prepareStatement("INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,SOURCE_ACCOUNT_ID,PRODUCT_VERSION_ID,AMOUNT,VALUE_DATE) VALUES (?,'FEE',?,?,?,'FEE','VALIDATED',?,?,?,?)",new String[]{"TXN_ID"});Object[] values={actor,key,CustomerHashService.digest(key),UUID.randomUUID().toString(),account.longValue(),a.get("PRODUCT_VERSION_ID"),amount,LocalDate.now(ZoneId.of("Asia/Kolkata"))};for(int n=0;n<values.length;n++)p.setObject(n+1,values[n]);return p;},holder);
  long txn=Objects.requireNonNull(holder.getKey()).longValue();
  long journal=ledger.postJournal(new JournalRequest(key,"FEE",txn,null,null,null,null,null,LocalDate.now(ZoneId.of("Asia/Kolkata")),"Account fee",List.of(new JournalLine(liability,account.longValue(),null,null,"DR",amount,"Account fee"),new JournalLine(income,null,null,null,"CR",amount,"Fee income"))),actor).journalId();
  db.jdbc().update("UPDATE M05_TXN_TRANSACTION_LOG SET STATUS='POSTED' WHERE TXN_ID=?",txn);
  for(String[] transition:List.of(new String[]{null,"RECEIVED"},new String[]{"RECEIVED","VALIDATED"},new String[]{"VALIDATED","POSTED"}))db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,?,?,?,'FEE_COLLECTION')",txn,transition[0],transition[1],actor);
  db.jdbc().update("UPDATE M05_FEE_ASSESSMENT SET PAID_AMOUNT=PAID_AMOUNT+?,STATUS='PAID',LAST_JOURNAL_ID=? WHERE FEE_ASSESSMENT_ID=?",amount,journal,id);hashes.audit("FEE_COLLECTED","ACCOUNT",account.toString(),"PRODUCT_FEE");return ledger.transaction(txn);
 }
 private long gl(Object version,String role){return ((Number)db.one("SELECT GL_ACCOUNT_ID FROM M05_GL_PRODUCT_MAPPING WHERE PRODUCT_VERSION_ID=? AND POSTING_TYPE='FEE' AND GL_ROLE_CODE=? AND STATUS='ACTIVE' AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>=SYSDATE)",version,role).get("GL_ACCOUNT_ID")).longValue();}
 private BusinessException fail(String m){return new BusinessException(HttpStatus.CONFLICT,"FEE_COLLECTION_RULE",m);}
}
