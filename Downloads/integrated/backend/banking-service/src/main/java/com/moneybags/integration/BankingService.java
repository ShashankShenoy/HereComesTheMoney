package com.moneybags.integration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.common.api.BusinessException;
import com.moneybags.txn.core.LedgerService;
import com.moneybags.txn.api.Contracts.*;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
@Service
public class BankingService {
 private final BusinessRepository db;private final BankingAccess access;private final LedgerService ledger;
 private final Set<String> internalBankCodes;
 public BankingService(BusinessRepository db,BankingAccess access,LedgerService ledger){this(db,access,ledger,"");}
 @org.springframework.beans.factory.annotation.Autowired public BankingService(BusinessRepository db,BankingAccess access,LedgerService ledger,@org.springframework.beans.factory.annotation.Value("${moneybags.internal-bank-codes:}") String bankCodes){this.db=db;this.access=access;this.ledger=ledger;this.internalBankCodes=Arrays.stream(bankCodes.split(",")).map(String::trim).filter(s->!s.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());}
 public record BeneficiaryTransferRequest(@jakarta.validation.constraints.NotNull Long sourceAccountId,
  @jakarta.validation.constraints.NotBlank String beneficiaryId,
  @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0.01") @jakarta.validation.constraints.Digits(integer=16,fraction=2) BigDecimal amount,
  @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=100) String requestKey,
  @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=100) String correlationId){}
 /** Resolve the customer's verified beneficiary on the server before using the normal ledger workflow. */
 @Transactional public TransactionView transferByBeneficiary(BeneficiaryTransferRequest r){
  access.account("TXN_POST",r.sourceAccountId());
  var b=db.one("SELECT ACCOUNT_TOKEN,BANK_CODE FROM MBX_BENEFICIARY WHERE BENEFICIARY_ID=? AND OWNER_USER_ID=? AND STATUS='ACTIVE'",r.beneficiaryId(),CurrentActor.get().userId());
  if(!internalBankCodes.contains(b.get("BANK_CODE")))
   throw conflict("This recipient belongs to an external bank");
  long target=((Number)db.one("SELECT ACCOUNT_ID FROM M04_BANK_ACCOUNT WHERE ACCOUNT_NUMBER=?",b.get("ACCOUNT_TOKEN")).get("ACCOUNT_ID")).longValue();
  return transfer(new TransferRequest(r.sourceAccountId(),target,r.amount(),"WEB",r.requestKey(),r.correlationId(),LocalDate.now(ZoneId.of("Asia/Kolkata"))));
 }
 /** Looks up only account IDs; recipient financial details are never returned to the sender. */
 @Transactional public TransactionView transferByNumber(NumberTransferRequest r){
  long source=((Number)db.one("SELECT ACCOUNT_ID FROM M04_BANK_ACCOUNT WHERE ACCOUNT_NUMBER=?",r.sourceAccountNumber().trim()).get("ACCOUNT_ID")).longValue();
  access.account("TXN_POST",source);
  long target=((Number)db.one("SELECT ACCOUNT_ID FROM M04_BANK_ACCOUNT WHERE ACCOUNT_NUMBER=?",r.targetAccountNumber().trim()).get("ACCOUNT_ID")).longValue();
  return transfer(new TransferRequest(source,target,r.amount(),"WEB",r.requestKey(),r.correlationId(),LocalDate.now(ZoneId.of("Asia/Kolkata"))));
 }
 @Transactional public TransactionView transfer(TransferRequest r){
  access.account("TXN_POST",r.sourceAccountId());
  if(!r.valueDate().equals(LocalDate.now(ZoneId.of("Asia/Kolkata"))))throw conflict("Immediate transfers must use the current business date");
  // Serialize velocity and funds decisions on the same account row across channels.
  var lockedAccount=db.one("SELECT PRODUCT_VERSION_ID FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=? FOR UPDATE",r.sourceAccountId());
  long productVersionId=((Number)lockedAccount.get("PRODUCT_VERSION_ID")).longValue();
  if(db.count("SELECT COUNT(*) FROM M05_TXN_TRANSACTION_LOG WHERE ORIGINATOR_ID=? AND CHANNEL_CODE=? AND REQUEST_KEY=?",CurrentActor.get().userId(),r.channelCode(),r.requestKey())==0){
   for(var limit:db.rows("SELECT PERIOD_CODE,MIN_AMOUNT,MAX_AMOUNT,MAX_COUNT FROM M03_PM_LIMIT_RULE WHERE PRODUCT_VERSION_ID=? AND OPERATION_CODE='INTERNAL_TRANSFER' AND (CURRENCY_CODE IS NULL OR CURRENCY_CODE='INR')",productVersionId)){
    String period=(String)limit.get("PERIOD_CODE");BigDecimal used=BigDecimal.ZERO;long count=0;
    if(Set.of("DAY","DAILY").contains(period)){
     var usage=db.one("SELECT COALESCE(SUM(AMOUNT),0) AMOUNT,COUNT(*) N FROM M05_TXN_TRANSACTION_LOG WHERE SOURCE_ACCOUNT_ID=? AND VALUE_DATE=? AND TXN_TYPE='INTERNAL_TRANSFER' AND TARGET_ACCOUNT_ID IS NOT NULL AND STATUS IN ('POSTED','REVERSED')",r.sourceAccountId(),r.valueDate());
     used=(BigDecimal)usage.get("AMOUNT");count=((Number)usage.get("N")).longValue();
    } else if(!Set.of("TRANSACTION","PER_TRANSACTION","SINGLE").contains(period))throw conflict("Unsupported product limit period; policy review required");
    if(limit.get("MIN_AMOUNT") instanceof BigDecimal min && r.amount().compareTo(min)<0)throw conflict("Amount is below the product limit");
    if(limit.get("MAX_AMOUNT") instanceof BigDecimal max && used.add(r.amount()).compareTo(max)>0)throw conflict("Product amount limit exceeded");
    if(limit.get("MAX_COUNT") instanceof Number max && count>=max.longValue())throw conflict("Product transaction velocity exceeded");
   }
   for(var limit:db.rows("SELECT LIMIT_AMOUNT,PERIOD_CODE FROM M04_ACCOUNT_LIMIT WHERE ACCOUNT_ID=? AND OPERATION_CODE='INTERNAL_TRANSFER' AND IS_ACTIVE='Y' AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>SYSDATE)",r.sourceAccountId())){
    BigDecimal used=BigDecimal.ZERO;
    if(Set.of("DAY","DAILY").contains(limit.get("PERIOD_CODE")))used=(BigDecimal)db.one("SELECT COALESCE(SUM(AMOUNT),0) AMOUNT FROM M05_TXN_TRANSACTION_LOG WHERE SOURCE_ACCOUNT_ID=? AND VALUE_DATE=? AND TXN_TYPE='INTERNAL_TRANSFER' AND TARGET_ACCOUNT_ID IS NOT NULL AND STATUS IN ('POSTED','REVERSED')",r.sourceAccountId(),r.valueDate()).get("AMOUNT");
    else if(!Set.of("TRANSACTION","PER_TRANSACTION","SINGLE").contains(limit.get("PERIOD_CODE")))throw conflict("Unsupported account limit period");
    if(used.add(r.amount()).compareTo((BigDecimal)limit.get("LIMIT_AMOUNT"))>0)throw conflict("Account amount limit exceeded");
   }
  }
  return ledger.transfer(r,CurrentActor.get().userId());
 }
 public BusinessException conflict(String message){return new BusinessException(HttpStatus.CONFLICT,"BANKING_RULE",message);}
}
