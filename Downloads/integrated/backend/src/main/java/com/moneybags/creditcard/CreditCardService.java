package com.moneybags.creditcard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.common.api.BusinessException;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.iam.dto.IamDtos.AuthorizationInput;
import com.moneybags.iam.service.AccessDecisionService;
import com.moneybags.integration.BankingAccess;
import com.moneybags.integration.CurrentActor;
import com.moneybags.integration.CustomerHashService;
import com.moneybags.txn.api.Contracts.JournalLine;
import com.moneybags.txn.api.Contracts.JournalRequest;
import com.moneybags.txn.core.LedgerService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.moneybags.common.database.BusinessRepository.*;
import static com.moneybags.creditcard.CreditCardDtos.*;
import static com.moneybags.creditcard.CreditCardMath.*;

/** Credit card commands and their ledger effects commit together in the shared database. */
@Service
public class CreditCardService {
    private static final BigDecimal ZERO=new BigDecimal("0.00");
    private static final SecureRandom RANDOM=new SecureRandom();
    private final BusinessRepository db;
    private final AccessDecisionService access;
    private final BankingAccess banking;
    private final LedgerService ledger;
    private final CustomerHashService audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final ZoneId businessZone;

    public CreditCardService(BusinessRepository db,AccessDecisionService access,BankingAccess banking,
                             LedgerService ledger,CustomerHashService audit,ObjectMapper json,Clock clock,
                             @Value("${moneybags.business-zone:Asia/Kolkata}") String businessZone) {
        this.db=db;this.access=access;this.banking=banking;this.ledger=ledger;this.audit=audit;this.json=json;this.clock=clock;
        this.businessZone=ZoneId.of(businessZone);
    }

    public List<Map<String,Object>> products() {
        CurrentActor.require("CC_READ");
        return db.rows("SELECT * FROM M11_CC_PRODUCT "+(customer()?"WHERE STATUS='APPROVED' ":"")+"ORDER BY PRODUCT_CODE,VERSION_NO DESC FETCH FIRST 200 ROWS ONLY")
            .stream().map(this::productView).toList();
    }

