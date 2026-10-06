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
 public BankingService(BusinessRepository db,BankingAccess access,LedgerService ledger){this.db=db;this.access=access;this.ledger=ledger;}
 @Transactional public TransactionView transfer(TransferRequest r){
  var a=access.account("TXN_POST",r.sourceAccountId());
  if(!r.valueDate().equals(LocalDate.now(ZoneId.of("Asia/Kolkata"))))throw conflict("Immediate transfers must use the current business date");
  // Serialize velocity and funds decisions on the same account row across channels.
  db.rows("SELECT ACCOUNT_ID FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=? FOR UPDATE",r.sourceAccountId());
  if(db.count("SELECT COUNT(*) FROM M05_TXN_TRANSACTION_LOG WHERE ORIGINATOR_ID=? AND CHANNEL_CODE=? AND REQUEST_KEY=?",CurrentActor.get().userId(),r.channelCode(),r.requestKey())==0){
   for(var limit:db.rows("SELECT PERIOD_CODE,MIN_AMOUNT,MAX_AMOUNT,MAX_COUNT FROM M03_PM_LIMIT_RULE WHERE PRODUCT_VERSION_ID=? AND OPERATION_CODE='INTERNAL_TRANSFER'",a.get("PRODUCT_VERSION_ID"))){
    String period=(String)limit.get("PERIOD_CODE");BigDecimal used=BigDecimal.ZERO;long count=0;
    if(Set.of("DAY","DAILY").contains(period)){
     var usage=db.one("SELECT COALESCE(SUM(AMOUNT),0) AMOUNT,COUNT(*) N FROM M05_TXN_TRANSACTION_LOG WHERE SOURCE_ACCOUNT_ID=? AND VALUE_DATE=? AND TXN_TYPE='INTERNAL_TRANSFER' AND STATUS IN ('POSTED','REVERSED')",r.sourceAccountId(),r.valueDate());
     used=(BigDecimal)usage.get("AMOUNT");count=((Number)usage.get("N")).longValue();
    } else if(!Set.of("TRANSACTION","PER_TRANSACTION","SINGLE").contains(period))throw conflict("Unsupported product limit period; policy review required");
    if(limit.get("MIN_AMOUNT") instanceof BigDecimal min && r.amount().compareTo(min)<0)throw conflict("Amount is below the product limit");
    if(limit.get("MAX_AMOUNT") instanceof BigDecimal max && used.add(r.amount()).compareTo(max)>0)throw conflict("Product amount limit exceeded");
    if(limit.get("MAX_COUNT") instanceof Number max && count>=max.longValue())throw conflict("Product transaction velocity exceeded");
   }
   for(var limit:db.rows("SELECT LIMIT_AMOUNT,PERIOD_CODE FROM M04_ACCOUNT_LIMIT WHERE ACCOUNT_ID=? AND OPERATION_CODE='INTERNAL_TRANSFER' AND IS_ACTIVE='Y' AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>SYSDATE)",r.sourceAccountId())){
    BigDecimal used=BigDecimal.ZERO;
    if(Set.of("DAY","DAILY").contains(limit.get("PERIOD_CODE")))used=(BigDecimal)db.one("SELECT COALESCE(SUM(AMOUNT),0) AMOUNT FROM M05_TXN_TRANSACTION_LOG WHERE SOURCE_ACCOUNT_ID=? AND VALUE_DATE=? AND TXN_TYPE='INTERNAL_TRANSFER' AND STATUS IN ('POSTED','REVERSED')",r.sourceAccountId(),r.valueDate()).get("AMOUNT");
    else if(!Set.of("TRANSACTION","PER_TRANSACTION","SINGLE").contains(limit.get("PERIOD_CODE")))throw conflict("Unsupported account limit period");
    if(used.add(r.amount()).compareTo((BigDecimal)limit.get("LIMIT_AMOUNT"))>0)throw conflict("Account amount limit exceeded");
   }
  }
  return ledger.transfer(r,CurrentActor.get().userId());
 }
 public BusinessException conflict(String message){return new BusinessException(HttpStatus.CONFLICT,"BANKING_RULE",message);}
}
