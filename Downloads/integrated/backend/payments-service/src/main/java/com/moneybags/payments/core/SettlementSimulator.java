package com.moneybags.payments.core;

import com.moneybags.common.api.BusinessException;
import com.moneybags.integration.*;
import com.moneybags.payments.api.Contracts.*;
import com.moneybags.txn.core.LedgerService;
import com.moneybags.txn.api.Contracts.*;
import com.moneybags.treasury.service.TreasuryService;
import com.moneybags.treasury.api.TreasuryRequests.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Types;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A durable, atomic simulation boundary. External reserves are never bank GL assets. */
@Service
public class SettlementSimulator {
    private final JdbcTemplate db;
    private final PaymentService payments;
    private final PaymentRepository repository;
    private final DispatchService dispatch;
    private final OperationsService batches;
    private final LedgerService ledger;
    private final TreasuryService treasury;
    private final IntegrationChecks checks;
    private final BankingAccess access;
    private final Clock clock;
    private final boolean enabled;
    public SettlementSimulator(JdbcTemplate db, PaymentService payments, PaymentRepository repository,
            DispatchService dispatch, OperationsService batches, LedgerService ledger, TreasuryService treasury,
            IntegrationChecks checks, BankingAccess access, Clock clock,
            @Value("${moneybags.payment-simulation.enabled:false}") boolean enabled) {
        this.db=db;this.payments=payments;this.repository=repository;this.dispatch=dispatch;this.batches=batches;
        this.ledger=ledger;this.treasury=treasury;this.checks=checks;this.access=access;this.clock=clock;this.enabled=enabled;
    }
    public boolean enabled() { return enabled; }
    /** All simulation writers take this DB lock first, including the scheduled worker. */
    public void lock() {
        if (!enabled) throw fail("SIMULATION_DISABLED","Payment simulation is disabled");
        db.queryForMap("SELECT LOCK_ID FROM MBX_SIM_LOCK WHERE LOCK_ID=1 FOR UPDATE");
    }
    public void requireAdmin() {
        var actor=CurrentActor.get();
        if (!"EMPLOYEE".equals(actor.userType()) || !actor.roles().contains("BANK_ADMIN"))
            throw new BusinessException(HttpStatus.FORBIDDEN,"ADMIN_ONLY","Bank administrator access required");
        access.global("PAYMENT_OPERATE");
    }
    public void requireRead() {
        var actor=CurrentActor.get();
        if (!"EMPLOYEE".equals(actor.userType()) || !(actor.roles().contains("BANK_ADMIN") || actor.permissions().contains("PAYMENT_APPROVE")))
            throw new BusinessException(HttpStatus.FORBIDDEN,"STAFF_ONLY","Simulation portal requires administrator or checker access");
        access.global("PAYMENT_READ");
    }
    /** Reuses the bank reserve, without copying or resetting its balance. */
    public long ownReserve() {
        var prior=db.queryForList("SELECT RESERVE_ACCOUNT_ID FROM MBX_SIM_BANK WHERE BANK_CODE='MCP'");
        if (!prior.isEmpty()) {
            long id=number(prior.get(0),"RESERVE_ACCOUNT_ID");
            if(db.queryForObject("SELECT COUNT(*) FROM M07_RESERVE_ACCOUNT WHERE RESERVE_ACCOUNT_ID=? AND RESERVE_ACCOUNT_CODE='MCP-RBI-CURRENT' AND ACCOUNT_STATUS='ACTIVE'",Integer.class,id)!=1)
                throw fail("ROUTING_RESERVE_MISMATCH","Our simulator mapping must use the active MCP-RBI-CURRENT reserve");
            ensureOwnRoute();return id;
        }
        var reserves=db.queryForList("SELECT RESERVE_ACCOUNT_ID FROM M07_RESERVE_ACCOUNT WHERE RESERVE_ACCOUNT_CODE='MCP-RBI-CURRENT' AND ACCOUNT_STATUS='ACTIVE'");
        if (reserves.size()!=1) throw fail("RESERVE_CONFIGURATION","Configure the existing MCP-RBI-CURRENT reserve account");
        long id=number(reserves.get(0),"RESERVE_ACCOUNT_ID");
        db.update("INSERT INTO MBX_SIM_BANK(BANK_CODE,BANK_NAME,RESERVE_ACCOUNT_ID) VALUES ('MCP','Moneybags RBI current reserve',?)",id);
        ensureOwnRoute();
        return id;
    }
    private void ensureOwnRoute() {
        var route=db.queryForList("SELECT BANK_CODE FROM MBX_SIM_ROUTE WHERE IFSC_PREFIX='MCPB'");
        if(route.isEmpty())db.update("INSERT INTO MBX_SIM_ROUTE(IFSC_PREFIX,BANK_CODE) VALUES ('MCPB','MCP')");
        else if(!"MCP".equals(route.get(0).get("BANK_CODE")))throw fail("ROUTING_MISMATCH","Our IFSC prefix is mapped to a different bank");
    }
    public String route(String ifsc) {
        if (ifsc==null || !ifsc.matches("[A-Z]{4}0[A-Z0-9]{6}")) throw fail("UNKNOWN_ROUTING","Use a valid IFSC for a participating external bank");
        var rows=db.queryForList("SELECT BANK_CODE FROM MBX_SIM_ROUTE WHERE IFSC_PREFIX=?",ifsc.substring(0,4));
        if (rows.size()!=1) throw fail("UNKNOWN_ROUTING","Bank routing is not registered in the simulator");
        return (String)rows.get(0).get("BANK_CODE");
    }
    @Transactional
    public Payment registerOutgoing(long id) {
        lock(); long reserve=ownReserve(); Payment p=repository.lock(id);
        if (db.queryForList("SELECT PAYMENT_ID FROM MBX_SIM_PAYMENT WHERE PAYMENT_ID=?",id).isEmpty()) {
            var row=db.queryForMap("SELECT BENEFICIARY_BANK_CODE FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",id);
            String bank=route((String)row.get("BENEFICIARY_BANK_CODE"));
            if(bank.equals("MCP"))throw fail("EXTERNAL_ROUTING_REQUIRED","Use the internal transfer workflow for this bank's beneficiaries");
            db.update("INSERT INTO MBX_SIM_PAYMENT(PAYMENT_ID,FROM_BANK,TO_BANK,RESERVE_ACCOUNT_ID) VALUES (?,'MCP',?,?)",id,bank,reserve);
        }
        if (!p.status().equals("RECEIVED")) return p;
        validateOutgoing(p);
        if (p.status().equals("RECEIVED") && !SimulationPolicy.approvalRequired(p.railCode(),p.amount())) return processOutgoing(p,"SYSTEM_AUTO");
        return p;
    }
    private void validateOutgoing(Payment p) {
        SimulationPolicy.validate(p.railCode(),p.amount());
        var row=db.queryForMap("SELECT SOURCE_ACCOUNT_ID,SOURCE_CIF_ID,ORIGINATOR_ID,BENEFICIARY_TOKEN_REF,BENEFICIARY_BANK_CODE,CHANNEL_CODE FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",p.paymentId());
        checks.authorize(p,(String)row.get("CHANNEL_CODE"),number(row,"SOURCE_ACCOUNT_ID"),(String)row.get("SOURCE_CIF_ID"));
        if (db.queryForObject("SELECT COUNT(*) FROM MBX_BENEFICIARY WHERE OWNER_USER_ID=? AND ACCOUNT_TOKEN=? AND BANK_CODE=? AND STATUS='ACTIVE'",Integer.class,row.get("ORIGINATOR_ID"),row.get("BENEFICIARY_TOKEN_REF"),row.get("BENEFICIARY_BANK_CODE"))!=1)
            throw fail("BENEFICIARY_UNVERIFIED","Beneficiary is no longer independently verified");
        route((String)row.get("BENEFICIARY_BANK_CODE"));
        if (p.status().equals("RECEIVED") && ledger.position(number(row,"SOURCE_ACCOUNT_ID")).spendable().compareTo(p.amount())<0)
            throw fail("INSUFFICIENT_FUNDS","Insufficient spendable customer funds");
        if (p.status().equals("RECEIVED")) {
            long reserve=ownReserve();
            var pos=db.queryForMap("SELECT AVAILABLE_BALANCE FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=? FOR UPDATE",reserve);
            BigDecimal buffer=db.queryForObject("SELECT SAFETY_BUFFER_AMOUNT FROM M07_RESERVE_ACCOUNT WHERE RESERVE_ACCOUNT_ID=?",BigDecimal.class,reserve);
            if (((BigDecimal)pos.get("AVAILABLE_BALANCE")).subtract(buffer).compareTo(p.amount())<0)
                throw fail("INSUFFICIENT_RESERVE_LIQUIDITY","Insufficient reserve liquidity after safety buffer");
        }
    }
    @Transactional
    public Payment approve(long id,long reserve) {
        lock(); if (reserve!=ownReserve()) throw fail("ROUTING_RESERVE_MISMATCH","Select the reserve mapped to our bank");
        Payment p=repository.lock(id);
        var row=db.queryForMap("SELECT SOURCE_ACCOUNT_ID,ORIGINATOR_ID FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",id);
        access.account("PAYMENT_APPROVE",number(row,"SOURCE_ACCOUNT_ID"));
        if (CurrentActor.get().userId().equals(row.get("ORIGINATOR_ID"))) throw fail("DISTINCT_CHECKER","A distinct checker must approve this payment");
        if (p.status().equals("RECEIVED")) {
            if (db.queryForList("SELECT PAYMENT_ID FROM MBX_SIM_PAYMENT WHERE PAYMENT_ID=?",id).isEmpty()) registerPending(p);
            validateOutgoing(p);
            return processOutgoing(p,CurrentActor.get().userId());
        }
        return p;
    }
    private void registerPending(Payment p) {
        var row=db.queryForMap("SELECT BENEFICIARY_BANK_CODE FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",p.paymentId());
        db.update("INSERT INTO MBX_SIM_PAYMENT(PAYMENT_ID,FROM_BANK,TO_BANK,RESERVE_ACCOUNT_ID) VALUES (?,'MCP',?,?)",p.paymentId(),route((String)row.get("BENEFICIARY_BANK_CODE")),ownReserve());
    }
    @Transactional
    public Payment reject(long id) {
        lock(); Payment p=repository.lock(id);
        var row=db.queryForMap("SELECT SOURCE_ACCOUNT_ID,ORIGINATOR_ID FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",id);
        access.account("PAYMENT_APPROVE",number(row,"SOURCE_ACCOUNT_ID"));
        if (CurrentActor.get().userId().equals(row.get("ORIGINATOR_ID"))) throw fail("DISTINCT_CHECKER","A distinct checker must reject this payment");
        if (p.status().equals("REJECTED")) return p;
        if (!p.status().equals("RECEIVED") || p.clearingBatchId()!=null) throw fail("PAYMENT_NOT_PENDING","Only unprocessed pending payments can be rejected");
        // No debit, liquidity reservation, dispatch or clearing membership exists at this point.
        return advance(p,"REJECTED",null,null,null,"CHECKER_REJECTED");
    }
    private Payment processOutgoing(Payment p,String actor) {
        long id=p.paymentId(),reserve=ownReserve();
        var account=db.queryForMap("SELECT A.ACCOUNT_ID,A.PRODUCT_VERSION_ID FROM M04_BANK_ACCOUNT A JOIN M06_PAYMENT_INSTRUCTION P ON P.SOURCE_ACCOUNT_ID=A.ACCOUNT_ID WHERE P.PAYMENT_ID=? FOR UPDATE",id);
        long accountId=number(account,"ACCOUNT_ID"),version=number(account,"PRODUCT_VERSION_ID");
        p=payments.transition(id,new Transition("AUTHORIZED",p.version(),null,null,null,null,null,"SIMULATED"));
        var liquidity=treasury.reserve(new CreateLiquidityHold("sim-payment:"+id,reserve,p.railCode(),id,null,p.amount(),now().plusDays(2)),actor);
        var hold=ledger.placeHold(new HoldRequest(accountId,id,p.amount(),"sim-payment:"+id,now().plusDays(2)),actor);
        p=payments.transition(id,new Transition("HELD",p.version(),null,null,null,hold.holdId(),liquidity.id(),"SIMULATED"));
        long journal=post("payment-debit:"+id,"PAYMENT_PROVISIONAL",id,null,List.of(
            line(gl(version,"CUSTOMER_LIABILITY"),accountId,"DR",p.amount()),line(gl(version,"SETTLEMENT_SUSPENSE"),null,"CR",p.amount())),actor);
        p=payments.transition(id,new Transition("DEBIT_POSTED",p.version(),null,journal,null,null,null,"SIMULATED"));
        long dispatchId=number(dispatch.prepare(id,new PrepareDispatch("SIM-"+id,"simulation://payment/"+id)),"DISPATCH_ID");
        dispatch.attempt(dispatchId,new DispatchAttempt("SEND","SENT","SIM-"+id,null));
        treasury.decideHold(liquidity.id(),new HoldDecision("COMMIT",null,liquidity.version()),actor);
        p=payments.transition(id,new Transition("DISPATCHED",p.version(),null,journal,null,null,null,"SIMULATED"));
        long evidence=evidence(p,"ACCEPTED");
        p=payments.transition(id,new Transition("ACCEPTED",p.version(),evidence,null,null,null,null,"SIMULATED"));
        db.update("UPDATE MBX_SIM_PAYMENT SET CUSTOMER_JOURNAL_ID=?,APPROVED_BY=? WHERE PAYMENT_ID=?",journal,actor,id);
        if (p.railCode().equals("RTGS")) settle(List.of(p),null,actor); else assign(p);
        return payments.get(id);
    }
    public record Incoming(@NotBlank @Pattern(regexp="HDFC|AXIS|SBI|ICICI") String sendingBank,
        @NotBlank @Size(max=20) String destinationAccountNumber, @Size(max=20) String destinationBranch,
        @Pattern(regexp="MCPB0000002") String destinationIfsc,
        @NotBlank @Pattern(regexp="UPI|IMPS|NEFT|RTGS") String railCode,
        @NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal amount,
        @NotBlank @Size(max=92) String externalReference,@Size(max=300) String senderDetails) {}
    @Transactional
    public Payment incoming(Incoming r) {
        requireAdmin();lock();SimulationPolicy.validate(r.railCode(),r.amount());
        ownReserve();
        if(r.destinationIfsc()!=null && (!r.destinationIfsc().equals("MCPB0000002") || !route(r.destinationIfsc()).equals("MCP")))throw fail("ROUTING_MISMATCH","Destination IFSC is not mapped to our bank");
        byte[] hash=hash(r.sendingBank()+"|"+r.destinationAccountNumber()+"|"+r.destinationBranch()+"|"+r.destinationIfsc()+"|"+r.railCode()+"|"+r.amount().stripTrailingZeros().toPlainString()+"|"+r.senderDetails());
        var prior=db.queryForList("SELECT PAYMENT_ID,REQUEST_HASH FROM MBX_SIM_PAYMENT WHERE EXTERNAL_REFERENCE=?",r.externalReference());
        if (!prior.isEmpty()) {
            if (!Arrays.equals((byte[])prior.get(0).get("REQUEST_HASH"),hash)) throw fail("DUPLICATE_REFERENCE","External reference was used with different details");
            return payments.get(number(prior.get(0),"PAYMENT_ID"));
        }
        var destinations=db.queryForList("SELECT ACCOUNT_ID,PRIMARY_CIF_ID,BRANCH_CODE,PRODUCT_VERSION_ID FROM M04_BANK_ACCOUNT WHERE ACCOUNT_NUMBER=? FOR UPDATE",r.destinationAccountNumber());
        if(destinations.size()!=1)throw fail("DESTINATION_ACCOUNT_NOT_FOUND","Destination account number does not identify an account at our bank");
        var account=destinations.get(0);
        if (r.destinationBranch()!=null && !r.destinationBranch().isBlank() && !r.destinationBranch().equals(account.get("BRANCH_CODE"))) throw fail("ROUTING_MISMATCH","Destination branch does not match the account");
        if (Set.of("NEFT","RTGS").contains(r.railCode()) && (r.destinationIfsc()==null || r.destinationBranch()==null || r.destinationBranch().isBlank())) throw fail("ROUTING_REQUIRED","NEFT/RTGS require destination branch and IFSC");
        var bank=db.queryForMap("SELECT BALANCE,HELD_AMOUNT FROM MBX_SIM_BANK WHERE BANK_CODE=? AND RESERVE_ACCOUNT_ID IS NULL FOR UPDATE",r.sendingBank());
        if (((BigDecimal)bank.get("BALANCE")).subtract((BigDecimal)bank.get("HELD_AMOUNT")).compareTo(r.amount())<0) throw fail("INSUFFICIENT_EXTERNAL_RESERVE","Sending bank has insufficient simulated reserve funds");
        String actor=CurrentActor.get().userId();
        var create=new CreatePayment(actor,"SIM_PORTAL",r.externalReference(),"incoming:"+r.externalReference(),"IN-"+r.externalReference(),"INBOUND","PAYMENT",null,null,null,number(account,"ACCOUNT_ID"),(String)account.get("PRIMARY_CIF_ID"),null,null,r.railCode(),r.amount(),today());
        long id=repository.insert(create,hash);repository.received(id,"INBOUND");repository.idempotency(create,hash,id);
        Payment p=payments.get(id); checks.authorize(p,"WEB",number(account,"ACCOUNT_ID"),(String)account.get("PRIMARY_CIF_ID"));
        update("INSERT INTO MBX_SIM_PAYMENT(PAYMENT_ID,EXTERNAL_REFERENCE,REQUEST_HASH,FROM_BANK,TO_BANK,RESERVE_ACCOUNT_ID,SENDER_DETAILS) VALUES (?,?,?,?,'MCP',?,?)",
            new Object[]{id,r.externalReference(),hash,r.sendingBank(),ownReserve(),r.senderDetails()},Types.NUMERIC,Types.VARCHAR,Types.BINARY,Types.VARCHAR,Types.NUMERIC,Types.VARCHAR);
        db.update("UPDATE MBX_SIM_BANK SET HELD_AMOUNT=HELD_AMOUNT+? WHERE BANK_CODE=?",r.amount(),r.sendingBank());
        p=advance(p,"AUTHORIZED",null,null,null,"SIMULATED_INCOMING");
        p=advance(p,"SETTLEMENT_PENDING",evidence(p,"PENDING"),null,null,"SIMULATED_INCOMING");
        if (SimulationPolicy.immediateCredit(p.railCode())) credit(p,actor);
        if (p.railCode().equals("RTGS")) settle(List.of(p),null,actor); else assign(p);
        return payments.get(id);
    }
    private void assign(Payment p) {
        var window=SimulationPolicy.window(p.railCode(),clock.instant());
        var cycles=db.queryForList("SELECT C.SETTLEMENT_CYCLE_ID,C.CLEARING_BATCH_ID FROM M07_SETTLEMENT_CYCLE C JOIN MBX_SIM_WINDOW W ON W.SETTLEMENT_CYCLE_ID=C.SETTLEMENT_CYCLE_ID WHERE C.RAIL_CODE=? AND C.STATUS='OPEN' AND W.WINDOW_START<=? AND W.WINDOW_END>? ORDER BY W.WINDOW_START DESC",p.railCode(),now(),now());
        long cycle,batch;
        if (cycles.isEmpty()) {
            String reference="SIM-"+p.railCode()+"-"+UUID.randomUUID();
            batch=number(batches.createBatch(new CreateBatch(p.railCode(),reference,"NET")),"CLEARING_BATCH_ID");
            cycle=treasury.createCycle(new CreateCycle(p.railCode(),ownReserve(),reference,batch,"NET"),"SYSTEM").id();
            // After early closure the replacement starts now, retaining the scheduled end.
            var closed=db.queryForList("SELECT C.CLOSED_AT FROM M07_SETTLEMENT_CYCLE C JOIN MBX_SIM_WINDOW W ON W.SETTLEMENT_CYCLE_ID=C.SETTLEMENT_CYCLE_ID WHERE C.RAIL_CODE=? AND W.WINDOW_END=? AND C.CLOSED_AT IS NOT NULL",p.railCode(),window.end());
            OffsetDateTime start=closed.isEmpty()?window.start():now();
            db.update("INSERT INTO MBX_SIM_WINDOW(SETTLEMENT_CYCLE_ID,CLEARING_BATCH_ID,WINDOW_START,WINDOW_END) VALUES (?,?,?,?)",cycle,batch,start,window.end());
            db.update("UPDATE M06_CLEARING_BATCH SET SETTLEMENT_CYCLE_ID=? WHERE CLEARING_BATCH_ID=?",cycle,batch);
        } else { cycle=number(cycles.get(0),"SETTLEMENT_CYCLE_ID");batch=number(cycles.get(0),"CLEARING_BATCH_ID"); }
        batches.addBatchItem(batch,new AddBatchItem(p.paymentId(),"SIM-PAYMENT-"+p.paymentId()));
        treasury.addCycleItems(cycle,new AddCycleItems(List.of(new CycleItem(p.paymentId(),p.direction().equals("OUTBOUND")?"OUT":"IN",p.amount()))),"SYSTEM");
        db.update("UPDATE M06_PAYMENT_INSTRUCTION SET SETTLEMENT_CYCLE_ID=? WHERE PAYMENT_ID=?",cycle,p.paymentId());
        if (p.status().equals("ACCEPTED")) advance(payments.get(p.paymentId()),"SETTLEMENT_PENDING",evidence(p,"PENDING"),null,null,"SIMULATED_CYCLE");
    }
    @Transactional
    public void closeCycle(long cycleId,String actor) {
        lock();var c=db.queryForMap("SELECT C.* FROM M07_SETTLEMENT_CYCLE C JOIN MBX_SIM_WINDOW W ON W.SETTLEMENT_CYCLE_ID=C.SETTLEMENT_CYCLE_ID WHERE C.SETTLEMENT_CYCLE_ID=? FOR UPDATE",cycleId);
        if("OPEN".equals(c.get("STATUS"))){long batch=number(c,"CLEARING_BATCH_ID");batches.closeBatch(batch);treasury.closeCycle(cycleId,actor);batches.linkSettlement(batch,new BatchSettlement(cycleId));}
    }
    @Transactional
    public void settleCycle(long cycleId,String actor) {
        lock();var c=db.queryForMap("SELECT C.* FROM M07_SETTLEMENT_CYCLE C JOIN MBX_SIM_WINDOW W ON W.SETTLEMENT_CYCLE_ID=C.SETTLEMENT_CYCLE_ID WHERE C.SETTLEMENT_CYCLE_ID=? FOR UPDATE",cycleId);
        if ("SETTLED".equals(c.get("STATUS"))) return;
        long batch=number(c,"CLEARING_BATCH_ID");
        if ("OPEN".equals(c.get("STATUS"))) { batches.closeBatch(batch);treasury.closeCycle(cycleId,actor);batches.linkSettlement(batch,new BatchSettlement(cycleId)); }
        List<Payment> items=db.query("SELECT PAYMENT_ID FROM M07_SETTLEMENT_CYCLE_ITEM WHERE SETTLEMENT_CYCLE_ID=? ORDER BY PAYMENT_ID",(rs,n)->payments.get(rs.getLong(1)),cycleId);
        settle(items,cycleId,actor);
    }
    /** Net participant movements and our balanced GL journal commit with all payment finality. */
    private void settle(List<Payment> items,Long cycleId,String actor) {
        if (items.isEmpty()) throw fail("EMPTY_CYCLE","Cannot settle an empty cycle");
        String reference=cycleId==null?"SIM-P-"+items.get(0).paymentId():"SIM-C-"+cycleId;
        if (!db.queryForList("SELECT SETTLEMENT_REFERENCE FROM MBX_SIM_SETTLEMENT WHERE SETTLEMENT_REFERENCE=?",reference).isEmpty()) return;
        Map<String,BigDecimal> net=new TreeMap<>(), released=new HashMap<>();
        List<JournalLine> lines=new ArrayList<>();
        for (Payment initial:items) {
            Payment p=repository.lock(initial.paymentId());
            if (!Set.of("ACCEPTED","SETTLEMENT_PENDING").contains(p.status()) || !Objects.equals(p.settlementCycleId(),cycleId)
                    || cycleId==null && !p.railCode().equals("RTGS")) throw fail("INVALID_SETTLEMENT_SCOPE","Payment is already settled or belongs to a different settlement mode");
            var m=db.queryForMap("SELECT * FROM MBX_SIM_PAYMENT WHERE PAYMENT_ID=?",p.paymentId());
            if (m.get("SETTLEMENT_REFERENCE")!=null || !db.queryForList("SELECT TREASURY_ENTRY_ID FROM M07_CENTRAL_TREASURY_LEDGER WHERE PAYMENT_ID=?",p.paymentId()).isEmpty()) throw fail("ALREADY_SETTLED","Payment reserve was already moved");
            String from=(String)m.get("FROM_BANK"),to=(String)m.get("TO_BANK");
            net.merge(from,p.amount().negate(),BigDecimal::add);net.merge(to,p.amount(),BigDecimal::add);
            released.merge(from,p.amount(),BigDecimal::add);
            long suspense=paymentGl(p,"SETTLEMENT_SUSPENSE");
            lines.add(line(suspense,null,p.direction().equals("OUTBOUND")?"DR":"CR",p.amount()));
        }
        if (net.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add).signum()!=0) throw fail("UNBALANCED_SETTLEMENT","Participant net positions do not balance");
        long reserve=ownReserve();
        var position=db.queryForMap("SELECT CONFIRMED_BALANCE,ACTIVE_HOLD_AMOUNT FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=? FOR UPDATE",reserve);
        Map<String,BigDecimal> before=new TreeMap<>();
        for (var entry:net.entrySet()) {
            String bank=entry.getKey();BigDecimal balance,held;
            if (bank.equals("MCP")) {balance=(BigDecimal)position.get("CONFIRMED_BALANCE");held=(BigDecimal)position.get("ACTIVE_HOLD_AMOUNT");}
            else {var b=db.queryForMap("SELECT BALANCE,HELD_AMOUNT FROM MBX_SIM_BANK WHERE BANK_CODE=? FOR UPDATE",bank);balance=(BigDecimal)b.get("BALANCE");held=(BigDecimal)b.get("HELD_AMOUNT");}
            BigDecimal remainder=held.subtract(released.getOrDefault(bank,BigDecimal.ZERO));
            BigDecimal buffer=bank.equals("MCP")?db.queryForObject("SELECT SAFETY_BUFFER_AMOUNT FROM M07_RESERVE_ACCOUNT WHERE RESERVE_ACCOUNT_ID=?",BigDecimal.class,reserve):BigDecimal.ZERO;
            if (remainder.signum()<0 || balance.add(entry.getValue()).subtract(remainder).subtract(buffer).signum()<0) throw fail("INSUFFICIENT_SETTLEMENT_LIQUIDITY","Settlement would consume funds reserved for another instruction");
            before.put(bank,balance);
        }
        BigDecimal ownNet=net.getOrDefault("MCP",BigDecimal.ZERO);
        Long entryId=null;long reserveGl=db.queryForObject("SELECT GL_ACCOUNT_ID FROM M07_RESERVE_ACCOUNT WHERE RESERVE_ACCOUNT_ID=?",Long.class,reserve);
        if (ownNet.signum()!=0) lines.add(line(reserveGl,null,ownNet.signum()>0?"DR":"CR",ownNet.abs()));
        long journal=post(reference,"SETTLEMENT",cycleId==null?items.get(0).paymentId():null,cycleId,lines,actor);
        if (ownNet.signum()!=0) {
            var e=treasury.recordEvidence(new SettlementEvidenceCommand(reserve,cycleId==null?items.get(0).paymentId():null,cycleId,items.get(0).railCode(),cycleId==null?"RAIL_CONFIRMATION":"CLEARING_CONFIRMATION","VERIFIED",ownNet.signum()>0?"IN":"OUT",ownNet.abs(),reference,null,"{\"simulation\":true,\"reference\":\""+reference+"\"}","simulation://settlement/"+reference,now()),actor);
            entryId=treasury.confirmMovement(new ConfirmMovement(e.id(),journal),actor).id();
        } else {
            // Zero net still has balanced participant confirmation, but no fabricated reserve movement.
            for (Payment p:items) if (p.direction().equals("OUTBOUND")) {
                int changed=db.update("UPDATE M07_TREASURY_LIQUIDITY_HOLD SET STATUS='RELEASED',RELEASE_REASON='ZERO_NET_SETTLEMENT',COMMITTED_AT=NULL,CLOSED_AT=SYSTIMESTAMP,VERSION_NO=VERSION_NO+1 WHERE LIQUIDITY_HOLD_ID=? AND STATUS='COMMITTED'",p.liquidityHoldId());
                if (changed!=1) throw fail("HOLD_MISMATCH","Missing committed reserve reservation");
                db.update("UPDATE M07_RESERVE_POSITION SET ACTIVE_HOLD_AMOUNT=ACTIVE_HOLD_AMOUNT-?,VERSION_NO=VERSION_NO+1 WHERE RESERVE_ACCOUNT_ID=?",p.amount(),reserve);
            }
            db.update("UPDATE M07_SETTLEMENT_CYCLE SET STATUS='SETTLED',SETTLED_AT=SYSTIMESTAMP WHERE SETTLEMENT_CYCLE_ID=? AND STATUS='CLOSED'",cycleId);
            db.update("UPDATE M07_SETTLEMENT_CYCLE_ITEM SET ITEM_STATUS='SETTLED' WHERE SETTLEMENT_CYCLE_ID=? AND ITEM_STATUS='INCLUDED'",cycleId);
        }
        update("INSERT INTO MBX_SIM_SETTLEMENT(SETTLEMENT_REFERENCE,PAYMENT_ID,SETTLEMENT_CYCLE_ID,STATUS,JOURNAL_ID,TREASURY_ENTRY_ID,CONFIRMED_AT) VALUES (?,?,?,'CONFIRMED',?,?,?)",
            new Object[]{reference,cycleId==null?items.get(0).paymentId():null,cycleId,journal,entryId,now()},Types.VARCHAR,Types.NUMERIC,Types.NUMERIC,Types.NUMERIC,Types.NUMERIC,Types.TIMESTAMP_WITH_TIMEZONE);
        for (var n:net.entrySet()) {
            if (!n.getKey().equals("MCP")) db.update("UPDATE MBX_SIM_BANK SET BALANCE=BALANCE+?,HELD_AMOUNT=HELD_AMOUNT-? WHERE BANK_CODE=?",n.getValue(),released.getOrDefault(n.getKey(),BigDecimal.ZERO),n.getKey());
            db.update("INSERT INTO MBX_SIM_MOVEMENT(SETTLEMENT_REFERENCE,BANK_CODE,NET_AMOUNT,BALANCE_BEFORE,BALANCE_AFTER) VALUES (?,?,?,?,?)",reference,n.getKey(),n.getValue(),before.get(n.getKey()),before.get(n.getKey()).add(n.getValue()));
        }
        for (Payment p:items) {
            db.update("UPDATE MBX_SIM_PAYMENT SET SETTLEMENT_REFERENCE=? WHERE PAYMENT_ID=?",reference,p.paymentId());
            long e=evidence(p,"SETTLED");
            if (p.direction().equals("INBOUND")) {
                credit(p,actor);long customer=db.queryForObject("SELECT CUSTOMER_JOURNAL_ID FROM MBX_SIM_PAYMENT WHERE PAYMENT_ID=?",Long.class,p.paymentId());
                p=advance(p,"CREDIT_POSTED",e,customer,entryId,"SIMULATED_CONFIRMED");
            }
            advance(p,"SETTLED",e,journal,entryId,p.direction().equals("OUTBOUND")?"INTERBANK_CONFIRMED":"SIMULATED_CONFIRMED");
        }
        if (cycleId!=null) {
            db.update("UPDATE M06_CLEARING_BATCH SET BATCH_STATUS='SETTLED',SETTLED_AT=SYSTIMESTAMP WHERE SETTLEMENT_CYCLE_ID=?",cycleId);
            db.update("UPDATE M06_CLEARING_BATCH_ITEM SET ITEM_STATUS='SETTLED' WHERE CLEARING_BATCH_ID=(SELECT CLEARING_BATCH_ID FROM M07_SETTLEMENT_CYCLE WHERE SETTLEMENT_CYCLE_ID=?)",cycleId);
        }
    }
    private void credit(Payment p,String actor) {
        if (db.queryForObject("SELECT CUSTOMER_JOURNAL_ID FROM MBX_SIM_PAYMENT WHERE PAYMENT_ID=?",Long.class,p.paymentId())!=null) return;
        var a=db.queryForMap("SELECT DESTINATION_ACCOUNT_ID,DESTINATION_CIF_ID FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",p.paymentId());
        checks.authorize(p,"WEB",number(a,"DESTINATION_ACCOUNT_ID"),(String)a.get("DESTINATION_CIF_ID"));
        long journal=post("incoming-credit:"+p.paymentId(),"PAYMENT_PROVISIONAL",p.paymentId(),null,List.of(line(paymentGl(p,"SETTLEMENT_SUSPENSE"),null,"DR",p.amount()),line(paymentGl(p,"CUSTOMER_LIABILITY"),number(a,"DESTINATION_ACCOUNT_ID"),"CR",p.amount())),actor);
        db.update("UPDATE MBX_SIM_PAYMENT SET CUSTOMER_JOURNAL_ID=? WHERE PAYMENT_ID=?",journal,p.paymentId());
    }
    private Payment advance(Payment p,String status,Long evidence,Long journal,Long entry,String reason) {
        if (repository.transitionRule(p.direction(),p.status(),status).isEmpty()) throw fail("ILLEGAL_TRANSITION","Illegal simulated payment transition");
        if (status.equals("SETTLED") && db.queryForObject("SELECT COUNT(*) FROM MBX_SIM_PAYMENT P JOIN MBX_SIM_SETTLEMENT S ON S.SETTLEMENT_REFERENCE=P.SETTLEMENT_REFERENCE WHERE P.PAYMENT_ID=? AND S.STATUS='CONFIRMED'",Integer.class,p.paymentId())!=1) throw fail("CONFIRMATION_REQUIRED","Balanced reserve confirmation is required");
        repository.advance(p,new Transition(status,p.version(),evidence,journal,entry,null,null,reason));return payments.get(p.paymentId());
    }
    private long evidence(Payment p,String status) {
        return payments.evidence(p.paymentId(),new RailEvidence("SIM-"+p.paymentId(),"SIM-"+p.paymentId()+"-"+status,"SETTLEMENT",status,"SIMULATED","simulation://payment/"+p.paymentId(),HexFormat.of().formatHex(hash(p.paymentId()+"|"+status)),now()));
    }
    private long paymentGl(Payment p,String role) {
        long version=db.queryForObject("SELECT A.PRODUCT_VERSION_ID FROM M04_BANK_ACCOUNT A JOIN M06_PAYMENT_INSTRUCTION P ON A.ACCOUNT_ID=CASE WHEN P.PAYMENT_DIRECTION='OUTBOUND' THEN P.SOURCE_ACCOUNT_ID ELSE P.DESTINATION_ACCOUNT_ID END WHERE P.PAYMENT_ID=?",Long.class,p.paymentId());
        return gl(version,role);
    }
    private long gl(long version,String role) {
        return db.queryForObject("SELECT GL_ACCOUNT_ID FROM M05_GL_PRODUCT_MAPPING WHERE PRODUCT_VERSION_ID=? AND POSTING_TYPE='PAYMENT' AND GL_ROLE_CODE=? AND STATUS='ACTIVE' AND EFFECTIVE_FROM<=SYSDATE AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>=SYSDATE)",Long.class,version,role);
    }
    private JournalLine line(long gl,Long account,String side,BigDecimal amount) { return new JournalLine(gl,account,null,null,side,amount,"Simulated payment settlement"); }
    private long post(String key,String type,Long payment,Long cycle,List<JournalLine> lines,String actor) {
        Long hold=null;
        if(payment!=null && type.equals("PAYMENT_PROVISIONAL")) {
            Payment p=payments.get(payment);if(p.direction().equals("OUTBOUND"))hold=p.holdId();
        }
        return ledger.postJournal(new JournalRequest(key,type,null,payment,cycle,null,hold,null,today(),"Simulated interbank processing",lines),actor).journalId();
    }
    public List<Map<String,Object>> banks() {
        return db.queryForList("SELECT B.BANK_CODE,B.BANK_NAME,B.RESERVE_ACCOUNT_ID,CASE WHEN B.BANK_CODE='MCP' THEN P.CONFIRMED_BALANCE ELSE B.BALANCE END BALANCE,CASE WHEN B.BANK_CODE='MCP' THEN P.ACTIVE_HOLD_AMOUNT ELSE B.HELD_AMOUNT END HELD_AMOUNT FROM MBX_SIM_BANK B LEFT JOIN M07_RESERVE_POSITION P ON P.RESERVE_ACCOUNT_ID=B.RESERVE_ACCOUNT_ID ORDER BY B.BANK_CODE");
    }
    public List<Map<String,Object>> cycles() { return db.queryForList("SELECT C.*,W.WINDOW_START,W.WINDOW_END,B.ITEM_COUNT,B.TOTAL_AMOUNT FROM MBX_SIM_WINDOW W JOIN M07_SETTLEMENT_CYCLE C ON C.SETTLEMENT_CYCLE_ID=W.SETTLEMENT_CYCLE_ID JOIN M06_CLEARING_BATCH B ON B.CLEARING_BATCH_ID=W.CLEARING_BATCH_ID ORDER BY C.SETTLEMENT_CYCLE_ID DESC FETCH FIRST 100 ROWS ONLY"); }
    public List<Map<String,Object>> instructions() { return db.queryForList("SELECT P.PAYMENT_ID,P.PAYMENT_DIRECTION,P.RAIL_CODE,P.AMOUNT,P.STATUS,P.CLEARING_BATCH_ID,P.SETTLEMENT_CYCLE_ID,S.FROM_BANK,S.TO_BANK,S.EXTERNAL_REFERENCE,S.SETTLEMENT_REFERENCE,S.CUSTOMER_JOURNAL_ID,A.ACCOUNT_NUMBER FROM MBX_SIM_PAYMENT S JOIN M06_PAYMENT_INSTRUCTION P ON P.PAYMENT_ID=S.PAYMENT_ID JOIN M04_BANK_ACCOUNT A ON A.ACCOUNT_ID=CASE WHEN P.PAYMENT_DIRECTION='INBOUND' THEN P.DESTINATION_ACCOUNT_ID ELSE P.SOURCE_ACCOUNT_ID END ORDER BY P.PAYMENT_ID DESC FETCH FIRST 200 ROWS ONLY"); }
    public List<Map<String,Object>> movements() { return db.queryForList("SELECT M.*,S.PAYMENT_ID,S.SETTLEMENT_CYCLE_ID,S.CONFIRMED_AT,S.STATUS FROM MBX_SIM_MOVEMENT M JOIN MBX_SIM_SETTLEMENT S ON S.SETTLEMENT_REFERENCE=M.SETTLEMENT_REFERENCE ORDER BY S.CONFIRMED_AT DESC FETCH FIRST 500 ROWS ONLY"); }
    public List<Long> dueCycles() { return db.query("SELECT C.SETTLEMENT_CYCLE_ID FROM MBX_SIM_WINDOW W JOIN M07_SETTLEMENT_CYCLE C ON C.SETTLEMENT_CYCLE_ID=W.SETTLEMENT_CYCLE_ID WHERE W.WINDOW_END<=? AND C.STATUS IN ('OPEN','CLOSED','SETTLEMENT_PENDING') ORDER BY W.WINDOW_END",(rs,n)->rs.getLong(1),now()); }
    public void recordFailure(long cycleId) { db.update("INSERT INTO MBX_EMPLOYEE_ERROR(ERROR_ID,SERVICE_NAME,ERROR_CODE,SAFE_MESSAGE,CORRELATION_ID) VALUES (?,'payments','CYCLE_SETTLEMENT_FAILED','Cycle settlement failed; membership retained for investigation and retry',?)",UUID.randomUUID().toString(),"cycle:"+cycleId); }
    private void update(String sql,Object[] args,int... types) {db.update(sql,args,types);}
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private OffsetDateTime now() {return clock.instant().atZone(SimulationPolicy.ZONE).toOffsetDateTime();}
    private LocalDate today() {return now().toLocalDate();}
    private static byte[] hash(String value) {try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    private static BusinessException fail(String code,String message) {return new BusinessException(HttpStatus.CONFLICT,code,message);}
}
