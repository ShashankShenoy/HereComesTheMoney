package com.moneybags.integration;

import com.moneybags.common.database.BusinessRepository;
import com.moneybags.common.api.BusinessException;
import com.moneybags.payments.core.*;
import com.moneybags.payments.api.Contracts.*;
import com.moneybags.txn.core.LedgerService;
import com.moneybags.txn.api.Contracts.*;
import com.moneybags.treasury.service.TreasuryService;
import com.moneybags.treasury.api.TreasuryRequests.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Explicit simulation boundary. Never contacts a payment network or represents real rail evidence. */
@RestController @RequestMapping("/api/v1/payments")
public class PaymentWorkflowController {
 private final BusinessRepository db;private final BankingAccess access;private final PaymentService payments;private final LedgerService ledger;private final TreasuryService treasury;private final DispatchService dispatch;private final boolean simulation;
 public PaymentWorkflowController(BusinessRepository db,BankingAccess access,PaymentService payments,LedgerService ledger,TreasuryService treasury,DispatchService dispatch,@Value("${moneybags.payment-simulation.enabled:false}")boolean simulation){this.db=db;this.access=access;this.payments=payments;this.ledger=ledger;this.treasury=treasury;this.dispatch=dispatch;this.simulation=simulation;}
 public record Initiate(@NotNull Long sourceAccountId,@NotBlank String beneficiaryId,@NotBlank @Pattern(regexp="UPI|IMPS|NEFT|RTGS")String railCode,@NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2)BigDecimal amount,@NotBlank @Size(max=100)String requestKey){}
 public record Authorize(@NotNull Long reserveAccountId){}
 public record Outcome(@NotBlank @Pattern(regexp="ACCEPTED|UNKNOWN|REJECTED|SETTLED|REFUNDED")String outcome){}
 @PostMapping("/initiate") @Transactional public Payment initiate(@Valid @RequestBody Initiate r){
  var a=access.account("PAYMENT_CREATE",r.sourceAccountId());var beneficiary=db.one("SELECT * FROM MBX_BENEFICIARY WHERE BENEFICIARY_ID=? AND OWNER_USER_ID=? AND STATUS='ACTIVE'",r.beneficiaryId(),CurrentActor.get().userId());
  return payments.create(new CreatePayment(CurrentActor.get().userId(),"WEB",r.requestKey(),"payment:"+r.requestKey(),"MB-"+r.requestKey(),"OUTBOUND","PAYMENT",null,r.sourceAccountId(),(String)a.get("PRIMARY_CIF_ID"),null,null,(String)beneficiary.get("ACCOUNT_TOKEN"),(String)beneficiary.get("BANK_CODE"),r.railCode(),r.amount(),today()));
 }
 @PostMapping("/{id}/authorize-simulation") @Transactional public Payment authorize(@PathVariable long id,@Valid @RequestBody Authorize r){
  enabled();var row=db.one("SELECT * FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=? FOR UPDATE",id);long account=((Number)row.get("SOURCE_ACCOUNT_ID")).longValue();
  var a=access.account("PAYMENT_APPROVE",account);String actor=CurrentActor.get().userId();
  if(actor.equals(row.get("ORIGINATOR_ID")))throw fail("A distinct checker must authorize the payment");
  Payment p=payments.get(id);if(!p.status().equals("RECEIVED"))return p;
  if(p.railCode().equals("RTGS")&&p.amount().compareTo(new BigDecimal("200000"))<0)throw fail("Simulation RTGS minimum is INR 200000");
  if(Set.of("UPI","IMPS").contains(p.railCode())&&p.amount().compareTo(new BigDecimal("100000"))>0)throw fail("Simulation instant-rail limit is INR 100000");
  long customerGl=gl(((Number)a.get("PRODUCT_VERSION_ID")).longValue(),"CUSTOMER_LIABILITY"),suspenseGl=gl(((Number)a.get("PRODUCT_VERSION_ID")).longValue(),"SETTLEMENT_SUSPENSE");
  p=step(p,"AUTHORIZED",null,null,null,null,null);
  var liquidity=treasury.reserve(new CreateLiquidityHold("sim-payment:"+id,r.reserveAccountId(),p.railCode(),id,null,p.amount(),OffsetDateTime.now().plusHours(24)),actor);
  var hold=ledger.placeHold(new HoldRequest(account,id,p.amount(),"sim-payment:"+id,OffsetDateTime.now().plusHours(24)),actor);
  p=step(p,"HELD",null,null,null,hold.holdId(),liquidity.id());
  var journal=ledger.postJournal(new JournalRequest("payment-debit:"+id,"PAYMENT_PROVISIONAL",null,id,null,null,hold.holdId(),null,today(),"Simulated outbound payment",List.of(new JournalLine(customerGl,account,null,null,"DR",p.amount(),"Payment debit"),new JournalLine(suspenseGl,null,null,null,"CR",p.amount(),"Payment suspense"))),actor);
  p=step(p,"DEBIT_POSTED",null,journal.journalId(),null,null,null);
  var work=dispatch.prepare(id,new PrepareDispatch("SIM-"+id,"simulation://payment/"+id));
  long dispatchId=((Number)work.get("DISPATCH_ID")).longValue();
  dispatch.attempt(dispatchId,new DispatchAttempt("SEND","SENT","SIM-"+id,null));
  treasury.decideHold(liquidity.id(),new HoldDecision("COMMIT",null,liquidity.version()),actor);
  return step(p,"DISPATCHED",null,journal.journalId(),null,null,null);
 }
 @PostMapping("/{id}/simulate-outcome") @Transactional public Payment outcome(@PathVariable long id,@Valid @RequestBody Outcome r){
  enabled();var row=db.one("SELECT * FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=? FOR UPDATE",id);long account=((Number)row.get("SOURCE_ACCOUNT_ID")).longValue();var a=access.account("PAYMENT_OPERATE",account);
  Payment p=payments.get(id);if(p.status().equals(r.outcome()))return p;
  String actor=CurrentActor.get().userId();long evidence=payments.evidence(id,new RailEvidence("SIM-"+id,"SIM-"+id+"-"+r.outcome(),r.outcome().equals("REFUNDED")?"RETURN":"CALLBACK",r.outcome().equals("REFUNDED")?"RETURNED":r.outcome(),"SIMULATED","simulation://payment/"+id,HexFormat.of().formatHex(CustomerHashService.digest(id+"|"+r.outcome())),OffsetDateTime.now()));
  Long journal=null,entry=null;
  long version=((Number)a.get("PRODUCT_VERSION_ID")).longValue(),suspense=gl(version,"SETTLEMENT_SUSPENSE");
  if(r.outcome().equals("SETTLED")){
   var hold=db.one("SELECT RESERVE_ACCOUNT_ID FROM M07_TREASURY_LIQUIDITY_HOLD WHERE LIQUIDITY_HOLD_ID=? AND STATUS='COMMITTED'",p.liquidityHoldId());long reserveId=((Number)hold.get("RESERVE_ACCOUNT_ID")).longValue();
   var reserve=db.one("SELECT GL_ACCOUNT_ID FROM M07_RESERVE_ACCOUNT WHERE RESERVE_ACCOUNT_ID=?",reserveId);long reserveGl=((Number)reserve.get("GL_ACCOUNT_ID")).longValue();
   var e=treasury.recordEvidence(new SettlementEvidenceCommand(reserveId,id,null,p.railCode(),"RAIL_CONFIRMATION","VERIFIED","OUT",p.amount(),"SIM-SETTLE-"+id,null,"{\"simulation\":true,\"paymentId\":"+id+"}","simulation://settlement/"+id,OffsetDateTime.now()),actor);
   journal=ledger.postJournal(new JournalRequest("payment-settle:"+id,"SETTLEMENT",null,id,null,null,null,null,today(),"Simulated settlement",List.of(new JournalLine(suspense,null,null,null,"DR",p.amount(),"Clear suspense"),new JournalLine(reserveGl,null,null,null,"CR",p.amount(),"Reserve settlement"))),actor).journalId();
   entry=treasury.confirmMovement(new ConfirmMovement(e.id(),journal),actor).id();
  }else if(r.outcome().equals("REFUNDED")){
   if(!Set.of("REJECTED","UNKNOWN").contains(p.status()))throw fail("Refund requires a rejected or investigated unknown payment");
   journal=ledger.postJournal(new JournalRequest("payment-refund:"+id,"PAYMENT_REFUND",null,id,null,null,null,null,today(),"Simulated payment refund",List.of(new JournalLine(suspense,null,null,null,"DR",p.amount(),"Release suspense"),new JournalLine(gl(version,"CUSTOMER_LIABILITY"),account,null,null,"CR",p.amount(),"Payment refund"))),actor).journalId();
  }
  p=step(p,r.outcome(),evidence,journal,entry,null,null);
  if(r.outcome().equals("REFUNDED")){
   db.jdbc().update("UPDATE M07_TREASURY_LIQUIDITY_HOLD SET STATUS='RELEASED',RELEASE_REASON='SIM_REFUNDED',CLOSED_AT=SYSTIMESTAMP,COMMITTED_AT=NULL,VERSION_NO=VERSION_NO+1 WHERE LIQUIDITY_HOLD_ID=? AND STATUS='COMMITTED'",p.liquidityHoldId());
   db.jdbc().update("UPDATE M07_RESERVE_POSITION SET ACTIVE_HOLD_AMOUNT=ACTIVE_HOLD_AMOUNT-?,VERSION_NO=VERSION_NO+1,AS_OF=SYSTIMESTAMP WHERE RESERVE_ACCOUNT_ID=(SELECT RESERVE_ACCOUNT_ID FROM M07_TREASURY_LIQUIDITY_HOLD WHERE LIQUIDITY_HOLD_ID=?)",p.amount(),p.liquidityHoldId());
  }
  return p;
 }
 private Payment step(Payment p,String status,Long evidence,Long journal,Long entry,Long hold,Long liquidity){return payments.transition(p.paymentId(),new Transition(status,p.version(),evidence,journal,entry,hold,liquidity,"SIMULATED"));}
 private long gl(long version,String role){return ((Number)db.one("SELECT GL_ACCOUNT_ID FROM M05_GL_PRODUCT_MAPPING WHERE PRODUCT_VERSION_ID=? AND POSTING_TYPE='PAYMENT' AND GL_ROLE_CODE=? AND STATUS='ACTIVE' AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>=SYSDATE)",version,role).get("GL_ACCOUNT_ID")).longValue();}
 private void enabled(){if(!simulation)throw new BusinessException(HttpStatus.CONFLICT,"SIMULATION_DISABLED","Payment simulation is disabled in this deployment");}
 private static LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Kolkata"));}
 private BusinessException fail(String m){return new BusinessException(HttpStatus.CONFLICT,"PAYMENT_CONTROL",m);}
}
