package com.moneybags.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.common.api.BusinessException;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.integration.BankingAccess;
import com.moneybags.integration.BankingOperationsController;
import com.moneybags.integration.BankingService;
import com.moneybags.integration.CurrentActor;
import com.moneybags.integration.CustomerHashService;
import com.moneybags.integration.PaymentWorkflowController;
import com.moneybags.txn.api.Contracts.TransferRequest;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;

/** A model may prepare an action. Only a separate, exact UI confirmation can execute it. */
@Service
public class AssistantIntentService {
    private final BusinessRepository db;
    private final BankingAccess access;
    private final PaymentWorkflowController payments;
    private final BankingService banking;
    private final BankingOperationsController operations;
    private final CustomerHashService audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean simulation;
    private final Set<String> internalBankCodes;

    public AssistantIntentService(BusinessRepository db, BankingAccess access,
            PaymentWorkflowController payments, BankingService banking, BankingOperationsController operations,
            CustomerHashService audit, ObjectMapper json, Clock clock,
            @Value("${moneybags.payment-simulation.enabled:false}") boolean simulation,
            @Value("${moneybags.internal-bank-codes:}") String bankCodes) {
        this.db=db; this.access=access; this.payments=payments; this.banking=banking; this.operations=operations;
        this.audit=audit; this.json=json; this.clock=clock; this.simulation=simulation;
        this.internalBankCodes=Arrays.stream(bankCodes.split(",")).map(String::trim).filter(s->!s.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Transactional
    public Map<String,Object> draftPayment(long accountId, String beneficiaryId, String rail, BigDecimal amount) {
        customer();
        if (amount==null || amount.signum()<=0 || amount.scale()>2 || amount.precision()>18 ||
                !Set.of("UPI","IMPS","NEFT","RTGS").contains(rail)) invalid();
        if(simulation && (("RTGS".equals(rail) && amount.compareTo(new BigDecimal("200000"))<0) ||
                (Set.of("UPI","IMPS").contains(rail) && amount.compareTo(new BigDecimal("100000"))>0)))
            throw new BusinessException(HttpStatus.BAD_REQUEST,"RAIL_AMOUNT_LIMIT",
                    "In this demo, RTGS starts at INR 200000 and UPI/IMPS are limited to INR 100000");
        Map<String,Object> account=access.account("PAYMENT_CREATE",accountId);
        Map<String,Object> beneficiary=db.one("SELECT BENEFICIARY_ID,DISPLAY_NAME,BANK_CODE,STATUS FROM MBX_BENEFICIARY WHERE BENEFICIARY_ID=? AND OWNER_USER_ID=? AND STATUS='ACTIVE'",
                beneficiaryId,CurrentActor.get().userId());
        if(internalTarget(beneficiaryId)!=null)
            throw new BusinessException(HttpStatus.CONFLICT,"USE_INTERNAL_TRANSFER",
                    "This beneficiary has a Moneybags account; use an internal transfer to credit it");
        String id=UUID.randomUUID().toString();
        Map<String,Object> payload=new java.util.LinkedHashMap<>();
        payload.put("accountId",accountId);payload.put("beneficiaryId",beneficiaryId);
        payload.put("rail",rail);payload.put("amount",amount.toPlainString());
        save(id,"PAYMENT_INITIATE",payload);
        audit.audit("ASSISTANT_INTENT_CREATED","ASSISTANT_INTENT",id,"PAYMENT_INITIATE");
        return Map.of("intentId",id,"action","PAYMENT_INITIATE","expiresAt",OffsetDateTime.now(clock).plusMinutes(5),
                "summary","Submit "+amount.toPlainString()+" INR by "+rail+" from account ending "+last4(account.get("ACCOUNT_NUMBER"))+
                        " to "+beneficiary.get("DISPLAY_NAME")+" ("+beneficiary.get("BANK_CODE")+"). Payment remains pending bank authorization.");
    }

    @Transactional
    public Map<String,Object> draftInternalTransfer(long accountId,String beneficiaryId,BigDecimal amount) {
        customer();
        CurrentActor.require("PAYMENT_CREATE");
        CurrentActor.require("TXN_POST");
        validAmount(amount);
        Map<String,Object> source=access.account("TXN_POST",accountId);
        Map<String,Object> beneficiary=activeBeneficiary(beneficiaryId);
        Map<String,Object> target=internalTarget(beneficiaryId);
        if(target==null)throw new BusinessException(HttpStatus.CONFLICT,"NOT_INTERNAL_BENEFICIARY",
                "This beneficiary is not linked to a Moneybags account");
        long targetId=((Number)target.get("ACCOUNT_ID")).longValue();
        if(targetId==accountId)throw new BusinessException(HttpStatus.BAD_REQUEST,"SAME_ACCOUNT",
                "Choose a different recipient account");
        if(!"ACTIVE".equals(target.get("LIFECYCLE_STATUS")) || !"INR".equals(target.get("CURRENCY_CODE")))
            throw new BusinessException(HttpStatus.CONFLICT,"TARGET_NOT_ACTIVE","Recipient account cannot receive INR transfers");
        String id=UUID.randomUUID().toString();
        save(id,"INTERNAL_TRANSFER",Map.of("accountId",accountId,"beneficiaryId",beneficiaryId,
                "targetAccountId",targetId,"amount",amount.toPlainString()));
        audit.audit("ASSISTANT_INTENT_CREATED","ASSISTANT_INTENT",id,"INTERNAL_TRANSFER");
        return Map.of("intentId",id,"action","INTERNAL_TRANSFER","expiresAt",OffsetDateTime.now(clock).plusMinutes(5),
                "summary","Transfer "+amount.toPlainString()+" INR from account ending "+last4(source.get("ACCOUNT_NUMBER"))+
                        " to "+beneficiary.get("DISPLAY_NAME")+" at Moneybags account ending "+last4(target.get("ACCOUNT_NUMBER"))+
                        ". Confirming posts the transfer and credits the recipient account.");
    }

    @Transactional
    public Map<String,Object> draftBeneficiaryVerification(String beneficiaryId) {
        employee();
        access.global("BENEFICIARY_VERIFY");
        Map<String,Object> beneficiary=db.one("SELECT BENEFICIARY_ID,DISPLAY_NAME,BANK_CODE,STATUS,OWNER_USER_ID FROM MBX_BENEFICIARY WHERE BENEFICIARY_ID=?",beneficiaryId);
        if(!"PENDING".equals(beneficiary.get("STATUS")) || CurrentActor.get().userId().equals(beneficiary.get("OWNER_USER_ID")))
            throw new BusinessException(HttpStatus.CONFLICT,"OPERATION_NOT_ALLOWED","A different employee must verify a pending beneficiary");
        String id=UUID.randomUUID().toString();
        save(id,"BENEFICIARY_VERIFY",Map.of("beneficiaryId",beneficiaryId));
        audit.audit("ASSISTANT_INTENT_CREATED","ASSISTANT_INTENT",id,"BENEFICIARY_VERIFY");
        return Map.of("intentId",id,"action","BENEFICIARY_VERIFY","expiresAt",OffsetDateTime.now(clock).plusMinutes(5),
                "summary","Verify pending beneficiary "+beneficiary.get("DISPLAY_NAME")+" ("+beneficiary.get("BANK_CODE")+").");
    }

    @Transactional
    public Map<String,Object> confirm(String id) {
        Map<String,Object> intent=db.one("SELECT * FROM MBX_ASSISTANT_INTENT WHERE INTENT_ID=? FOR UPDATE",id);
        var actor=CurrentActor.get();
        if(!actor.userId().equals(intent.get("ACTOR_USER_ID")) || !actor.sessionId().equals(intent.get("SESSION_ID")))
            throw new BusinessException(HttpStatus.FORBIDDEN,"INTENT_OWNER","This action belongs to another session");
        if("COMPLETED".equals(intent.get("STATUS")))return Map.of("status","COMPLETED","resultRef",intent.get("RESULT_REF"),"action",intent.get("ACTION_CODE"));
        if(!"PENDING".equals(intent.get("STATUS")) || !((OffsetDateTime)intent.get("EXPIRES_AT")).isAfter(OffsetDateTime.now(clock)))
            throw new BusinessException(HttpStatus.CONFLICT,"INTENT_EXPIRED","This action has expired; start again");
        Map<String,Object> payload;
        try { payload=json.readValue((String)intent.get("PAYLOAD_JSON"),Map.class); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Stored action is invalid",e); }
        String result;
        if("PAYMENT_INITIATE".equals(intent.get("ACTION_CODE"))) {
            customer();
            long accountId=((Number)payload.get("accountId")).longValue();
            String beneficiaryId=(String)payload.get("beneficiaryId");
            access.account("PAYMENT_CREATE",accountId);
            activeBeneficiary(beneficiaryId);
            if(internalTarget(beneficiaryId)!=null)
                throw new BusinessException(HttpStatus.CONFLICT,"USE_INTERNAL_TRANSFER",
                        "This beneficiary has a Moneybags account; create an internal transfer draft instead");
            result=String.valueOf(payments.initiate(new PaymentWorkflowController.Initiate(accountId,beneficiaryId,
                    (String)payload.get("rail"),new BigDecimal((String)payload.get("amount")),(String)intent.get("REQUEST_KEY"))).paymentId());
        } else if("INTERNAL_TRANSFER".equals(intent.get("ACTION_CODE"))) {
            customer();
            CurrentActor.require("PAYMENT_CREATE");
            CurrentActor.require("TXN_POST");
            long accountId=((Number)payload.get("accountId")).longValue();
            String beneficiaryId=(String)payload.get("beneficiaryId");
            long targetId=((Number)payload.get("targetAccountId")).longValue();
            activeBeneficiary(beneficiaryId);
            Map<String,Object> target=internalTarget(beneficiaryId);
            if(target==null || ((Number)target.get("ACCOUNT_ID")).longValue()!=targetId)
                throw new BusinessException(HttpStatus.CONFLICT,"RECIPIENT_CHANGED","Recipient account changed; create a new transfer draft");
            access.account("TXN_POST",accountId);
            var posted=banking.transfer(new TransferRequest(accountId,targetId,
                    new BigDecimal((String)payload.get("amount")),"WEB",(String)intent.get("REQUEST_KEY"),id,
                    LocalDate.now(ZoneId.of("Asia/Kolkata"))));
            if(!"POSTED".equals(posted.status()))
                throw new BusinessException(HttpStatus.CONFLICT,"TRANSFER_NOT_POSTED","Transfer was not posted");
            result=Long.toString(posted.transactionId());
        } else if("BENEFICIARY_VERIFY".equals(intent.get("ACTION_CODE"))) {
            employee();
            result=operations.verify((String)payload.get("beneficiaryId")).get("beneficiaryId");
        } else throw new BusinessException(HttpStatus.BAD_REQUEST,"UNKNOWN_ACTION","Unsupported action");
        db.jdbc().update("UPDATE MBX_ASSISTANT_INTENT SET STATUS='COMPLETED',RESULT_REF=?,CONSUMED_AT=SYSTIMESTAMP WHERE INTENT_ID=?",result,id);
        audit.audit("ASSISTANT_INTENT_CONFIRMED","ASSISTANT_INTENT",id,(String)intent.get("ACTION_CODE"));
        return Map.of("status","COMPLETED","resultRef",result,"action",intent.get("ACTION_CODE"));
    }

    private Map<String,Object> activeBeneficiary(String beneficiaryId) {
        return db.one("SELECT BENEFICIARY_ID,DISPLAY_NAME,BANK_CODE,ACCOUNT_TOKEN FROM MBX_BENEFICIARY " +
                "WHERE BENEFICIARY_ID=? AND OWNER_USER_ID=? AND STATUS='ACTIVE'",
                beneficiaryId,CurrentActor.get().userId());
    }
    private Map<String,Object> internalTarget(String beneficiaryId) {
        var matches=db.rows("SELECT A.ACCOUNT_ID,A.ACCOUNT_NUMBER,A.LIFECYCLE_STATUS,A.CURRENCY_CODE,B.BANK_CODE " +
                "FROM MBX_BENEFICIARY B JOIN M04_BANK_ACCOUNT A ON A.ACCOUNT_NUMBER=B.ACCOUNT_TOKEN " +
                "WHERE B.BENEFICIARY_ID=? AND B.OWNER_USER_ID=? AND B.STATUS='ACTIVE'",
                beneficiaryId,CurrentActor.get().userId());
        return matches.isEmpty() || !internalBankCodes.contains(matches.get(0).get("BANK_CODE"))?null:matches.get(0);
    }
    private static void validAmount(BigDecimal amount) {
        if(amount==null || amount.signum()<=0 || amount.scale()>2 || amount.precision()>18)invalid();
    }

    private void save(String id,String action,Map<String,Object> payload) {
        try {
            db.jdbc().update("INSERT INTO MBX_ASSISTANT_INTENT(INTENT_ID,ACTOR_USER_ID,SESSION_ID,ACTION_CODE,PAYLOAD_JSON,STATUS,REQUEST_KEY,EXPIRES_AT) VALUES (?,?,?,?,?,'PENDING',?,?)",
                    id,CurrentActor.get().userId(),CurrentActor.get().sessionId(),action,json.writeValueAsString(payload),id,OffsetDateTime.now(clock).plusMinutes(5));
        } catch(JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private static String last4(Object value) { String s=String.valueOf(value);return s.substring(Math.max(0,s.length()-4)); }
    private static void customer() { if(!"CUSTOMER".equals(CurrentActor.get().userType())) denied(); }
    private static void employee() { if(!"EMPLOYEE".equals(CurrentActor.get().userType())) denied(); }
    private static void denied() { throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","This action is unavailable to your user type"); }
    private static void invalid() { throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_ACTION","Check the amount and payment rail"); }
}