    @Transactional
    public Map<String,Object> createProduct(ProductInput r) {
        staffGlobal("CC_PRODUCT_MANAGE");lockActor();
        return command(r.requestKey(),"product-create",r,()->{
            if(r.minimumLimit().compareTo(r.maximumLimit())>0)throw conflict("INVALID_LIMIT_RANGE","Minimum credit limit exceeds maximum credit limit");
            String id=uuid();
            db.jdbc().update("""
                INSERT INTO M11_CC_PRODUCT(PRODUCT_ID,PRODUCT_CODE,PRODUCT_NAME,VERSION_NO,MINIMUM_LIMIT,MAXIMUM_LIMIT,
                  ANNUAL_RATE_PCT,MIN_PAYMENT_PCT,MIN_PAYMENT_FLOOR,BILLING_DAY,PAYMENT_DUE_DAYS,DESCRIPTION,MAKER_ID)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,r.productCode(),r.productName().trim(),r.versionNumber(),r.minimumLimit(),r.maximumLimit(),
                r.annualRatePct(),r.minimumPaymentPct(),r.minimumPaymentFloor(),r.billingDay(),r.paymentDueDays(),r.description().trim(),actor());
            audit("PRODUCT_CREATED",id,"PENDING_REVIEW");
            return productView(product(id,false));
        });
    }

    @Transactional
    public Map<String,Object> decideProduct(String id,Decision r) {
        staffGlobal("CC_PRODUCT_APPROVE");lockActor();var p=product(id,true);
        independent(str(p,"MAKER_ID"));
        return command(r.requestKey(),"product-decision/"+id,r,()->{
            version(p,r.rowVersion());state(p,"PENDING");
            if(r.approvedLimit()!=null)throw conflict("INVALID_DECISION","Credit limits are approved on applications, not product decisions");
            if("APPROVED".equals(r.decision())){gl("CC_RECEIVABLE","ASSET");gl("CC_SETTLEMENT","LIABILITY");gl("CC_INTEREST","INCOME");}
            db.jdbc().update("UPDATE M11_CC_PRODUCT SET STATUS=?,CHECKER_ID=?,DECISION_REASON=?,DECIDED_AT=SYSTIMESTAMP,ROW_VERSION=ROW_VERSION+1 WHERE PRODUCT_ID=?",r.decision(),actor(),r.reason(),id);
            audit("PRODUCT_DECIDED",id,r.decision());return productView(product(id,false));
        });
    }

    @Transactional
    public Map<String,Object> retireProduct(String id,Command r) {
        staffGlobal("CC_PRODUCT_MANAGE");lockActor();var p=product(id,true);
        return command(r.requestKey(),"product-retire/"+id,r,()->{
            version(p,r.rowVersion());state(p,"APPROVED");
            db.jdbc().update("UPDATE M11_CC_PRODUCT SET STATUS='RETIRED',ROW_VERSION=ROW_VERSION+1 WHERE PRODUCT_ID=?",id);
            audit("PRODUCT_RETIRED",id,r.reason());return productView(product(id,false));
        });
    }

    public List<Map<String,Object>> applications() {
        CurrentActor.require("CC_READ");
        var visible=new ArrayList<Map<String,Object>>();int offset=0;
        while(visible.size()<100){
            var rows=db.rows("SELECT A.*,P.PRODUCT_NAME FROM M11_CC_APPLICATION A JOIN M11_CC_PRODUCT P ON P.PRODUCT_ID=A.PRODUCT_ID ORDER BY A.CREATED_AT DESC,A.APPLICATION_ID DESC OFFSET ? ROWS FETCH NEXT 500 ROWS ONLY",offset);
            for(var row:rows)if(allowed(row,"CC_READ")){visible.add(row);if(visible.size()==100)break;}
            if(rows.size()<500)break;
            offset+=rows.size();
        }
        return visible;
    }

    @Transactional
    public Map<String,Object> apply(ApplicationInput r) {
        lockActor();var c=cif(r.cifId(),true);require("CC_APPLY",str(c,"HOME_BRANCH_REF"),r.cifId());
        return command(r.requestKey(),"apply",r,()->{
            eligible(c);var p=product(r.productId(),true);state(p,"APPROVED");
            requireRepaymentAccount(r.repaymentAccountId(),r.cifId(),true);
            range(r.requestedLimit(),p);
            if(db.count("SELECT COUNT(*) FROM M11_CC_APPLICATION A JOIN M11_CC_PRODUCT P ON P.PRODUCT_ID=A.PRODUCT_ID WHERE A.CIF_ID=? AND P.PRODUCT_CODE=? AND A.STATUS='PENDING'",r.cifId(),p.get("PRODUCT_CODE"))>0
                ||db.count("SELECT COUNT(*) FROM M11_CC_CARD C JOIN M11_CC_PRODUCT P ON P.PRODUCT_ID=C.PRODUCT_ID WHERE C.CIF_ID=? AND P.PRODUCT_CODE=? AND C.STATUS<>'CLOSED'",r.cifId(),p.get("PRODUCT_CODE"))>0)
                throw conflict("EXISTING_CARD_APPLICATION","An open card or pending application already exists for this product");
            String id=uuid();
            db.jdbc().update("INSERT INTO M11_CC_APPLICATION(APPLICATION_ID,PRODUCT_ID,CIF_ID,BRANCH_CODE,REPAYMENT_ACCOUNT_ID,REQUESTED_LIMIT,MAKER_ID) VALUES (?,?,?,?,?,?,?)",id,r.productId(),r.cifId(),c.get("HOME_BRANCH_REF"),r.repaymentAccountId(),r.requestedLimit(),actor());
            audit("APPLICATION_CREATED",id,"TERMS_ACCEPTED");return application(id,false);
        });
    }

    @Transactional
    public Map<String,Object> decideApplication(String id,Decision r) {
        staff();lockActor();var a=application(id,true);require(a,"CC_APPROVE");independent(str(a,"MAKER_ID"));
        return command(r.requestKey(),"application-decision/"+id,r,()->{
            version(a,r.rowVersion());state(a,"PENDING");
            if("APPROVED".equals(r.decision())){
                if(r.approvedLimit()==null)throw conflict("LIMIT_REQUIRED","Enter the approved credit limit");
                var c=cif(str(a,"CIF_ID"),true);eligible(c);var p=product(str(a,"PRODUCT_ID"),true);state(p,"APPROVED");
                range(r.approvedLimit(),p);
                if(r.approvedLimit().compareTo(money(a,"REQUESTED_LIMIT"))>0)throw conflict("LIMIT_EXCEEDS_REQUEST","Approved credit limit cannot exceed the amount requested");
                access.require(CurrentActor.get(),new AuthorizationInput("CC_APPROVE",str(a,"BRANCH_CODE"),null,str(a,"CIF_ID"),"CREDIT_CARD","INR","CC_LIMIT",r.approvedLimit(),money(p,"ANNUAL_RATE_PCT"),str(a,"MAKER_ID")));
                requireRepaymentAccount(number(a,"REPAYMENT_ACCOUNT_ID"),str(a,"CIF_ID"),false);
                String card=uuid();LocalDate today=today();
                db.jdbc().update("""
                    INSERT INTO M11_CC_CARD(CARD_ID,APPLICATION_ID,PRODUCT_ID,CIF_ID,BRANCH_CODE,REPAYMENT_ACCOUNT_ID,
                      CARD_REFERENCE,DISPLAY_ENDING,CREDIT_LIMIT,LAST_ACCRUAL_DATE,NEXT_STATEMENT_DATE,ISSUED_DATE,EXPIRY_DATE)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,card,id,a.get("PRODUCT_ID"),a.get("CIF_ID"),a.get("BRANCH_CODE"),a.get("REPAYMENT_ACCOUNT_ID"),
                    "SIM-"+card,String.format(Locale.ROOT,"%04d",RANDOM.nextInt(10000)),r.approvedLimit(),today,nextBilling(today,(int)number(p,"BILLING_DAY")),today,today.plusYears(3));
            }else if(r.approvedLimit()!=null)throw conflict("INVALID_DECISION","A rejected application cannot have an approved limit");
            db.jdbc().update("UPDATE M11_CC_APPLICATION SET STATUS=?,APPROVED_LIMIT=?,CHECKER_ID=?,DECISION_REASON=?,DECIDED_AT=SYSTIMESTAMP,ROW_VERSION=ROW_VERSION+1 WHERE APPLICATION_ID=?",r.decision(),r.approvedLimit(),actor(),r.reason(),id);
            audit("APPLICATION_DECIDED",id,r.decision());var result=application(id,false);
            var cards=db.rows("SELECT CARD_ID FROM M11_CC_CARD WHERE APPLICATION_ID=?",id);
            if(!cards.isEmpty())result.put("CARD_ID",cards.get(0).get("CARD_ID"));return result;
        });
    }

    @Transactional
    public Map<String,Object> cancelApplication(String id,Command r) {
        lockActor();var a=application(id,true);require(a,"CC_APPLY");
        return command(r.requestKey(),"application-cancel/"+id,r,()->{
            version(a,r.rowVersion());state(a,"PENDING");
            db.jdbc().update("UPDATE M11_CC_APPLICATION SET STATUS='CANCELLED',DECISION_REASON=?,ROW_VERSION=ROW_VERSION+1 WHERE APPLICATION_ID=?",r.reason(),id);
            audit("APPLICATION_CANCELLED",id,r.reason());return application(id,false);
        });
    }

    public List<Map<String,Object>> cards() {
        CurrentActor.require("CC_READ");
        var visible=new ArrayList<Map<String,Object>>();int offset=0;
        while(visible.size()<100){
            var rows=db.rows("SELECT C.*,P.PRODUCT_NAME FROM M11_CC_CARD C JOIN M11_CC_PRODUCT P ON P.PRODUCT_ID=C.PRODUCT_ID ORDER BY C.ISSUED_DATE DESC,C.CARD_ID OFFSET ? ROWS FETCH NEXT 500 ROWS ONLY",offset);
            for(var row:rows)if(allowed(row,"CC_READ")){visible.add(cardView(row));if(visible.size()==100)break;}
            if(rows.size()<500)break;
            offset+=rows.size();
        }
        return visible;
    }

    public Map<String,Object> card(String id) {
        var c=cardRow(id,false);require(c,"CC_READ");audit("CARD_VIEWED",id,"AUTHORIZED_READ");return cardView(c);
    }

    @Transactional
    public Map<String,Object> control(String id,Control r) {
        lockActor();var c=cardRow(id,true);require(c,"CC_MANAGE");
        if("BLOCK".equals(r.action())){staff();require(c,"CC_SERVICE");}
        return command(r.requestKey(),"card-control/"+id,r,()->{
            version(c,r.rowVersion());String old=str(c,"STATUS");
            String target=switch(r.action()){
                case "ACTIVATE"->{if(!"ISSUED".equals(old))throw transition();eligible(cif(str(c,"CIF_ID"),false));unexpired(c);yield "ACTIVE";}
                case "FREEZE"->{if(!"ACTIVE".equals(old))throw transition();yield "FROZEN";}
                case "UNFREEZE"->{if(!"FROZEN".equals(old))throw transition();eligible(cif(str(c,"CIF_ID"),false));unexpired(c);yield "ACTIVE";}
                case "BLOCK"->{if(Set.of("CLOSED","BLOCKED").contains(old))throw transition();yield "BLOCKED";}
                case "CLOSE"->{
                    if("CLOSED".equals(old))throw transition();
                    if(balance(c).signum()!=0||cents(money(c,"ACCRUED_INTEREST")).signum()>0)
                        throw conflict("OUTSTANDING_BALANCE","Repay the principal and all posted or accrued interest before closing this card");
                    yield "CLOSED";
                }
                default->throw transition();
            };
            db.jdbc().update("UPDATE M11_CC_CARD SET STATUS=?,ROW_VERSION=ROW_VERSION+1 WHERE CARD_ID=?",target,id);
            audit("CARD_"+target,id,r.reason());return cardView(cardRow(id,false));
        });
    }

    @Transactional
    public Map<String,Object> purchase(String id,Purchase r) {
        lockActor();var c=cardRow(id,true);require(c,"CC_SPEND");
        return command(r.requestKey(),"purchase/"+id,r,()->{
            state(c,"ACTIVE");unexpired(c);eligible(cif(str(c,"CIF_ID"),false));currentCycle(c);accrue(c,today());
            if(overdue(c).signum()>0)throw conflict("MINIMUM_PAYMENT_OVERDUE","Pay the overdue minimum payment before making another purchase");
            BigDecimal exposure=balance(c).add(cents(money(c,"ACCRUED_INTEREST")).max(ZERO));
            if(exposure.add(r.amount()).compareTo(money(c,"CREDIT_LIMIT"))>0)throw conflict("CREDIT_LIMIT_EXCEEDED","Purchase exceeds available credit");
            long entry=post(c,"PURCHASE",r.amount(),r.amount(),ZERO,ZERO,r.merchantName(),today(),null,null,
                List.of(line(gl("CC_RECEIVABLE","ASSET"),null,"DR",r.amount(),"Card receivable"),line(gl("CC_SETTLEMENT","LIABILITY"),null,"CR",r.amount(),"Simulated merchant settlement")));
            audit("PURCHASE_POSTED",id,"SIMULATED_SETTLEMENT");return entry(entry);
        });
    }

    @Transactional
    public Map<String,Object> repay(String id,Repayment r) {
        lockActor();var c=cardRow(id,true);require(c,"CC_REPAY");
        // Recheck account ownership even on retries; a request key never grants authority.
        long account=number(c,"REPAYMENT_ACCOUNT_ID");var a=banking.account("TXN_POST",account);
        return command(r.requestKey(),"repayment/"+id,r,()->{
            notClosed(c);currentCycle(c);requireRepaymentAccount(account,str(c,"CIF_ID"),false);accrue(c,today());postInterest(c,today());
            if(r.amount().compareTo(balance(c))>0)throw conflict("OVERPAYMENT","Repayment exceeds the outstanding balance including accrued interest");
            BigDecimal interest=r.amount().min(money(c,"INTEREST_BALANCE"));BigDecimal principal=r.amount().subtract(interest);
            long entry=post(c,"REPAYMENT",r.amount(),principal.negate(),interest.negate(),ZERO,"Card repayment",today(),null,account,
                List.of(line(depositGl(a),account,"DR",r.amount(),"Credit card repayment"),line(gl("CC_RECEIVABLE","ASSET"),null,"CR",r.amount(),"Card receivable repaid")));
            audit("REPAYMENT_POSTED",id,"DEPOSIT_ACCOUNT_DEBITED");return entry(entry);
        });
    }

    @Transactional
    public Map<String,Object> refund(String id,long purchaseId,Refund r) {
        staff();lockActor();var c=cardRow(id,true);require(c,"CC_SERVICE");
        return command(r.requestKey(),"refund/"+id+"/"+purchaseId,r,()->{
            notClosed(c);currentCycle(c);var purchase=entry(purchaseId);
            if(!id.equals(purchase.get("CARD_ID"))||!"PURCHASE".equals(purchase.get("ENTRY_TYPE")))throw conflict("INVALID_PURCHASE","Select a purchase on this card");
            if(db.count("SELECT COUNT(*) FROM M11_CC_ENTRY WHERE REFUND_OF_ENTRY=?",purchaseId)>0)throw conflict("ALREADY_REFUNDED","This purchase has already been refunded");
            accrue(c,today());BigDecimal amount=money(purchase,"AMOUNT"),principal=amount.min(money(c,"PRINCIPAL_BALANCE")),cash=amount.subtract(principal);
            List<JournalLine> lines=new ArrayList<>();lines.add(line(gl("CC_SETTLEMENT","LIABILITY"),null,"DR",amount,"Simulated merchant refund"));
            if(principal.signum()>0)lines.add(line(gl("CC_RECEIVABLE","ASSET"),null,"CR",principal,"Card principal refund"));
            Long account=null;
            if(cash.signum()>0){account=number(c,"REPAYMENT_ACCOUNT_ID");var a=requireRepaymentAccount(account,str(c,"CIF_ID"),false);lines.add(line(depositGl(a),account,"CR",cash,"Excess card refund"));}
            long entry=post(c,"REFUND",amount,principal.negate(),ZERO,cash,r.reason(),today(),purchaseId,account,lines);
            audit("PURCHASE_REFUNDED",id,"SIMULATED_SETTLEMENT");return entry(entry);
        });
    }

    @Transactional
    public Map<String,Object> bill(String id,Billing r) {
        lockActor();var c=cardRow(id,true);require(c,"CC_MANAGE");
        return command(r.requestKey(),"billing/"+id,r,()->{
            // A second request key for an already issued period returns the immutable snapshot.
            var prior=db.rows("SELECT * FROM M11_CC_STATEMENT WHERE CARD_ID=? AND STATEMENT_DATE=?",id,r.statementDate());
            if(!prior.isEmpty())return prior.get(0);
            notClosed(c);LocalDate date=localDate(c.get("NEXT_STATEMENT_DATE"));
            if(!date.equals(r.statementDate())||date.isAfter(today()))throw conflict("BILLING_DATE","Generate the next scheduled statement on or after its billing date");
            accrue(c,date);postInterest(c,date);
            long from=number(c,"LAST_STATEMENT_ENTRY");
            long to=db.count("SELECT COALESCE(MAX(ENTRY_ID),0) FROM M11_CC_ENTRY WHERE CARD_ID=?",id);
            var totals=db.one("""
                SELECT COALESCE(SUM(CASE WHEN ENTRY_TYPE='PURCHASE' THEN AMOUNT ELSE 0 END),0) PURCHASES,
                  COALESCE(SUM(CASE WHEN ENTRY_TYPE='REFUND' THEN -PRINCIPAL_DELTA ELSE 0 END),0) REFUNDS,
                  COALESCE(SUM(CASE WHEN ENTRY_TYPE='REPAYMENT' THEN AMOUNT ELSE 0 END),0) REPAYMENTS,
                  COALESCE(SUM(CASE WHEN ENTRY_TYPE='INTEREST' THEN AMOUNT ELSE 0 END),0) INTEREST
                FROM M11_CC_ENTRY WHERE CARD_ID=? AND ENTRY_ID>? AND ENTRY_ID<=?
                """,id,from,to);
            BigDecimal closing=balance(c),opening=closing.subtract(money(totals,"PURCHASES")).subtract(money(totals,"INTEREST")).add(money(totals,"REFUNDS")).add(money(totals,"REPAYMENTS"));
            var p=product(str(c,"PRODUCT_ID"),false);String statement=uuid();
            db.jdbc().update("""
                INSERT INTO M11_CC_STATEMENT(STATEMENT_ID,CARD_ID,STATEMENT_DATE,DUE_DATE,FROM_ENTRY_EXCLUSIVE,TO_ENTRY_INCLUSIVE,
                  OPENING_BALANCE,PURCHASE_AMOUNT,REFUND_AMOUNT,REPAYMENT_AMOUNT,INTEREST_AMOUNT,CLOSING_BALANCE,MINIMUM_DUE)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,statement,id,date,date.plusDays(number(p,"PAYMENT_DUE_DAYS")),from,to,opening,totals.get("PURCHASES"),totals.get("REFUNDS"),totals.get("REPAYMENTS"),totals.get("INTEREST"),closing,minimum(closing,money(p,"MIN_PAYMENT_PCT"),money(p,"MIN_PAYMENT_FLOOR")));
            db.jdbc().update("UPDATE M11_CC_CARD SET NEXT_STATEMENT_DATE=?,LAST_STATEMENT_ENTRY=?,ROW_VERSION=ROW_VERSION+1 WHERE CARD_ID=?",date.plusMonths(1),to,id);
            audit("STATEMENT_ISSUED",id,date.toString());return db.one("SELECT * FROM M11_CC_STATEMENT WHERE STATEMENT_ID=?",statement);
        });
    }

    public List<Map<String,Object>> entries(String id,long after,int limit) {
        var c=cardRow(id,false);require(c,"CC_READ");audit("ENTRIES_VIEWED",id,"AUTHORIZED_READ");
        return db.rows("SELECT * FROM M11_CC_ENTRY WHERE CARD_ID=? AND ENTRY_ID>? ORDER BY ENTRY_ID FETCH FIRST ? ROWS ONLY",id,after,limit);
    }

    public List<Map<String,Object>> statements(String id) {
        var c=cardRow(id,false);require(c,"CC_READ");
        var rows=db.rows("SELECT * FROM M11_CC_STATEMENT WHERE CARD_ID=? ORDER BY STATEMENT_DATE DESC FETCH FIRST 120 ROWS ONLY",id);
        rows.forEach(s->s.put("REMAINING_MINIMUM_DUE",remainingMinimum(s,today())));return rows;
    }

    public Map<String,Object> statement(String id,String statementId) {
        var c=cardRow(id,false);require(c,"CC_READ");
        var s=db.one("SELECT * FROM M11_CC_STATEMENT WHERE CARD_ID=? AND STATEMENT_ID=?",id,statementId);
        s.put("ENTRIES",db.rows("SELECT * FROM M11_CC_ENTRY WHERE CARD_ID=? AND ENTRY_ID>? AND ENTRY_ID<=? ORDER BY ENTRY_ID",id,s.get("FROM_ENTRY_EXCLUSIVE"),s.get("TO_ENTRY_INCLUSIVE")));
        s.put("REMAINING_MINIMUM_DUE",remainingMinimum(s,today()));audit("STATEMENT_VIEWED",id,"AUTHORIZED_READ");return s;
    }

    public Map<String,Object> reconciliation(String id) {
        var c=cardRow(id,false);require(c,"CC_READ");
        var sums=db.one("SELECT COALESCE(SUM(PRINCIPAL_DELTA),0) PRINCIPAL,COALESCE(SUM(INTEREST_DELTA),0) INTEREST FROM M11_CC_ENTRY WHERE CARD_ID=?",id);
        BigDecimal glBalance=db.jdbc().queryForObject("""
            SELECT COALESCE(SUM(CASE WHEN P.ENTRY_SIDE='DR' THEN P.AMOUNT ELSE -P.AMOUNT END),0)
            FROM M11_CC_ENTRY E JOIN M05_GL_POSTING P ON P.JOURNAL_ID=E.JOURNAL_ID
            JOIN M05_GL_ACCOUNT G ON G.GL_ACCOUNT_ID=P.GL_ACCOUNT_ID WHERE E.CARD_ID=? AND G.GL_CODE='CC_RECEIVABLE'
            """,BigDecimal.class,id);
        return Map.of("cardId",id,"principal",money(c,"PRINCIPAL_BALANCE"),"interest",money(c,"INTEREST_BALANCE"),"ledgerReceivable",glBalance,
            "matched",money(sums,"PRINCIPAL").compareTo(money(c,"PRINCIPAL_BALANCE"))==0&&money(sums,"INTEREST").compareTo(money(c,"INTEREST_BALANCE"))==0&&glBalance.compareTo(balance(c))==0);
    }

    private void accrue(Map<String,Object> c,LocalDate date) {
        LocalDate from=localDate(c.get("LAST_ACCRUAL_DATE"));
        if(date.isBefore(from))throw conflict("BUSINESS_DATE","Cannot accrue before the last processed business date");
        var p=product(str(c,"PRODUCT_ID"),false);
        BigDecimal accrued=money(c,"ACCRUED_INTEREST").add(interest(money(c,"PRINCIPAL_BALANCE"),money(p,"ANNUAL_RATE_PCT"),from,date));
        db.jdbc().update("UPDATE M11_CC_CARD SET ACCRUED_INTEREST=?,LAST_ACCRUAL_DATE=? WHERE CARD_ID=?",accrued,date,c.get("CARD_ID"));
        c.put("ACCRUED_INTEREST",accrued);c.put("LAST_ACCRUAL_DATE",date);
    }

    private void postInterest(Map<String,Object> c,LocalDate date) {
        BigDecimal amount=cents(money(c,"ACCRUED_INTEREST"));
        if(amount.signum()<=0)return;
        post(c,"INTEREST",amount,ZERO,amount,ZERO,"Simple daily interest (ACT/365)",date,null,null,
            List.of(line(gl("CC_RECEIVABLE","ASSET"),null,"DR",amount,"Card interest receivable"),line(gl("CC_INTEREST","INCOME"),null,"CR",amount,"Card interest income")));
        BigDecimal residual=money(c,"ACCRUED_INTEREST").subtract(amount);
        db.jdbc().update("UPDATE M11_CC_CARD SET ACCRUED_INTEREST=? WHERE CARD_ID=?",residual,c.get("CARD_ID"));c.put("ACCRUED_INTEREST",residual);
    }

    private long post(Map<String,Object> c,String type,BigDecimal amount,BigDecimal principal,BigDecimal interest,BigDecimal cash,
                      String description,LocalDate date,Long refundOf,Long account,List<JournalLine> lines) {
        String key="cc:"+uuid(),txnType="CARD_"+type;
        Long source="REPAYMENT".equals(type)?account:null,target="REFUND".equals(type)?account:null;
        long txn=insert("TXN_ID","INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID,AMOUNT,VALUE_DATE) VALUES (?,'CARD',?,?,?,?, 'POSTED',?,?,?,?)",actor(),key,CustomerHashService.digest(key),key,txnType,source,target,amount,date);
        long journal;
        try { journal=ledger.postJournal(new JournalRequest(key,txnType,txn,null,null,null,null,null,date,description,lines),actor()).journalId(); }
        catch(com.moneybags.txn.api.ApiException e){throw new BusinessException(e.status(),e.code(),e.getMessage());}
        BigDecimal newPrincipal=money(c,"PRINCIPAL_BALANCE").add(principal),newInterest=money(c,"INTEREST_BALANCE").add(interest);
        long id=insert("ENTRY_ID","""
            INSERT INTO M11_CC_ENTRY(CARD_ID,ENTRY_TYPE,AMOUNT,PRINCIPAL_DELTA,INTEREST_DELTA,DEPOSIT_CREDIT,PRINCIPAL_AFTER,
              INTEREST_AFTER,DESCRIPTION,BUSINESS_DATE,TXN_ID,JOURNAL_ID,REFUND_OF_ENTRY,ACTOR_ID) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,c.get("CARD_ID"),type,amount,principal,interest,cash,newPrincipal,newInterest,description,date,txn,journal,refundOf,actor());
        db.jdbc().update("UPDATE M11_CC_CARD SET PRINCIPAL_BALANCE=?,INTEREST_BALANCE=?,ROW_VERSION=ROW_VERSION+1 WHERE CARD_ID=?",newPrincipal,newInterest,c.get("CARD_ID"));
        db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,NULL,'POSTED',?,'CREDIT_CARD')",txn,actor());
        c.put("PRINCIPAL_BALANCE",newPrincipal);c.put("INTEREST_BALANCE",newInterest);return id;
    }

    private Map<String,Object> product(String id,boolean lock){return db.one("SELECT * FROM M11_CC_PRODUCT WHERE PRODUCT_ID=?"+(lock?" FOR UPDATE":""),id);}
    private Map<String,Object> application(String id,boolean lock){return db.one("SELECT * FROM M11_CC_APPLICATION WHERE APPLICATION_ID=?"+(lock?" FOR UPDATE":""),id);}
    private Map<String,Object> cardRow(String id,boolean lock){return db.one("SELECT * FROM M11_CC_CARD WHERE CARD_ID=?"+(lock?" FOR UPDATE":""),id);}
    private Map<String,Object> entry(long id){return db.one("SELECT * FROM M11_CC_ENTRY WHERE ENTRY_ID=?",id);}
    private Map<String,Object> cif(String id,boolean lock){return db.one("SELECT * FROM M02_CIF_CUSTOMER WHERE CIF_ID=?"+(lock?" FOR UPDATE":""),id);}

    private Map<String,Object> productView(Map<String,Object> p) {
        var result=new LinkedHashMap<>(p);
        if(customer()){result.remove("MAKER_ID");result.remove("CHECKER_ID");result.remove("DECISION_REASON");}
        result.put("INTEREST_POLICY","Simple ACT/365 on outstanding principal from purchase; no interest-free grace period or compounding. No annual, late, or over-limit fees.");
        return result;
    }

    private Map<String,Object> cardView(Map<String,Object> c) {
        var result=new LinkedHashMap<>(c);var p=product(str(c,"PRODUCT_ID"),false);
        for(String key:List.of("ISSUED_DATE","EXPIRY_DATE","NEXT_STATEMENT_DATE","LAST_ACCRUAL_DATE"))result.put(key,localDate(c.get(key)));
        LocalDate asOf=today().isBefore(localDate(c.get("NEXT_STATEMENT_DATE")))?today():localDate(c.get("NEXT_STATEMENT_DATE"));
        LocalDate accrualFrom=localDate(c.get("LAST_ACCRUAL_DATE"));
        BigDecimal projected=asOf.isAfter(accrualFrom)
            ? interest(money(c,"PRINCIPAL_BALANCE"),money(p,"ANNUAL_RATE_PCT"),accrualFrom,asOf)
            : ZERO;
        BigDecimal accrued=money(c,"ACCRUED_INTEREST").add(projected);
        result.put("PRODUCT_NAME",p.get("PRODUCT_NAME"));result.put("CURRENCY_CODE","INR");result.put("ANNUAL_RATE_PCT",p.get("ANNUAL_RATE_PCT"));
        result.put("OUTSTANDING_BALANCE",balance(c));result.put("UNBILLED_INTEREST",cents(accrued).max(ZERO));
        result.put("PAYOFF_AMOUNT",balance(c).add(cents(accrued).max(ZERO)));
        result.put("AVAILABLE_CREDIT",money(c,"CREDIT_LIMIT").subtract(balance(c)).subtract(cents(accrued).max(ZERO)).max(ZERO));
        result.put("OVERDUE_MINIMUM",overdue(c));result.put("BILLING_REQUIRED",!"CLOSED".equals(c.get("STATUS"))&&!localDate(c.get("NEXT_STATEMENT_DATE")).isAfter(today()));
        result.put("SIMULATED",true);result.put("TERMS",productView(p));return result;
    }

    private BigDecimal remainingMinimum(Map<String,Object> s,LocalDate through) {
        BigDecimal credits=db.jdbc().queryForObject("SELECT COALESCE(SUM(-PRINCIPAL_DELTA-INTEREST_DELTA),0) FROM M11_CC_ENTRY WHERE CARD_ID=? AND ENTRY_ID>? AND BUSINESS_DATE<=? AND ENTRY_TYPE IN ('REPAYMENT','REFUND')",BigDecimal.class,s.get("CARD_ID"),s.get("TO_ENTRY_INCLUSIVE"),through);
        return money(s,"MINIMUM_DUE").subtract(credits).max(ZERO);
    }
    private BigDecimal overdue(Map<String,Object> c) {
        // Rolled balances appear in later statements; use the largest unpaid requirement, never sum it twice.
        return db.rows("SELECT * FROM M11_CC_STATEMENT WHERE CARD_ID=? AND DUE_DATE<?",c.get("CARD_ID"),today()).stream()
            .map(s->remainingMinimum(s,today())).max(BigDecimal::compareTo).orElse(ZERO);
    }

    private void eligible(Map<String,Object> c) {
        if(!"ACTIVE".equals(c.get("STATUS"))||!"VERIFIED".equals(c.get("KYC_STATUS")))throw conflict("CIF_NOT_ELIGIBLE","An active customer with verified KYC is required");
        if(c.get("NEXT_REVIEW_DUE_AT")!=null&&!localDate(c.get("NEXT_REVIEW_DUE_AT")).isAfter(today()))throw conflict("KYC_REVIEW_DUE","Complete the customer's KYC review first");
        var party=db.one("SELECT PARTY_TYPE,DATE_OF_BIRTH FROM M02_CIF_PARTY WHERE PARTY_ID=?",c.get("PARTY_ID"));
        LocalDate born=localDate(party.get("DATE_OF_BIRTH"));
        if(!"INDIVIDUAL".equals(party.get("PARTY_TYPE"))||born==null||born.plusYears(18).isAfter(today()))throw conflict("ADULT_INDIVIDUAL_REQUIRED","This credit card is available to adult individual customers");
    }

    private Map<String,Object> requireRepaymentAccount(long id,String cif,boolean checkActor) {
        var a=db.one("SELECT A.*,P.PRODUCT_TYPE FROM M04_BANK_ACCOUNT A JOIN M03_PM_PRODUCT P ON P.PRODUCT_ID=A.PRODUCT_ID WHERE A.ACCOUNT_ID=?",id);
        if(checkActor)banking.account("ACCOUNT_READ",id);
        if(!cif.equals(a.get("PRIMARY_CIF_ID"))||!"ACTIVE".equals(a.get("LIFECYCLE_STATUS"))||!"SELF_OPERATED".equals(a.get("ACCOUNT_OPERATION_MODE"))||!Set.of("SAVINGS","CURRENT").contains(a.get("PRODUCT_TYPE")))
            throw conflict("REPAYMENT_ACCOUNT","Use an active, self-operated savings/current account belonging to the applicant");
        return a;
    }
    private long depositGl(Map<String,Object> a) {
        var rows=db.rows("SELECT GL_ACCOUNT_ID FROM M05_GL_PRODUCT_MAPPING WHERE PRODUCT_VERSION_ID=? AND GL_ROLE_CODE='CUSTOMER_LIABILITY' AND POSTING_TYPE='TRANSFER' AND EFFECTIVE_FROM<=? AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>?)",a.get("PRODUCT_VERSION_ID"),today(),today());
        if(rows.size()!=1)throw conflict("GL_MAPPING_REQUIRED","Configure one effective customer liability mapping for the repayment account");
        return number(rows.get(0),"GL_ACCOUNT_ID");
    }
    private long gl(String code,String accountClass) {
        var row=db.one("SELECT GL_ACCOUNT_ID,ACCOUNT_CLASS,ACTIVE_FLAG,CURRENCY_CODE FROM M05_GL_ACCOUNT WHERE GL_CODE=?",code);
        if(!accountClass.equals(row.get("ACCOUNT_CLASS"))||!"Y".equals(row.get("ACTIVE_FLAG"))||!"INR".equals(row.get("CURRENCY_CODE")))throw conflict("CARD_GL_CONFIGURATION","Card general ledger account is inactive or has an incompatible class/currency");
        return number(row,"GL_ACCOUNT_ID");
    }
    private JournalLine line(long gl,Long account,String side,BigDecimal amount,String description){return new JournalLine(gl,account,null,null,side,amount,description);}
    private void range(BigDecimal limit,Map<String,Object> p){if(limit.compareTo(money(p,"MINIMUM_LIMIT"))<0||limit.compareTo(money(p,"MAXIMUM_LIMIT"))>0)throw conflict("LIMIT_OUTSIDE_PRODUCT","Requested limit must be within the product's approved range");}
    private void currentCycle(Map<String,Object> c){if(!localDate(c.get("NEXT_STATEMENT_DATE")).isAfter(today()))throw conflict("BILLING_REQUIRED","Generate all due card statements before posting another transaction");}
    private void unexpired(Map<String,Object> c){if(!localDate(c.get("EXPIRY_DATE")).isAfter(today()))throw conflict("CARD_EXPIRED","This card has expired; repayment and refunds remain available");}
    private void notClosed(Map<String,Object> c){if("CLOSED".equals(c.get("STATUS")))throw transition();}
    private void state(Map<String,Object> row,String status){if(!status.equals(row.get("STATUS")))throw transition();}
    private BusinessException transition(){return conflict("INVALID_CARD_STATE","This action is not available in the current status");}
    private void independent(String maker){if(actor().equals(maker))throw conflict("MAKER_CHECKER","A different employee must approve or reject this request");}
    private void staff(){if(!"EMPLOYEE".equals(CurrentActor.get().userType()))throw new BusinessException(HttpStatus.FORBIDDEN,"STAFF_ONLY","This action requires an authorized employee");}
    private void staffGlobal(String permission){staff();banking.global(permission);}
    private void require(Map<String,Object> row,String permission){require(permission,str(row,"BRANCH_CODE"),str(row,"CIF_ID"));}
    private void require(String permission,String branch,String cif){
        if(customer()&&!owns(cif))throw new BusinessException(HttpStatus.FORBIDDEN,"NOT_CARD_HOLDER","This credit card is outside your access");
        access.require(CurrentActor.get(),context(permission,branch,cif));
    }
    private AuthorizationInput context(String permission,String branch,String cif){return new AuthorizationInput(permission,branch,null,cif,"CREDIT_CARD","INR",null,null,null,null);}
    private boolean allowed(Map<String,Object> row,String permission){return (!customer()||owns(str(row,"CIF_ID")))&&access.allowed(CurrentActor.get(),context(permission,str(row,"BRANCH_CODE"),str(row,"CIF_ID")));}
    private boolean owns(String cif){return db.count("SELECT COUNT(*) FROM M01_IAM_CUSTOMER_LINK WHERE USER_ID=? AND CIF_ID=? AND STATUS='ACTIVE' AND VALID_FROM<=? AND (VALID_TO IS NULL OR VALID_TO>?)",actor(),cif,OffsetDateTime.now(clock),OffsetDateTime.now(clock))>0;}
    private boolean customer(){return "CUSTOMER".equals(CurrentActor.get().userType());}
    private String actor(){return CurrentActor.get().userId();}
    private LocalDate today(){return LocalDate.now(clock.withZone(businessZone));}
    private String uuid(){return UUID.randomUUID().toString();}
    private BigDecimal money(Map<String,Object> row,String key){return new BigDecimal(row.get(key).toString());}
    private BigDecimal balance(Map<String,Object> c){return money(c,"PRINCIPAL_BALANCE").add(money(c,"INTEREST_BALANCE"));}
    private BusinessException conflict(String code,String message){return new BusinessException(HttpStatus.CONFLICT,code,message);}
    private void audit(String action,String id,String reason){audit.audit("CC_"+action,"CREDIT_CARD",id,reason.length()>100?reason.substring(0,100):reason);}

    /** Serializes request keys for an actor before resource locks. No in-process-only mutexes. */
    private void lockActor(){db.one("SELECT USER_ID FROM M01_IAM_USER WHERE USER_ID=? FOR UPDATE",actor());}
    private Map<String,Object> command(String key,String operation,Object payload,Supplier<Map<String,Object>> action) {
        String hash=HexFormat.of().formatHex(CustomerHashService.digest(operation+":"+canonical(json.valueToTree(payload))));
        var old=db.rows("SELECT REQUEST_HASH,RESPONSE_JSON FROM M11_CC_REQUEST WHERE ACTOR_ID=? AND REQUEST_KEY=?",actor(),key);
        if(!old.isEmpty()){
            if(!hash.equals(old.get(0).get("REQUEST_HASH")))throw conflict("IDEMPOTENCY_CONFLICT","Request reference was already used for a different command");
            try{return json.readValue(str(old.get(0),"RESPONSE_JSON"),new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("Stored card response is unreadable",e);}
        }
        var result=action.get();
        try{db.jdbc().update("INSERT INTO M11_CC_REQUEST(ACTOR_ID,REQUEST_KEY,REQUEST_HASH,RESPONSE_JSON) VALUES (?,?,?,?)",actor(),key,hash,json.writeValueAsString(result));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Card response cannot be serialized",e);}
        return result;
    }
    private String canonical(JsonNode node) {
        if(node.isNumber())return "n:"+new BigDecimal(node.asText()).stripTrailingZeros().toPlainString();
        if(node.isObject()){TreeMap<String,String> values=new TreeMap<>();node.fields().forEachRemaining(e->values.put(e.getKey(),canonical(e.getValue())));return values.toString();}
        if(node.isArray()){List<String> values=new ArrayList<>();node.forEach(n->values.add(canonical(n)));return values.toString();}
        return node.toString();
    }
    private long insert(String column,String sql,Object... values){
        var holder=new GeneratedKeyHolder();db.jdbc().update(connection->{var statement=connection.prepareStatement(sql,new String[]{column});for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);return statement;},holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
}
