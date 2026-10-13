package com.moneybags.integration;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.txn.core.LedgerService;
import com.moneybags.txn.api.Contracts.ControlRequest;
import com.moneybags.account.domain.InboundService;
import com.moneybags.account.api.Models.ControlAck;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.*;
@Service
public class AccountControlBridge {
 private final BusinessRepository db;private final LedgerService ledger;private final InboundService inbound;
 public AccountControlBridge(BusinessRepository db,LedgerService ledger,InboundService inbound){this.db=db;this.ledger=ledger;this.inbound=inbound;}
 @Transactional public void apply(long accountId){
  var account=db.one("SELECT LIFECYCLE_STATUS FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=? FOR UPDATE",accountId);
  for(var sync:db.rows("SELECT * FROM M04_ACCOUNT_CONTROL_SYNC WHERE ACCOUNT_ID=? AND SYNC_STATUS='PENDING' ORDER BY CONTROL_VERSION",accountId)){
   boolean closed=Set.of("CLOSING","CLOSED","CANCELLED","DORMANT").contains(account.get("LIFECYCLE_STATUS"));
   String debit=closed?"CLOSED":"OPEN",credit=closed?"CLOSED":"OPEN";
   BigDecimal liens=BigDecimal.ZERO,blocks=BigDecimal.ZERO;
   for(var r:db.rows("SELECT RESTRICTION_TYPE,RESTRICTION_AMOUNT FROM M04_ACCOUNT_RESTRICTION WHERE ACCOUNT_ID=? AND RESTRICTION_STATUS IN ('ACTIVE','PENDING_APPLY')",accountId)){
    switch((String)r.get("RESTRICTION_TYPE")){
    case "FREEZE" -> {debit="CLOSED";credit="CLOSED";}
    case "DEBIT_BLOCK" -> debit="CLOSED";
    case "CREDIT_BLOCK" -> credit="CLOSED";
    case "LIEN" -> liens=liens.add((BigDecimal)r.get("RESTRICTION_AMOUNT"));
    case "PARTIAL_BLOCK" -> blocks=blocks.add((BigDecimal)r.get("RESTRICTION_AMOUNT"));
    default -> {}
    }
   }
   byte[] raw=(byte[])sync.get("SOURCE_EVENT_ID");ByteBuffer b=ByteBuffer.wrap(raw);UUID event=new UUID(b.getLong(),b.getLong());
   long version=((Number)sync.get("CONTROL_VERSION")).longValue();
   var pos=db.rows("SELECT OVERDRAFT_LIMIT FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=?",accountId);
   BigDecimal overdraft=pos.isEmpty()?BigDecimal.ZERO:(BigDecimal)pos.get(0).get("OVERDRAFT_LIMIT");
   ledger.applyControl(accountId,new ControlRequest(event,version,debit,credit,liens,blocks,overdraft,"ACCOUNT_CONTROL"),CurrentActor.get().userId());
   inbound.localControlAck(new ControlAck(UUID.randomUUID().toString(),accountId,version,event.toString(),"ACKNOWLEDGED",version,null));
  }
 }
}
