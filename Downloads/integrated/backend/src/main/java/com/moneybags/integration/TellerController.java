package com.moneybags.integration;

import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.common.api.BusinessException;
import com.moneybags.txn.core.LedgerService;
import com.moneybags.treasury.service.TreasuryService;
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
 private static final BigDecimal OPENING_CASH=new BigDecimal("5000.00");
 private final BusinessRepository db;private final BankingAccess access;private final LedgerService ledger;private final TreasuryService treasury;private final CustomerHashService audit;
 public TellerController(BusinessRepository db,BankingAccess access,LedgerService ledger,TreasuryService treasury,CustomerHashService audit){this.db=db;this.access=access;this.ledger=ledger;this.treasury=treasury;this.audit=audit;}
 public record OpenTill(@NotBlank String branchCode,@NotNull Long cashGlId){}
 public record OpenVault(@NotBlank String branchCode,@NotNull Long vaultGlId,@NotNull @DecimalMin("0") @Digits(integer=16,fraction=2)BigDecimal countedCash,@NotBlank @Size(max=160)String evidenceRef){}
 public record RequestCashDelivery(@NotBlank @Size(max=120)String requestKey,@NotBlank String branchCode,
  @NotNull @Positive Long reserveAccountId,@NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2)BigDecimal amount,
  @NotBlank @Size(max=120)String shipmentRef,@NotBlank @Size(max=250)String requestEvidenceRef){}
 public record ConfirmCashDelivery(@NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2)BigDecimal countedCash,
  @NotBlank @Size(max=250)String receiptEvidenceRef){}
 public record ReplenishTill(@NotBlank @Size(max=100)String requestKey,
  @NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2)BigDecimal amount,
  @NotBlank @Size(max=160)String evidenceRef){}
 public record CashCommand(@NotBlank @Size(max=100)String requestKey,@NotBlank String tillId,@NotNull Long accountId,@NotBlank @Pattern(regexp="DEPOSIT|WITHDRAWAL")String direction,@NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2)BigDecimal amount,@NotBlank @Size(max=160)String reason){}
 public record CloseTill(@NotNull @DecimalMin("0")BigDecimal countedCash,@NotBlank String reason){}
 @GetMapping("/cash-ledger-accounts") public List<Map<String,Object>> cashLedgerAccounts(){
  if(!CurrentActor.get().permissions().contains("TELLER_OPERATE"))CurrentActor.require("TELLER_READ");
  return db.rows("SELECT GL_ACCOUNT_ID,GL_CODE,GL_NAME FROM M05_GL_ACCOUNT g WHERE ACCOUNT_CLASS='ASSET' AND ACTIVE_FLAG='Y' AND (UPPER(GL_CODE) LIKE '%CASH%' OR UPPER(GL_NAME) LIKE '%CASH%') AND NOT EXISTS (SELECT 1 FROM MBX_BRANCH_VAULT v WHERE v.VAULT_GL_ID=g.GL_ACCOUNT_ID) AND NOT EXISTS (SELECT 1 FROM M07_RESERVE_ACCOUNT r WHERE r.GL_ACCOUNT_ID=g.GL_ACCOUNT_ID) ORDER BY GL_CODE");
 }
 @GetMapping("/vaults") public List<Map<String,Object>> vaults(){
  access.global("TELLER_APPROVE");
  return db.rows("SELECT BRANCH_CODE,VAULT_GL_ID,CASH_BALANCE,OPENING_EVIDENCE_REF,UPDATED_AT FROM MBX_BRANCH_VAULT ORDER BY BRANCH_CODE");
 }
 @PostMapping("/vaults") @Transactional public Map<String,Object> registerVault(@Valid @RequestBody OpenVault r){
  access.global("GL_ADMIN");
  access.branch("TELLER_APPROVE",r.branchCode());
  if(db.count("SELECT COUNT(*) FROM M05_GL_ACCOUNT g WHERE GL_ACCOUNT_ID=? AND ACCOUNT_CLASS='ASSET' AND ACTIVE_FLAG='Y' AND CURRENCY_CODE='INR' AND (UPPER(GL_CODE) LIKE '%CASH%' OR UPPER(GL_NAME) LIKE '%CASH%') AND NOT EXISTS (SELECT 1 FROM M07_RESERVE_ACCOUNT r WHERE r.GL_ACCOUNT_ID=g.GL_ACCOUNT_ID)",r.vaultGlId())!=1)throw fail("Vault must reference an active INR cash asset GL that is not an RBI reserve GL");
  if(db.count("SELECT COUNT(*) FROM MBX_BRANCH_VAULT WHERE BRANCH_CODE=? OR VAULT_GL_ID=?",r.branchCode(),r.vaultGlId())>0)throw fail("Branch vault or vault GL is already registered");
  if(db.count("SELECT COUNT(*) FROM MBX_TELLER_TILL WHERE CASH_GL_ID=?",r.vaultGlId())>0)throw fail("A vault GL cannot also be used by a teller till");
  BigDecimal booked=db.jdbc().queryForObject("SELECT COALESCE(SUM(CASE WHEN ENTRY_SIDE='DR' THEN AMOUNT ELSE -AMOUNT END),0) FROM M05_GL_POSTING WHERE GL_ACCOUNT_ID=?",BigDecimal.class,r.vaultGlId());
  if(booked==null||booked.compareTo(r.countedCash())!=0)throw fail("Counted vault cash must equal the cash already booked to its GL account; vault setup cannot create money");
  db.jdbc().update("INSERT INTO MBX_BRANCH_VAULT(BRANCH_CODE,VAULT_GL_ID,CASH_BALANCE,OPENING_EVIDENCE_REF) VALUES (?,?,?,?)",r.branchCode(),r.vaultGlId(),r.countedCash(),r.evidenceRef());
  audit.audit("VAULT_REGISTERED","BRANCH_VAULT",r.branchCode(),r.evidenceRef());
  return Map.of("branchCode",r.branchCode(),"vaultGlId",r.vaultGlId(),"cashBalance",r.countedCash());
 }
 @GetMapping("/cash-deliveries") public List<Map<String,Object>> cashDeliveries(){
  access.global("TELLER_READ");
  return db.rows("SELECT DELIVERY_ID,BRANCH_CODE,RESERVE_ACCOUNT_ID,AMOUNT,SHIPMENT_REF,STATUS,MAKER_ID,CHECKER_ID,JOURNAL_ID,EVIDENCE_ID,TREASURY_ENTRY_ID,REQUESTED_AT,DECIDED_AT FROM MBX_RBI_CASH_DELIVERY ORDER BY REQUESTED_AT DESC FETCH FIRST 100 ROWS ONLY");
 }
 @PostMapping("/cash-deliveries") @Transactional public Map<String,Object> requestCashDelivery(@Valid @RequestBody RequestCashDelivery r){
  access.global("TREASURY_LIQUIDITY_MANAGE");
  access.branch("TELLER_APPROVE",r.branchCode());
  if(db.count("SELECT COUNT(*) FROM MBX_BRANCH_VAULT WHERE BRANCH_CODE=?",r.branchCode())!=1)
   throw fail("Register a separate branch vault cash GL before requesting a delivery");
  if(db.count("SELECT COUNT(*) FROM M07_RESERVE_ACCOUNT WHERE RESERVE_ACCOUNT_ID=? AND ACCOUNT_TYPE='RBI_CURRENT' AND ACCOUNT_STATUS='ACTIVE'",r.reserveAccountId())!=1)
   throw fail("Select an active simulated RBI current reserve account");
  var existing=db.rows("SELECT DELIVERY_ID,BRANCH_CODE,RESERVE_ACCOUNT_ID,AMOUNT,SHIPMENT_REF,REQUEST_EVIDENCE_REF,STATUS FROM MBX_RBI_CASH_DELIVERY WHERE REQUEST_KEY=?",r.requestKey());
  if(!existing.isEmpty()){
   var row=existing.get(0);
   if(!Objects.equals(row.get("BRANCH_CODE"),r.branchCode())||((Number)row.get("RESERVE_ACCOUNT_ID")).longValue()!=r.reserveAccountId()
    ||((BigDecimal)row.get("AMOUNT")).compareTo(r.amount())!=0||!Objects.equals(row.get("SHIPMENT_REF"),r.shipmentRef())
    ||!Objects.equals(row.get("REQUEST_EVIDENCE_REF"),r.requestEvidenceRef()))throw fail("Request key was used for another cash delivery");
   return Map.of("deliveryId",row.get("DELIVERY_ID"),"status",row.get("STATUS"));
  }
  String id=UUID.randomUUID().toString();
  db.jdbc().update("INSERT INTO MBX_RBI_CASH_DELIVERY(DELIVERY_ID,REQUEST_KEY,BRANCH_CODE,RESERVE_ACCOUNT_ID,AMOUNT,SHIPMENT_REF,REQUEST_EVIDENCE_REF,MAKER_ID) VALUES (?,?,?,?,?,?,?,?)",
   id,r.requestKey(),r.branchCode(),r.reserveAccountId(),r.amount(),r.shipmentRef(),r.requestEvidenceRef(),CurrentActor.get().userId());
  audit.audit("CASH_DELIVERY_REQUESTED","CASH_DELIVERY",id,"SIMULATED_RBI_SHIPMENT");
  return Map.of("deliveryId",id,"status","PENDING");
 }
 @PostMapping("/cash-deliveries/{id}/confirm") @Transactional public Map<String,Object> confirmCashDelivery(@PathVariable String id,@Valid @RequestBody ConfirmCashDelivery r){
  var delivery=db.one("SELECT * FROM MBX_RBI_CASH_DELIVERY WHERE DELIVERY_ID=? FOR UPDATE",id);
  access.global("TREASURY_WORK_APPROVE");
  access.branch("TELLER_APPROVE",(String)delivery.get("BRANCH_CODE"));
  String checker=CurrentActor.get().userId();
  if(checker.equals(delivery.get("MAKER_ID")))throw fail("An independent checker must confirm receipt of the cash delivery");
  if("CONFIRMED".equals(delivery.get("STATUS"))){
   if(((BigDecimal)delivery.get("AMOUNT")).compareTo(r.countedCash())!=0||!Objects.equals(delivery.get("RECEIPT_EVIDENCE_REF"),r.receiptEvidenceRef()))
    throw fail("Cash delivery was confirmed with different receipt details");
   return Map.of("deliveryId",id,"status","CONFIRMED","journalId",delivery.get("JOURNAL_ID"),"treasuryEntryId",delivery.get("TREASURY_ENTRY_ID"));
  }
  if(!"PENDING".equals(delivery.get("STATUS"))||((BigDecimal)delivery.get("AMOUNT")).compareTo(r.countedCash())!=0)
   throw fail("Counted notes must match the pending cash delivery amount");
  String branch=(String)delivery.get("BRANCH_CODE");
  var vault=db.one("SELECT * FROM MBX_BRANCH_VAULT WHERE BRANCH_CODE=? FOR UPDATE",branch);
  long reserveId=((Number)delivery.get("RESERVE_ACCOUNT_ID")).longValue();
  BigDecimal amount=(BigDecimal)delivery.get("AMOUNT");
  try {
  var reserve=treasury.requireCashDeliveryCapacity(reserveId,amount);
  long vaultGl=((Number)vault.get("VAULT_GL_ID")).longValue(),reserveGl=reserve.glAccountId();
  if(vaultGl==reserveGl||db.count("SELECT COUNT(*) FROM M07_RESERVE_ACCOUNT WHERE GL_ACCOUNT_ID=?",reserveGl)!=1)
   throw fail("Reserve and vault must use distinct, unshared GL accounts");
  if(db.count("SELECT COUNT(*) FROM M05_GL_ACCOUNT WHERE GL_ACCOUNT_ID=? AND ACCOUNT_CLASS='ASSET' AND NORMAL_SIDE='DR' AND ACTIVE_FLAG='Y' AND CURRENCY_CODE='INR'",reserveGl)!=1)
   throw fail("RBI reserve must use an active INR asset GL account");
  BigDecimal vaultBooked=db.jdbc().queryForObject("SELECT COALESCE(SUM(CASE WHEN ENTRY_SIDE='DR' THEN AMOUNT ELSE -AMOUNT END),0) FROM M05_GL_POSTING WHERE GL_ACCOUNT_ID=?",BigDecimal.class,vaultGl);
  BigDecimal reserveBooked=db.jdbc().queryForObject("SELECT COALESCE(SUM(CASE WHEN ENTRY_SIDE='DR' THEN AMOUNT ELSE -AMOUNT END),0) FROM M05_GL_POSTING WHERE GL_ACCOUNT_ID=?",BigDecimal.class,reserveGl);
  BigDecimal reservePosition=db.jdbc().queryForObject("SELECT CONFIRMED_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=?",BigDecimal.class,reserveId);
  if(vaultBooked.compareTo((BigDecimal)vault.get("CASH_BALANCE"))!=0||reserveBooked.compareTo(reservePosition)!=0)
   throw fail("Cash vault or RBI reserve does not agree with its GL book");
  LocalDate date=LocalDate.now(ZoneId.of("Asia/Kolkata"));
  var key=new GeneratedKeyHolder();
  db.jdbc().update(con->{var ps=con.prepareStatement("INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,AMOUNT,VALUE_DATE) VALUES (?,'TREASURY',?,?,?,'ADJUSTMENT','VALIDATED',?,?)",new String[]{"TXN_ID"});Object[] values={checker,"cash-delivery:"+id,CustomerHashService.digest("cash-delivery:"+id),id,amount,date};for(int i=0;i<values.length;i++)ps.setObject(i+1,values[i]);return ps;},key);
  long txn=Objects.requireNonNull(key.getKey()).longValue();
  var journal=ledger.postJournal(new JournalRequest("cash-delivery:"+id,"ADJUSTMENT",txn,null,null,null,null,null,date,
   "Simulated RBI cash delivery to branch vault",List.of(
    new JournalLine(vaultGl,null,null,null,"DR",amount,"Counted notes received at "+branch),
    new JournalLine(reserveGl,null,null,null,"CR",amount,"Simulated RBI reserve debit for cash shipment"))),checker);
  markAdjustmentPosted(txn,checker,"CASH_DELIVERY");
  var posted=treasury.confirmCashDelivery(id,reserveId,amount,(String)delivery.get("SHIPMENT_REF"),r.receiptEvidenceRef(),journal.journalId(),java.time.OffsetDateTime.now(),checker);
  db.jdbc().update("UPDATE MBX_BRANCH_VAULT SET CASH_BALANCE=CASH_BALANCE+?,UPDATED_AT=SYSTIMESTAMP WHERE BRANCH_CODE=?",amount,branch);
  db.jdbc().update("UPDATE MBX_RBI_CASH_DELIVERY SET STATUS='CONFIRMED',RECEIPT_EVIDENCE_REF=?,CHECKER_ID=?,TXN_ID=?,JOURNAL_ID=?,EVIDENCE_ID=?,TREASURY_ENTRY_ID=?,DECIDED_AT=SYSTIMESTAMP WHERE DELIVERY_ID=?",
   r.receiptEvidenceRef(),checker,txn,journal.journalId(),posted.evidenceId(),posted.treasuryEntryId(),id);
  audit.audit("CASH_DELIVERY_CONFIRMED","CASH_DELIVERY",id,"SIMULATED_RBI_SHIPMENT");
  return Map.of("deliveryId",id,"status","CONFIRMED","journalId",journal.journalId(),"treasuryEntryId",posted.treasuryEntryId(),"vaultBalance",((BigDecimal)vault.get("CASH_BALANCE")).add(amount));
  } catch(com.moneybags.treasury.domain.DomainException treasuryError) {
   throw new BusinessException(HttpStatus.valueOf(treasuryError.status()),treasuryError.code(),treasuryError.getMessage());
  }
 }
 @GetMapping("/tills") public List<Map<String,Object>> tills(){
  if(CurrentActor.get().permissions().contains("TELLER_READ")){
   access.global("TELLER_READ");
   return db.rows("SELECT * FROM MBX_TELLER_TILL ORDER BY BUSINESS_DATE DESC FETCH FIRST 100 ROWS ONLY");
  }
  CurrentActor.require("TELLER_OPERATE");
  return db.rows("SELECT * FROM MBX_TELLER_TILL WHERE TELLER_USER_ID=? ORDER BY BUSINESS_DATE DESC FETCH FIRST 100 ROWS ONLY",CurrentActor.get().userId());
 }
 @GetMapping("/replenishments") public List<Map<String,Object>> replenishments(){
  access.global("TELLER_READ");
  return db.rows("SELECT REPLENISHMENT_ID,REQUEST_KEY,TILL_ID,BRANCH_CODE,AMOUNT,TILL_BALANCE_AFTER,EVIDENCE_REF,TXN_ID,JOURNAL_ID,APPROVED_BY,CREATED_AT FROM MBX_TILL_REPLENISHMENT ORDER BY CREATED_AT DESC FETCH FIRST 100 ROWS ONLY");
 }
 @PostMapping("/tills") @Transactional public Map<String,Object> open(@Valid @RequestBody OpenTill r){
  access.branch("TELLER_OPERATE",r.branchCode());
  if(db.count("SELECT COUNT(*) FROM M05_GL_ACCOUNT g WHERE GL_ACCOUNT_ID=? AND ACCOUNT_CLASS='ASSET' AND ACTIVE_FLAG='Y' AND CURRENCY_CODE='INR' AND (UPPER(GL_CODE) LIKE '%CASH%' OR UPPER(GL_NAME) LIKE '%CASH%') AND NOT EXISTS (SELECT 1 FROM M07_RESERVE_ACCOUNT r WHERE r.GL_ACCOUNT_ID=g.GL_ACCOUNT_ID)",r.cashGlId())!=1)throw fail("Till must reference an active INR cash asset GL that is not an RBI reserve GL");
  var vaults=db.rows("SELECT * FROM MBX_BRANCH_VAULT WHERE BRANCH_CODE=? FOR UPDATE",r.branchCode());
  if(vaults.isEmpty())throw fail("Register counted branch vault cash before opening a till");
  var vault=vaults.get(0);
  long vaultGl=((Number)vault.get("VAULT_GL_ID")).longValue();
  if(vaultGl==r.cashGlId())throw fail("Till cash and vault cash need separate GL accounts");
  if(((BigDecimal)vault.get("CASH_BALANCE")).compareTo(OPENING_CASH)<0)throw fail("Branch vault has less than INR 5,000 available for opening the till");
  String id=UUID.randomUUID().toString();
  String actor=CurrentActor.get().userId();LocalDate date=LocalDate.now(ZoneId.of("Asia/Kolkata"));
  db.jdbc().update("INSERT INTO MBX_TELLER_TILL(TILL_ID,TELLER_USER_ID,BRANCH_CODE,BUSINESS_DATE,CASH_GL_ID) VALUES (?,?,?,?,?)",id,actor,r.branchCode(),date,r.cashGlId());
  var key=new GeneratedKeyHolder();
  db.jdbc().update(con->{var ps=con.prepareStatement("INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,AMOUNT,VALUE_DATE) VALUES (?,'TELLER',?,?,?,'ADJUSTMENT','VALIDATED',?,?)",new String[]{"TXN_ID"});Object[] values={actor,"till-opening:"+id,CustomerHashService.digest("till-opening:"+id),id,OPENING_CASH,date};for(int i=0;i<values.length;i++)ps.setObject(i+1,values[i]);return ps;},key);
  long txn=Objects.requireNonNull(key.getKey()).longValue();
  var journal=ledger.postJournal(new JournalRequest("till-opening:"+id,"ADJUSTMENT",txn,null,null,null,null,null,date,"Vault to teller cash allocation",List.of(
   new JournalLine(r.cashGlId(),null,null,null,"DR",OPENING_CASH,"Cash allocated to till "+id),
   new JournalLine(vaultGl,null,null,null,"CR",OPENING_CASH,"Cash released from vault "+r.branchCode()))),actor);
  markAdjustmentPosted(txn,actor,"TILL_OPENED");
  db.jdbc().update("UPDATE MBX_BRANCH_VAULT SET CASH_BALANCE=CASH_BALANCE-?,UPDATED_AT=SYSTIMESTAMP WHERE BRANCH_CODE=?",OPENING_CASH,r.branchCode());
  db.jdbc().update("UPDATE MBX_TELLER_TILL SET CASH_BALANCE=? WHERE TILL_ID=?",OPENING_CASH,id);
  db.jdbc().update("INSERT INTO MBX_TILL_ALLOCATION(ALLOCATION_ID,TILL_ID,BRANCH_CODE,VAULT_GL_ID,TILL_GL_ID,AMOUNT,JOURNAL_ID,CREATED_BY) VALUES (?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,r.branchCode(),vaultGl,r.cashGlId(),OPENING_CASH,journal.journalId(),actor);
  audit.audit("TILL_OPENED","TILL",id,"VAULT_ALLOCATION");
  return Map.of("tillId",id,"status","OPEN","openingCash",OPENING_CASH,"fundingJournalId",journal.journalId(),"fundingSource","BRANCH_VAULT");
 }
 @PostMapping("/tills/{id}/replenish") @Transactional public Map<String,Object> replenish(@PathVariable String id,@Valid @RequestBody ReplenishTill r){
  var initial=db.one("SELECT BRANCH_CODE FROM MBX_TELLER_TILL WHERE TILL_ID=?",id);
  String branch=(String)initial.get("BRANCH_CODE");
  access.branch("TELLER_APPROVE",branch);
  var vault=db.one("SELECT * FROM MBX_BRANCH_VAULT WHERE BRANCH_CODE=? FOR UPDATE",branch);
  var till=db.one("SELECT * FROM MBX_TELLER_TILL WHERE TILL_ID=? FOR UPDATE",id);
  String checker=CurrentActor.get().userId();
  if(checker.equals(till.get("TELLER_USER_ID")))throw fail("A second person must approve and count teller replenishment");
  byte[] hash=CustomerHashService.digest(id+"|"+r.amount().stripTrailingZeros().toPlainString()+"|"+r.evidenceRef());
  var old=db.rows("SELECT REPLENISHMENT_ID,TILL_ID,REQUEST_HASH,JOURNAL_ID,TILL_BALANCE_AFTER FROM MBX_TILL_REPLENISHMENT WHERE REQUEST_KEY=?",r.requestKey());
  if(!old.isEmpty()){
   var row=old.get(0);
   if(!id.equals(row.get("TILL_ID"))||!java.security.MessageDigest.isEqual(hash,(byte[])row.get("REQUEST_HASH")))
    throw fail("Request key was used for different teller replenishment details");
   return Map.of("replenishmentId",row.get("REPLENISHMENT_ID"),"tillId",id,"journalId",row.get("JOURNAL_ID"),"tillBalance",row.get("TILL_BALANCE_AFTER"));
  }
  if(!"OPEN".equals(till.get("STATUS"))||!LocalDate.now(ZoneId.of("Asia/Kolkata")).equals(BusinessRepository.localDate(till.get("BUSINESS_DATE"))))
   throw fail("Till must be open on the current business date");
  BigDecimal vaultBalance=(BigDecimal)vault.get("CASH_BALANCE");
  if(vaultBalance.compareTo(r.amount())<0)throw fail("Branch vault has insufficient counted cash for teller replenishment");
  long vaultGl=((Number)vault.get("VAULT_GL_ID")).longValue(),tillGl=((Number)till.get("CASH_GL_ID")).longValue();
  if(vaultGl==tillGl)throw fail("Vault and teller till must use separate GL accounts");
  BigDecimal vaultBooked=db.jdbc().queryForObject("SELECT COALESCE(SUM(CASE WHEN ENTRY_SIDE='DR' THEN AMOUNT ELSE -AMOUNT END),0) FROM M05_GL_POSTING WHERE GL_ACCOUNT_ID=?",BigDecimal.class,vaultGl);
  if(vaultBooked.compareTo(vaultBalance)!=0)throw fail("Branch vault cash does not agree with its GL book");
  String replenishmentId=UUID.randomUUID().toString(),postingKey="till-replenishment:"+replenishmentId;
  LocalDate date=LocalDate.now(ZoneId.of("Asia/Kolkata"));
  var key=new GeneratedKeyHolder();
  db.jdbc().update(con->{var ps=con.prepareStatement("INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,AMOUNT,VALUE_DATE) VALUES (?,'TELLER',?,?,?,'ADJUSTMENT','VALIDATED',?,?)",new String[]{"TXN_ID"});Object[] values={checker,postingKey,hash,replenishmentId,r.amount(),date};for(int i=0;i<values.length;i++)ps.setObject(i+1,values[i]);return ps;},key);
  long txn=Objects.requireNonNull(key.getKey()).longValue();
  var journal=ledger.postJournal(new JournalRequest(postingKey,"ADJUSTMENT",txn,null,null,null,null,null,date,
   "Vault to teller cash replenishment: "+r.evidenceRef(),List.of(
    new JournalLine(tillGl,null,null,null,"DR",r.amount(),"Counted cash added to till "+id),
    new JournalLine(vaultGl,null,null,null,"CR",r.amount(),"Counted cash released from vault "+branch))),checker);
  markAdjustmentPosted(txn,checker,"TILL_REPLENISHED");
  BigDecimal after=((BigDecimal)till.get("CASH_BALANCE")).add(r.amount());
  db.jdbc().update("UPDATE MBX_BRANCH_VAULT SET CASH_BALANCE=CASH_BALANCE-?,UPDATED_AT=SYSTIMESTAMP WHERE BRANCH_CODE=?",r.amount(),branch);
  db.jdbc().update("UPDATE MBX_TELLER_TILL SET CASH_BALANCE=? WHERE TILL_ID=?",after,id);
  db.jdbc().update("INSERT INTO MBX_TILL_REPLENISHMENT(REPLENISHMENT_ID,REQUEST_KEY,REQUEST_HASH,TILL_ID,BRANCH_CODE,AMOUNT,TILL_BALANCE_AFTER,EVIDENCE_REF,TXN_ID,JOURNAL_ID,APPROVED_BY) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
   replenishmentId,r.requestKey(),hash,id,branch,r.amount(),after,r.evidenceRef(),txn,journal.journalId(),checker);
  audit.audit("TILL_REPLENISHED","TILL",id,r.evidenceRef());
  return Map.of("replenishmentId",replenishmentId,"tillId",id,"journalId",journal.journalId(),"tillBalance",after);
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
  var initial=db.one("SELECT BRANCH_CODE FROM MBX_TELLER_TILL WHERE TILL_ID=?",id);
  access.branch("TELLER_APPROVE",(String)initial.get("BRANCH_CODE"));
  boolean allocated=db.count("SELECT COUNT(*) FROM MBX_TILL_ALLOCATION WHERE TILL_ID=?",id)==1;
  Map<String,Object> vault=allocated?db.one("SELECT * FROM MBX_BRANCH_VAULT WHERE BRANCH_CODE=? FOR UPDATE",initial.get("BRANCH_CODE")):null;
  var till=db.one("SELECT * FROM MBX_TELLER_TILL WHERE TILL_ID=? FOR UPDATE",id);
  if(till.get("TELLER_USER_ID").equals(CurrentActor.get().userId()))throw fail("A distinct checker must close the till");
  if(!"OPEN".equals(till.get("STATUS"))||r.countedCash().compareTo((BigDecimal)till.get("CASH_BALANCE"))!=0)throw fail("Till must be open and counted cash must match recorded cash");
  if(allocated&&r.countedCash().signum()>0){
   long vaultGl=((Number)vault.get("VAULT_GL_ID")).longValue(),tillGl=((Number)till.get("CASH_GL_ID")).longValue();
   String actor=CurrentActor.get().userId(),requestKey="till-return:"+id;
   LocalDate date=LocalDate.now(ZoneId.of("Asia/Kolkata"));
   var key=new GeneratedKeyHolder();
   db.jdbc().update(con->{var ps=con.prepareStatement("INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,AMOUNT,VALUE_DATE) VALUES (?,'TELLER',?,?,?,'ADJUSTMENT','VALIDATED',?,?)",new String[]{"TXN_ID"});Object[] values={actor,requestKey,CustomerHashService.digest(requestKey),id,r.countedCash(),date};for(int i=0;i<values.length;i++)ps.setObject(i+1,values[i]);return ps;},key);
   long txn=Objects.requireNonNull(key.getKey()).longValue();
   var journal=ledger.postJournal(new JournalRequest(requestKey,"ADJUSTMENT",txn,null,null,null,null,null,date,"Teller cash returned to vault",List.of(
    new JournalLine(vaultGl,null,null,null,"DR",r.countedCash(),"Cash returned to vault "+till.get("BRANCH_CODE")),
    new JournalLine(tillGl,null,null,null,"CR",r.countedCash(),"Cash returned from till "+id))),actor);
   markAdjustmentPosted(txn,actor,"TILL_CLOSED");
   db.jdbc().update("UPDATE MBX_BRANCH_VAULT SET CASH_BALANCE=CASH_BALANCE+?,UPDATED_AT=SYSTIMESTAMP WHERE BRANCH_CODE=?",r.countedCash(),till.get("BRANCH_CODE"));
   db.jdbc().update("INSERT INTO MBX_TILL_CASH_RETURN(RETURN_ID,TILL_ID,BRANCH_CODE,AMOUNT,JOURNAL_ID,CHECKED_BY) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),id,till.get("BRANCH_CODE"),r.countedCash(),journal.journalId(),actor);
  }
  db.jdbc().update("UPDATE MBX_TELLER_TILL SET STATUS='CLOSED',CLOSED_BY=?,CASH_BALANCE=CASE WHEN ?=1 THEN 0 ELSE CASH_BALANCE END WHERE TILL_ID=?",CurrentActor.get().userId(),allocated?1:0,id);
  audit.audit("TILL_CLOSED","TILL",id,r.reason());return Map.of("tillId",id,"status","CLOSED");
 }
 private void markAdjustmentPosted(long txn,String actor,String reason){
  db.jdbc().update("UPDATE M05_TXN_TRANSACTION_LOG SET STATUS='POSTED' WHERE TXN_ID=?",txn);
  db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,NULL,'RECEIVED',?,?)",txn,actor,reason);
  db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,'RECEIVED','VALIDATED',?,?)",txn,actor,reason);
  db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,'VALIDATED','POSTED',?,?)",txn,actor,reason);
 }
 private BusinessException fail(String message){return new BusinessException(HttpStatus.CONFLICT,"CASH_CONTROL",message);}
}
