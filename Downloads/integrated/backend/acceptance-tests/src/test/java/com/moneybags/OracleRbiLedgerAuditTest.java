package com.moneybags;

import com.moneybags.integration.RbiReserveOpeningController;
import com.moneybags.integration.PaymentWorkflowController;
import com.moneybags.integration.TellerController;
import com.moneybags.payments.core.SettlementSimulator;
import com.moneybags.treasury.api.TreasuryRequests.*;
import com.moneybags.treasury.domain.DomainException;
import com.moneybags.treasury.service.TreasuryService;
import com.moneybags.txn.api.Contracts.*;
import com.moneybags.txn.api.ApiException;
import com.moneybags.txn.core.LedgerService;
import com.moneybags.common.api.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in Oracle service tests. Every fixture, financial write and session is rolled back. */
@EnabledIfEnvironmentVariable(named="RUN_ALPHA_PAYMENT_TESTS",matches="true")
@Transactional
class OracleRbiLedgerAuditTest extends OracleSettlementSmokeTest {
    @Autowired TreasuryService treasury;
    @Autowired LedgerService ledger;
    @Autowired RbiReserveOpeningController openings;
    @Autowired TellerController teller;
    @Autowired com.moneybags.txn.core.OperationsService operations;
    Long auditTransaction;

    String key(){return "QA-"+UUID.randomUUID();}
    BigDecimal reserveBalance(){return treasury.position(reserve).confirmedBalance();}
    void money(BigDecimal expected,BigDecimal actual){assertEquals(0,expected.compareTo(actual));}
    long gl(String accountClass,String side){
        String code=key();
        db.update("INSERT INTO M05_GL_ACCOUNT(GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE) VALUES (?,'Rollback audit fixture',?,?)",code,accountClass,side);
        return db.queryForObject("SELECT GL_ACCOUNT_ID FROM M05_GL_ACCOUNT WHERE GL_CODE=?",Long.class,code);
    }
    JournalRequest journal(String key,long asset,long equity,String debit,String credit,LocalDate date){
        if(auditTransaction==null){
            String transactionKey=key();
            db.update("INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,AMOUNT,VALUE_DATE) VALUES (?,'TREASURY',?,?,?,'ADJUSTMENT','VALIDATED',1,?)",
                admin,transactionKey,com.moneybags.integration.CustomerHashService.digest(transactionKey),transactionKey,today());
            auditTransaction=db.queryForObject("SELECT TXN_ID FROM M05_TXN_TRANSACTION_LOG WHERE ORIGINATOR_ID=? AND CHANNEL_CODE='TREASURY' AND REQUEST_KEY=?",Long.class,admin,transactionKey);
        }
        return new JournalRequest(key,"ADJUSTMENT",auditTransaction,null,null,null,null,null,date,"Oracle rollback audit",
            List.of(new JournalLine(asset,null,null,null,"DR",new BigDecimal(debit),"Fixture"),
                new JournalLine(equity,null,null,null,"CR",new BigDecimal(credit),"Fixture")));
    }
    LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Kolkata"));}
    com.moneybags.payments.api.Contracts.Payment outgoing(String rail,String amount){
        return workflow.initiate(new PaymentWorkflowController.Initiate(account,beneficiary,rail,new BigDecimal(amount),key()));
    }

    @Test void incomingReferenceReplayAndPayloadConflictNeverCreditTwice(){
        BigDecimal before=position();String ref=key();
        var first=simulator.incoming(incoming("UPI","3.25",ref));
        assertEquals(first.paymentId(),simulator.incoming(incoming("UPI","3.25",ref)).paymentId());
        assertEquals("DUPLICATE_REFERENCE",assertThrows(BusinessException.class,()->simulator.incoming(incoming("UPI","3.26",ref))).code());
        money(before.add(new BigDecimal("3.25")),position());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM MBX_SIM_PAYMENT WHERE EXTERNAL_REFERENCE=?",Integer.class,ref));
    }

    @Test void invalidRoutingAndInsufficientExternalFundsDoNotCreateInstructions(){
        int before=db.queryForObject("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION",Integer.class);
        String branch=db.queryForObject("SELECT BRANCH_CODE FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?",String.class,account);
        for(var command:List.of(
            new SettlementSimulator.Incoming("SBI",number,"WRONG","MCPB0000002","NEFT",BigDecimal.ONE,key(),null),
            new SettlementSimulator.Incoming("SBI","NO_SUCH_ACCOUNT",branch,"MCPB0000002","NEFT",BigDecimal.ONE,key(),null),
            new SettlementSimulator.Incoming("SBI",number,null,null,"RTGS",new BigDecimal("200000"),key(),null),
            incoming("NEFT","10000001",key())))
            assertThrows(BusinessException.class,()->simulator.incoming(command));
        assertThrows(IllegalArgumentException.class,()->simulator.incoming(incoming("RTGS","199999.99",key())));
        assertThrows(IllegalArgumentException.class,()->simulator.incoming(incoming("UPI","1.001",key())));
        assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM M06_PAYMENT_INSTRUCTION",Integer.class));
    }

    @Test void independentCheckerCanRejectUnprocessedPaymentAndReplayWithoutDebit(){
        simulator.incoming(incoming("RTGS","1200000",key()));
        BigDecimal before=position(),r=reserveBalance();
        var pending=outgoing("NEFT","100000.01");assertEquals("RECEIVED",pending.status());
        assertEquals("DISTINCT_CHECKER",assertThrows(BusinessException.class,()->simulator.approve(pending.paymentId(),reserve)).code());
        assertEquals("DISTINCT_CHECKER",assertThrows(BusinessException.class,()->simulator.reject(pending.paymentId())).code());
        as(checker);
        assertEquals("REJECTED",simulator.reject(pending.paymentId()).status());
        assertEquals("REJECTED",simulator.reject(pending.paymentId()).status());
        money(before,position());money(r,reserveBalance());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M06_CLEARING_BATCH_ITEM WHERE PAYMENT_ID=?",Integer.class,pending.paymentId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M05_GL_JOURNAL WHERE PAYMENT_ID=?",Integer.class,pending.paymentId()));
    }

    @Test void zeroNetCycleSettlesBothSidesOnceWithoutChangingReserve(){
        simulator.incoming(incoming("RTGS","200000",key()));
        BigDecimal before=position(),r=reserveBalance();
        var out=outgoing("IMPS","25");var in=simulator.incoming(incoming("IMPS","25",key()));
        assertEquals(out.settlementCycleId(),in.settlementCycleId());
        simulator.settleCycle(out.settlementCycleId(),admin);simulator.settleCycle(out.settlementCycleId(),admin);
        money(before,position());money(r,reserveBalance());
        for(long id:List.of(out.paymentId(),in.paymentId()))assertEquals("SETTLED",db.queryForObject("SELECT STATUS FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",String.class,id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM MBX_SIM_SETTLEMENT WHERE SETTLEMENT_CYCLE_ID=?",Integer.class,out.settlementCycleId()));
        money(BigDecimal.ZERO,db.queryForObject("SELECT ACTIVE_HOLD_AMOUNT FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=?",BigDecimal.class,reserve));
    }

    @Test void liquidityRejectsInvalidScopeAndDuplicatePayload(){
        var p=outgoing("UPI","1");
        var existing=treasury.holds(reserve,null).stream().filter(h->Objects.equals(h.paymentId(),p.paymentId())).findFirst().orElseThrow();
        assertThrows(DomainException.class,()->treasury.reserve(new CreateLiquidityHold(existing.holdKey(),reserve,"UPI",p.paymentId(),null,new BigDecimal("2"),OffsetDateTime.now().plusHours(1)),admin));
        assertThrows(DomainException.class,()->treasury.reserve(new CreateLiquidityHold(key(),reserve,"UPI",p.paymentId(),p.settlementCycleId(),BigDecimal.ONE,null),admin));
        assertThrows(DomainException.class,()->treasury.reserve(new CreateLiquidityHold(key(),reserve,"UPI",null,null,BigDecimal.ONE,null),admin));
        assertThrows(DomainException.class,()->treasury.decideHold(existing.id(),new HoldDecision("RELEASE","TEST",existing.version()),admin));
    }

    @Test void syntheticOpeningRequiresIndependentApprovalAndBalancesBothBooks(){
        long asset=gl("ASSET","DR"),equity=gl("EQUITY","CR");
        var fixture=treasury.createReserveAccount(new CreateReserveAccount(key(),"RBI_CURRENT",key(),asset,BigDecimal.ZERO,BigDecimal.ZERO),admin);
        var request=new RbiReserveOpeningController.RequestOpening(key(),fixture.id(),equity,new BigDecimal("50000"),key());
        String id=(String)openings.request(request).get("openingId");assertEquals(id,openings.request(request).get("openingId"));
        assertEquals("RESERVE_OPENING_CONTROL",assertThrows(BusinessException.class,()->openings.confirm(id)).code());
        as(checker);var confirmed=openings.confirm(id);assertEquals(((Number)confirmed.get("journalId")).longValue(),((Number)openings.confirm(id).get("journalId")).longValue());
        money(new BigDecimal("50000"),treasury.position(fixture.id()).confirmedBalance());
        assertEquals("Y",treasury.reserveReconciliation(fixture.id()).isMatched());
        assertEquals("Y",ledger.journalControl(((Number)confirmed.get("journalId")).longValue()).get("IS_BALANCED"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER WHERE RESERVE_OPENING_ID=?",Integer.class,id));
    }

    @Test void journalReplayIsExactAndOracleRejectsMutationOfPostingHistory(){
        long asset=gl("ASSET","DR"),equity=gl("EQUITY","CR");String key=key();
        var request=journal(key,asset,equity,"1.25","1.25",today());
        var first=ledger.postJournal(request,admin);assertEquals(first.journalId(),ledger.postJournal(request,admin).journalId());
        var conflict=assertThrows(ApiException.class,()->ledger.postJournal(journal(key,asset,equity,"1.26","1.26",today()),admin));
        assertEquals("IDEMPOTENCY_CONFLICT",conflict.code());
        assertThrows(DataAccessException.class,()->db.update("UPDATE M05_GL_JOURNAL SET DESCRIPTION='altered' WHERE JOURNAL_ID=?",first.journalId()));
        assertThrows(DataAccessException.class,()->db.update("UPDATE M05_GL_POSTING SET AMOUNT=2 WHERE JOURNAL_ID=?",first.journalId()));
        assertThrows(DataAccessException.class,()->db.update("DELETE FROM M05_GL_POSTING WHERE JOURNAL_ID=?",first.journalId()));
        assertEquals("Y",ledger.journalControl(first.journalId()).get("IS_BALANCED"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM M05_GL_POSTING WHERE JOURNAL_ID=?",Integer.class,first.journalId()));
    }

    @Test void invalidAndFutureJournalsNeverCreatePostings(){
        long asset=gl("ASSET","DR"),equity=gl("EQUITY","CR");
        int before=db.queryForObject("SELECT COUNT(*) FROM M05_GL_JOURNAL",Integer.class);
        assertEquals("UNBALANCED_JOURNAL",assertThrows(ApiException.class,()->ledger.postJournal(journal(key(),asset,equity,"1","2",today()),admin)).code());
        assertEquals("FUTURE_VALUE_DATE",assertThrows(ApiException.class,()->ledger.postJournal(journal(key(),asset,equity,"1","1",today().plusDays(1)),admin)).code());
        assertThrows(ApiException.class,()->ledger.postJournal(journal(key(),asset,equity,"-1","-1",today()),admin));
        assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM M05_GL_JOURNAL",Integer.class));
    }

    @Test void reserveCannotReuseMappedOrLiabilityGlAndEmptyCycleCannotClose(){
        long mapped=treasury.reserveAccount(reserve).glAccountId();
        assertThrows(DomainException.class,()->treasury.createReserveAccount(new CreateReserveAccount(key(),"RBI_CURRENT",key(),mapped,BigDecimal.ZERO,BigDecimal.ZERO),admin));
        long liability=gl("LIABILITY","CR");
        assertThrows(DomainException.class,()->treasury.createReserveAccount(new CreateReserveAccount(key(),"RBI_CURRENT",key(),liability,BigDecimal.ZERO,BigDecimal.ZERO),admin));
        var cycle=treasury.createCycle(new CreateCycle("NEFT",reserve,key(),null,"OUTBOUND"),admin);
        assertThrows(DomainException.class,()->treasury.closeCycle(cycle.id(),admin));
    }

    @Test void treasuryWorkSeparatesMakerCheckerAndRejectsStaleDecisions(){
        var item=treasury.createWork(new CreateWorkItem("RECONCILIATION","RESERVE_ACCOUNT",reserve,null,null,key(),
            BigDecimal.ONE,BigDecimal.ONE,"Rollback audit",admin),admin);
        treasury.submitWork(item.id(),admin,item.version());
        var pending=treasury.workItem(item.id());
        assertEquals("CHECKER_CONFLICT",assertThrows(DomainException.class,()->treasury.decideWork(item.id(),new WorkDecision("WAIVED",admin,null,pending.version()))).code());
        assertEquals("CHECKER_CONFLICT",assertThrows(DomainException.class,()->treasury.decideWork(item.id(),new WorkDecision("WAIVED",checker,null,item.version()))).code());
        treasury.decideWork(item.id(),new WorkDecision("WAIVED",checker,null,pending.version()));
        assertEquals("WAIVED",treasury.workItem(item.id()).status().name());
        assertEquals(checker,treasury.workItem(item.id()).checkerUserId());
        assertThrows(DomainException.class,()->treasury.decideWork(item.id(),new WorkDecision("RESOLVED",checker,null,pending.version())));
    }

    @Test void treasuryWorkRejectsIneligibleOwnersAndRevokedAssignments(){
        String customer=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USER_TYPE='CUSTOMER' AND STATUS='ACTIVE' ORDER BY USER_ID FETCH FIRST 1 ROWS ONLY",String.class);
        int before=db.queryForObject("SELECT COUNT(*) FROM M07_TREASURY_WORK_ITEM",Integer.class);
        assertTrue(treasury.workOwners().stream().anyMatch(owner->owner.userId().equals(admin)));
        assertTrue(treasury.workOwners().stream().anyMatch(owner->owner.userId().equals(checker)));
        assertFalse(treasury.workOwners().stream().anyMatch(owner->owner.userId().equals(customer)));
        for(String invalid:List.of(customer,UUID.randomUUID().toString()))
            assertEquals("INVALID_WORK_OWNER",assertThrows(DomainException.class,()->treasury.createWork(new CreateWorkItem("RECONCILIATION","RESERVE_ACCOUNT",reserve,null,null,key(),BigDecimal.ONE,BigDecimal.ONE,"Rollback owner validation",invalid),admin)).code());
        db.update("UPDATE M01_IAM_USER SET STATUS='DISABLED' WHERE USER_ID=?",checker);
        assertFalse(treasury.workOwners().stream().anyMatch(owner->owner.userId().equals(checker)));
        assertEquals("INVALID_WORK_OWNER",assertThrows(DomainException.class,()->treasury.createWork(new CreateWorkItem("RECONCILIATION","RESERVE_ACCOUNT",reserve,null,null,key(),BigDecimal.ONE,BigDecimal.ONE,"Rollback stale owner",checker),admin)).code());
        assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM M07_TREASURY_WORK_ITEM",Integer.class));
    }

    @Test void existingTreasuryWorkEnforcesIndependentCheckerAndVersion(){
        // An explicit rollback fixture isolates approval behavior from work creation.
        String reference=key();
        db.update("INSERT INTO M07_TREASURY_WORK_ITEM(WORK_TYPE,SUBJECT_TYPE,RESERVE_ACCOUNT_ID,SOURCE_REFERENCE,DETAIL_TEXT,OWNER_ID) VALUES ('RECONCILIATION','RESERVE_ACCOUNT',?,?,'Rollback approval fixture',?)",reserve,reference,admin);
        long id=db.queryForObject("SELECT WORK_ITEM_ID FROM M07_TREASURY_WORK_ITEM WHERE SOURCE_REFERENCE=?",Long.class,reference);
        var item=treasury.workItem(id);treasury.submitWork(id,admin,item.version());var pending=treasury.workItem(id);
        assertEquals("CHECKER_CONFLICT",assertThrows(DomainException.class,()->treasury.decideWork(id,new WorkDecision("WAIVED",admin,null,pending.version()))).code());
        assertEquals("CHECKER_CONFLICT",assertThrows(DomainException.class,()->treasury.decideWork(id,new WorkDecision("WAIVED",checker,null,item.version()))).code());
        treasury.decideWork(id,new WorkDecision("WAIVED",checker,null,pending.version()));
        assertEquals("WAIVED",treasury.workItem(id).status().name());
        assertEquals(checker,treasury.workItem(id).checkerUserId());
        assertThrows(DomainException.class,()->treasury.decideWork(id,new WorkDecision("RESOLVED",checker,null,pending.version())));
    }

    @Test void periodCloseRejectsSelfApprovalAndUnreconciledPosition(){
        // The negative case must not depend on a broken live-data position.
        db.update("UPDATE M05_ACCOUNT_POSITION SET POSTED_BALANCE=POSTED_BALANCE+0.01 WHERE BANK_ACCOUNT_ID=?",account);
        LocalDate date=today().minusDays(1);
        while(db.queryForObject("SELECT COUNT(*) FROM M05_GL_PERIOD_CLOSE WHERE CLOSED_THROUGH_DATE=?",Integer.class,date)>0)date=date.minusDays(1);
        var request=new com.moneybags.txn.api.OperationsContracts.CloseRequest(key(),date,"Rollback close audit");
        var first=operations.requestClose(request,admin);assertEquals(first,operations.requestClose(request,admin));
        long id=((Number)first.get("closeId")).longValue();var decision=new com.moneybags.txn.api.OperationsContracts.CloseDecision("APPROVED");
        assertEquals("MAKER_CHECKER",assertThrows(ApiException.class,()->operations.decideClose(id,decision,admin)).code());
        assertEquals("RECONCILIATION_OPEN",assertThrows(ApiException.class,()->operations.decideClose(id,decision,checker)).code());
        assertEquals("PENDING",db.queryForObject("SELECT STATUS FROM M05_GL_PERIOD_CLOSE WHERE CLOSE_ID=?",String.class,id));
    }

    @Test void reconciledLedgerAllowsIndependentPeriodCloseAndExactReplay(){
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M05_V_ACCOUNT_POSITION_RECON WHERE IS_MATCHED='N'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M05_V_GL_JOURNAL_CONTROL WHERE IS_BALANCED='N'",Integer.class));
        LocalDate date=today().minusDays(1);
        while(db.queryForObject("SELECT COUNT(*) FROM M05_GL_PERIOD_CLOSE WHERE CLOSED_THROUGH_DATE=?",Integer.class,date)>0)date=date.minusDays(1);
        var first=operations.requestClose(new com.moneybags.txn.api.OperationsContracts.CloseRequest(key(),date,"Rollback clean close audit"),admin);
        long id=((Number)first.get("closeId")).longValue();var decision=new com.moneybags.txn.api.OperationsContracts.CloseDecision("APPROVED");
        assertEquals("MAKER_CHECKER",assertThrows(ApiException.class,()->operations.decideClose(id,decision,admin)).code());
        var approved=operations.decideClose(id,decision,checker);
        assertEquals("APPROVED",approved.get("status"));assertEquals(approved,operations.decideClose(id,decision,checker));
    }

    @Test void rbiCashDeliveryChecksCountIndependenceLiquidityAndReplay(){
        String branch=db.queryForObject("SELECT BRANCH_CODE FROM MBX_BRANCH_VAULT ORDER BY BRANCH_CODE FETCH FIRST 1 ROWS ONLY",String.class);
        BigDecimal vault=db.queryForObject("SELECT CASH_BALANCE FROM MBX_BRANCH_VAULT WHERE BRANCH_CODE=?",BigDecimal.class,branch),r=reserveBalance();
        var command=new TellerController.RequestCashDelivery(key(),branch,reserve,BigDecimal.ONE,key(),key());
        String id=(String)teller.requestCashDelivery(command).get("deliveryId");
        assertEquals(id,teller.requestCashDelivery(command).get("deliveryId"));
        var receipt=new TellerController.ConfirmCashDelivery(BigDecimal.ONE,key());
        assertThrows(BusinessException.class,()->teller.confirmCashDelivery(id,receipt));
        as(checker);
        assertThrows(BusinessException.class,()->teller.confirmCashDelivery(id,new TellerController.ConfirmCashDelivery(new BigDecimal("0.99"),key())));
        var confirmed=teller.confirmCashDelivery(id,receipt);
        assertEquals(((Number)confirmed.get("journalId")).longValue(),((Number)teller.confirmCashDelivery(id,receipt).get("journalId")).longValue());
        money(vault.add(BigDecimal.ONE),db.queryForObject("SELECT CASH_BALANCE FROM MBX_BRANCH_VAULT WHERE BRANCH_CODE=?",BigDecimal.class,branch));
        money(r.subtract(BigDecimal.ONE),reserveBalance());
        assertEquals("Y",treasury.reserveReconciliation(reserve).isMatched());
        assertEquals("Y",ledger.journalControl(((Number)confirmed.get("journalId")).longValue()).get("IS_BALANCED"));
        as(admin);BigDecimal excess=r.add(BigDecimal.ONE);
        String oversized=(String)teller.requestCashDelivery(new TellerController.RequestCashDelivery(key(),branch,reserve,excess,key(),key())).get("deliveryId");
        as(checker);assertThrows(BusinessException.class,()->teller.confirmCashDelivery(oversized,new TellerController.ConfirmCashDelivery(excess,key())));
        assertEquals("PENDING",db.queryForObject("SELECT STATUS FROM MBX_RBI_CASH_DELIVERY WHERE DELIVERY_ID=?",String.class,oversized));
    }
}
