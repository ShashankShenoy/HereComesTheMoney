package com.moneybags.loan;

import tools.jackson.databind.json.JsonMapper;
import com.moneybags.common.api.*;
import com.moneybags.iam.dto.IamDtos.AuthorizationInput;
import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.iam.service.AccessDecisionService;
import com.moneybags.loan.LoanDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Module 8 owns the loan workflow; it never posts a journal or changes an account balance. */
@Service
public class LoanService {
    private final JdbcTemplate db;
    private final AccessDecisionService access;
    private final JsonMapper json;
    private final Clock clock;

    public LoanService(JdbcTemplate db, AccessDecisionService access, JsonMapper json, Clock clock) {
        this.db=db; this.access=access; this.json=json; this.clock=clock;
    }

    /** Returns a bounded, branch-scoped operations queue. */
    public List<Application> applications(UserPrincipal actor,String branch,String status,int limit) {
        require(actor,"LOAN_READ",branch,null,null,null,null,null);
        if(limit<1||limit>100)throw bad("limit must be between 1 and 100");
        return db.query("""
            SELECT * FROM M08_LOAN_APPLICATION WHERE BRANCH_CODE=?
            AND (? IS NULL OR STATUS=?) ORDER BY CREATED_AT DESC FETCH FIRST ? ROWS ONLY
            """,(rs,n)->application(rs),branch,status,status,limit);
    }

    /** Reads one application after evaluating its actual branch and CIF scope. */
    public Application application(UserPrincipal actor,long id) {
        Application a=load(id,false);
        require(actor,"LOAN_READ",a.branchCode(),a.cifId(),null,null,null,null);
        return a;
    }

    /** Returns a compact underwriting trail to authorized loan officers. */
    public List<Map<String,Object>> assessments(UserPrincipal actor,long id) {
        Application a=load(id,false);
        require(actor,"LOAN_ASSESS",a.branchCode(),a.cifId(),null,null,null,null);
        return db.queryForList("""
            SELECT ASSESSMENT_ID,APPLICATION_REVISION_ID,ASSESSMENT_TYPE,MONTHLY_INCOME,
              MONTHLY_OBLIGATIONS,DEBT_TO_INCOME_PCT,CREDIT_SCORE,RECOMMENDATION,REASON_CODES,
              CREDIT_EVIDENCE_REF,ASSESSED_BY_USER_ID,ASSESSED_AT
            FROM M08_LOAN_ASSESSMENT WHERE APPLICATION_ID=? ORDER BY ASSESSED_AT DESC FETCH FIRST 100 ROWS ONLY
            """,id);
    }

    /** Returns decision terms and authority code without exposing session identifiers. */
    public List<Map<String,Object>> decisions(UserPrincipal actor,long id) {
        Application a=load(id,false);
        require(actor,"LOAN_READ",a.branchCode(),a.cifId(),null,null,null,null);
        return db.queryForList("""
            SELECT DECISION_ID,APPLICATION_REVISION_ID,ASSESSMENT_ID,DECISION_CODE,SANCTIONED_AMOUNT,
              SANCTIONED_TENURE_MONTHS,INTEREST_TYPE,ANNUAL_RATE_PCT,REASON_CODES,CHECKER_USER_ID,
              APPROVAL_AUTHORITY_CODE,DECIDED_AT
            FROM M08_LOAN_DECISION WHERE APPLICATION_ID=? ORDER BY DECIDED_AT DESC FETCH FIRST 100 ROWS ONLY
            """,id);
    }

    /** Lists offer status and dates; document storage references stay private. */
    public List<Map<String,Object>> offers(UserPrincipal actor,long id) {
        Application a=load(id,false);
        require(actor,"LOAN_READ",a.branchCode(),a.cifId(),null,null,null,null);
        return db.queryForList("""
            SELECT OFFER_ID,OFFER_NUMBER,DECISION_ID,STATUS,ISSUED_AT,EXPIRES_AT,ACCEPTED_AT
            FROM M08_LOAN_OFFER WHERE APPLICATION_ID=? ORDER BY ISSUED_AT DESC FETCH FIRST 100 ROWS ONLY
            """,id);
    }

    /** Lists document metadata needed to complete acceptance and verification. */
    public List<Map<String,Object>> documents(UserPrincipal actor,long id) {
        Application a=load(id,false);
        require(actor,"LOAN_ASSESS",a.branchCode(),a.cifId(),null,null,null,null);
        return db.queryForList("""
            SELECT DOCUMENT_REF_ID,DOCUMENT_TYPE,CONTENT_HASH,STATUS,VERIFIED_BY_USER_ID,VERIFIED_AT,CREATED_AT
            FROM M08_LOAN_DOCUMENT_REF WHERE APPLICATION_ID=? ORDER BY CREATED_AT DESC FETCH FIRST 100 ROWS ONLY
            """,id);
    }

    /** Starts a draft only when CIF/KYC and a live loan product version permit it. */
    @Transactional
    public Application create(UserPrincipal actor,CreateApplication input,String key) {
        require(actor,"LOAN_CREATE",input.branchCode(),input.cifId(),null,null,null,null);
        requireKey(key);
        byte[] requestHash=sha256(toJson(input));
        List<Map<String,Object>> previous=db.queryForList("""
            SELECT REQUEST_HASH,STATUS,RESOURCE_ID FROM M08_LOAN_IDEMPOTENCY_RECORD
            WHERE ACTOR_ID=? AND OPERATION_CODE='CREATE_APPLICATION' AND IDEMPOTENCY_KEY=?
            """,actor.userId(),key);
        if(!previous.isEmpty()) {
            Map<String,Object> replay=previous.get(0);
            if(!Arrays.equals(requestHash,(byte[])replay.get("REQUEST_HASH")))throw conflict("IDEMPOTENCY_MISMATCH","This key was used for a different application");
            if(!"SUCCEEDED".equals(replay.get("STATUS")))throw conflict("REQUEST_IN_PROGRESS","Request has not completed");
            return load(Long.parseLong((String)replay.get("RESOURCE_ID")),false);
        }
        List<Map<String,Object>> customers=db.queryForList("SELECT STATUS,KYC_STATUS FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",input.cifId());
        if(customers.isEmpty()||!"ACTIVE".equals(customers.get(0).get("STATUS"))||!"VERIFIED".equals(customers.get(0).get("KYC_STATUS")))
            throw conflict("CIF_NOT_ELIGIBLE","Customer must be active with verified KYC");
        List<Map<String,Object>> kyc=db.queryForList("""
            SELECT CASE_ID,DECIDED_AT FROM M02_KYC_CASE WHERE CIF_ID=? AND STATUS='APPROVED' AND DECIDED_AT IS NOT NULL
            ORDER BY DECIDED_AT DESC FETCH FIRST 1 ROWS ONLY
            """,input.cifId());
        if(kyc.isEmpty())throw conflict("KYC_EVIDENCE_REQUIRED","Approved KYC case is required");
        List<Map<String,Object>> rules=db.queryForList("""
            SELECT P.PRODUCT_ID,V.PRODUCT_VERSION_ID,V.RULE_SET_HASH,L.MIN_LOAN_AMOUNT,L.MAX_LOAN_AMOUNT,
                   L.MIN_TENURE_MONTHS,L.MAX_TENURE_MONTHS
            FROM M03_PM_PRODUCT P JOIN M03_PM_PRODUCT_VERSION V ON V.PRODUCT_ID=P.PRODUCT_ID
            JOIN M03_PM_LOAN_RULE L ON L.PRODUCT_VERSION_ID=V.PRODUCT_VERSION_ID
            WHERE P.PRODUCT_ID=? AND V.PRODUCT_VERSION_ID=? AND P.PRODUCT_TYPE='LOAN'
              AND P.STATUS='ACTIVE' AND V.VERSION_STATE='ACTIVE'
              AND V.EFFECTIVE_FROM_AT<=SYSTIMESTAMP
              AND (V.EFFECTIVE_TO_AT IS NULL OR V.EFFECTIVE_TO_AT>SYSTIMESTAMP)
            """,input.productId(),input.productVersionId());
        if(rules.isEmpty())throw conflict("PRODUCT_NOT_ACTIVE","Loan product version is unavailable");
        Long offered=db.queryForObject("""
            SELECT COUNT(*) FROM M03_PM_AVAILABILITY A JOIN M02_CIF_CUSTOMER C ON C.CIF_ID=?
            WHERE A.PRODUCT_VERSION_ID=? AND (A.BRANCH_CODE IS NULL OR A.BRANCH_CODE=?)
              AND (A.SEGMENT_CODE IS NULL OR A.SEGMENT_CODE=C.SEGMENT_CODE)
              AND (A.CHANNEL_CODE IS NULL OR A.CHANNEL_CODE=?) AND A.CURRENCY_CODE='INR'
              AND A.OFFER_FROM_AT<=SYSTIMESTAMP AND (A.OFFER_TO_AT IS NULL OR A.OFFER_TO_AT>SYSTIMESTAMP)
            """,Long.class,input.cifId(),input.productVersionId(),input.branchCode(),input.channelCode());
        if(offered==null||offered==0)throw conflict("PRODUCT_NOT_OFFERED","Product is not offered for this customer, branch and channel");
        Map<String,Object> rule=rules.get(0);
        if(rule.get("RULE_SET_HASH")==null)throw conflict("PRODUCT_RULE_HASH_MISSING","Product version lacks an approved rule hash");
        if(input.amount().compareTo((BigDecimal)rule.get("MIN_LOAN_AMOUNT"))<0||input.amount().compareTo((BigDecimal)rule.get("MAX_LOAN_AMOUNT"))>0
            ||input.tenureMonths()<((Number)rule.get("MIN_TENURE_MONTHS")).intValue()||input.tenureMonths()>((Number)rule.get("MAX_TENURE_MONTHS")).intValue())
            throw bad("Amount or tenure is outside the product rule");
        db.update("""
            INSERT INTO M08_LOAN_IDEMPOTENCY_RECORD(ACTOR_ID,OPERATION_CODE,IDEMPOTENCY_KEY,REQUEST_HASH,EXPIRES_AT)
            VALUES(?,'CREATE_APPLICATION',?,?,SYSTIMESTAMP+INTERVAL '1' DAY)
            """,actor.userId(),key,requestHash);
        Long seq=db.queryForObject("SELECT M08_LOAN_APPLICATION_NO_SEQ.NEXTVAL FROM DUAL",Long.class);
        String number="LNAPP-"+seq;
        long id=insert("APPLICATION_ID","""
            INSERT INTO M08_LOAN_APPLICATION(APPLICATION_NUMBER,PRIMARY_CIF_ID,PRODUCT_ID,PRODUCT_VERSION_ID,
              PRODUCT_RULE_SET_HASH,BRANCH_CODE,CHANNEL_CODE,CURRENCY_CODE,REQUESTED_AMOUNT,
              REQUESTED_TENURE_MONTHS,LOAN_PURPOSE_CODE,CREATED_BY_USER_ID,CORRELATION_ID)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,number,input.cifId(),input.productId(),input.productVersionId(),rule.get("RULE_SET_HASH"),
            input.branchCode(),input.channelCode(),"INR",input.amount(),input.tenureMonths(),input.purposeCode(),
            actor.userId(),Correlation.current());
        db.update("""
            INSERT INTO M08_LOAN_APPLICATION_PARTY(APPLICATION_ID,CIF_ID,PARTY_ROLE,CONSENT_STATUS,KYC_DECISION_REF,KYC_EVALUATED_AT)
            VALUES(?,?,'PRIMARY_BORROWER','GIVEN',?,SYSTIMESTAMP)
            """,id,input.cifId(),"KYC_CASE:"+kyc.get(0).get("CASE_ID"));
        history("APPLICATION",id,null,"DRAFT","CREATED",actor);
        event("LoanApplicationCreated",id,Map.of("applicationId",id,"applicationNumber",number,"cifId",input.cifId()));
        db.update("""
            UPDATE M08_LOAN_IDEMPOTENCY_RECORD SET STATUS='SUCCEEDED',RESPONSE_CODE='CREATED',
              RESOURCE_TYPE='APPLICATION',RESOURCE_ID=?,RESPONSE_JSON=?,COMPLETED_AT=SYSTIMESTAMP
            WHERE ACTOR_ID=? AND OPERATION_CODE='CREATE_APPLICATION' AND IDEMPOTENCY_KEY=?
            """,String.valueOf(id),toJson(Map.of("applicationId",id)),actor.userId(),key);
        return load(id,false);
    }

    /** Freezes a JSON revision before review; later assessment and decisions bind to this revision. */
    @Transactional
    public Application submit(UserPrincipal actor,long id) {
        Application a=load(id,true);
        require(actor,"LOAN_CREATE",a.branchCode(),a.cifId(),null,null,null,null);
        if(!"DRAFT".equals(a.status()))throw conflict("INVALID_STATE","Only a draft can be submitted");
        String snapshot=toJson(Map.of("applicationId",id,"cifId",a.cifId(),"productVersionId",a.productVersionId(),
            "amount",a.amount(),"tenureMonths",a.tenureMonths(),"purposeCode",a.purposeCode()));
        long revision=insert("APPLICATION_REVISION_ID","""
            INSERT INTO M08_LOAN_APPLICATION_REVISION(APPLICATION_ID,REVISION_NO,REVISION_REASON,SNAPSHOT_JSON,CONTENT_HASH,CREATED_BY_USER_ID)
            VALUES(?,1,'INITIAL_SUBMISSION',?,?,?)
            """,id,snapshot,hash(snapshot),actor.userId());
        db.update("UPDATE M08_LOAN_APPLICATION SET STATUS='SUBMITTED',CURRENT_REVISION_ID=?,SUBMITTED_AT=SYSTIMESTAMP,UPDATED_AT=SYSTIMESTAMP WHERE APPLICATION_ID=?",revision,id);
        history("APPLICATION",id,"DRAFT","SUBMITTED","SUBMITTED",actor);
        event("LoanApplicationSubmitted",id,Map.of("applicationId",id,"revisionId",revision));
        return load(id,false);
    }

    /** Records a manual affordability assessment; a score is evidence, never an automatic decision. */
    @Transactional
    public Map<String,Object> assess(UserPrincipal actor,long id,Assessment input) {
        Application a=load(id,true);
        require(actor,"LOAN_ASSESS",a.branchCode(),a.cifId(),null,null,null,null);
        if(!Set.of("SUBMITTED","UNDER_REVIEW","REFERRED").contains(a.status()))throw conflict("INVALID_STATE","Application is not reviewable");
        BigDecimal dti=input.monthlyIncome().signum()==0?null:input.monthlyObligations().divide(input.monthlyIncome(),6,RoundingMode.HALF_UP);
        String evidence=toJson(input);
        long assessment=insert("ASSESSMENT_ID","""
            INSERT INTO M08_LOAN_ASSESSMENT(APPLICATION_ID,APPLICATION_REVISION_ID,ASSESSMENT_TYPE,MONTHLY_INCOME,
              MONTHLY_OBLIGATIONS,DEBT_TO_INCOME_PCT,CREDIT_SCORE,RECOMMENDATION,REASON_CODES,
              CREDIT_EVIDENCE_REF,ASSESSED_BY_USER_ID,CONTENT_HASH)
            VALUES(?,?,'MANUAL_REVIEW',?,?,?,?,?,?,?,?,?)
            """,id,a.revisionId(),input.monthlyIncome(),input.monthlyObligations(),dti,input.creditScore(),
            input.recommendation(),input.reasonCodes(),input.creditEvidenceRef(),actor.userId(),hash(evidence));
        if(!"UNDER_REVIEW".equals(a.status())){
            db.update("UPDATE M08_LOAN_APPLICATION SET STATUS='UNDER_REVIEW',UPDATED_AT=SYSTIMESTAMP WHERE APPLICATION_ID=?",id);
            history("APPLICATION",id,a.status(),"UNDER_REVIEW","ASSESSMENT_RECORDED",actor);
        }
        event("LoanAssessmentRecorded",id,Map.of("applicationId",id,"assessmentId",assessment,"recommendation",input.recommendation()));
        audit("ASSESSMENT_RECORDED","ASSESSMENT",assessment,actor);
        return Map.of("assessmentId",assessment,"applicationId",id,"recommendation",input.recommendation());
    }

    /** Applies a checker decision with IAM amount authority and database-enforced maker separation. */
    @Transactional
    public Map<String,Object> decide(UserPrincipal actor,long id,Decision input) {
        Application a=load(id,true);
        if(!Set.of("SUBMITTED","UNDER_REVIEW","REFERRED").contains(a.status()))throw conflict("INVALID_STATE","Application is not awaiting a decision");
        String maker=db.queryForObject("SELECT CREATED_BY_USER_ID FROM M08_LOAN_APPLICATION_REVISION WHERE APPLICATION_REVISION_ID=?",String.class,a.revisionId());
        if(maker.equals(actor.userId()))throw conflict("MAKER_CHECKER","Checker must differ from the revision maker");
        BigDecimal amount="APPROVE".equals(input.code())?input.sanctionedAmount():a.amount();
        require(actor,"LOAN_DECIDE",a.branchCode(),a.cifId(),amount,input.annualRatePct(),input.authorityCode(),maker);
        if("APPROVE".equals(input.code())){
            if(input.sanctionedAmount()==null||input.sanctionedTenureMonths()==null||input.annualRatePct()==null)
                throw bad("Approved decisions require amount, tenure and annual rate");
            if(input.sanctionedAmount().compareTo(a.amount())>0||input.sanctionedTenureMonths()>a.tenureMonths())
                throw bad("Sanction cannot exceed the submitted application");
        } else if(input.sanctionedAmount()!=null||input.sanctionedTenureMonths()!=null||input.annualRatePct()!=null)
            throw bad("Reject and refer decisions must omit sanction terms");
        List<Map<String,Object>> assessed=db.queryForList("""
            SELECT ASSESSMENT_ID FROM M08_LOAN_ASSESSMENT WHERE APPLICATION_ID=? AND APPLICATION_REVISION_ID=?
            ORDER BY ASSESSED_AT DESC FETCH FIRST 1 ROWS ONLY
            """,id,a.revisionId());
        if(assessed.isEmpty())throw conflict("ASSESSMENT_REQUIRED","Record an assessment before a decision");
        long assessment=((Number)assessed.get(0).get("ASSESSMENT_ID")).longValue();
        BigDecimal authorityMax=db.queryForObject("""
            SELECT MAX(A.MAX_AMOUNT) FROM M01_IAM_USER_ROLE UR JOIN M01_IAM_ROLE_AUTHORITY A ON A.ROLE_ID=UR.ROLE_ID
            WHERE UR.USER_ID=? AND UR.STATUS='ACTIVE' AND A.AUTHORITY_CODE=? AND A.VALID_FROM<=SYSTIMESTAMP
              AND (A.VALID_TO IS NULL OR A.VALID_TO>SYSTIMESTAMP) AND (A.CURRENCY_CODE='*' OR A.CURRENCY_CODE='INR')
            """,BigDecimal.class,actor.userId(),input.authorityCode());
        if(authorityMax==null)throw conflict("AUTHORITY_REQUIRED","No active approval amount authority");
        String scope=hash(id+":"+a.revisionId()+":"+assessment+":"+input.code()+":"+amount);
        String makerSession=db.queryForObject("""
            SELECT SESSION_ID FROM M08_LOAN_STATUS_HISTORY WHERE RESOURCE_TYPE='APPLICATION' AND RESOURCE_ID=?
              AND TO_STATUS='SUBMITTED' ORDER BY CHANGED_AT DESC FETCH FIRST 1 ROWS ONLY
            """,String.class,id);
        long decision=insert("DECISION_ID","""
            INSERT INTO M08_LOAN_DECISION(APPLICATION_ID,APPLICATION_REVISION_ID,ASSESSMENT_ID,DECISION_CODE,
              SANCTIONED_AMOUNT,SANCTIONED_TENURE_MONTHS,INTEREST_TYPE,ANNUAL_RATE_PCT,FIXED_RATE_PCT,
              REASON_CODES,MAKER_USER_ID,MAKER_SESSION_ID,CHECKER_USER_ID,CHECKER_SESSION_ID,
              APPROVAL_AUTHORITY_CODE,AUTHORITY_MAX_AMOUNT,AUTHORITY_EVIDENCE_REF,DECISION_SCOPE_HASH)
            VALUES(?,?,?, ?,?,?, ?,?,?, ?,?,?, ?,?,?, ?,?,?)
            """,id,a.revisionId(),assessment,input.code(),"APPROVE".equals(input.code())?input.sanctionedAmount():null,
            "APPROVE".equals(input.code())?input.sanctionedTenureMonths():null,
            "APPROVE".equals(input.code())?"FIXED":null,input.annualRatePct(),input.annualRatePct(),
            input.reasonCodes(),maker,makerSession,actor.userId(),actor.sessionId(),input.authorityCode(),
            authorityMax,input.authorityEvidenceRef(),scope);
        String to=switch(input.code()){case "APPROVE"->"APPROVED";case "REJECT"->"REJECTED";default->"REFERRED";};
        db.update("UPDATE M08_LOAN_APPLICATION SET STATUS=?,TERMINATED_AT=CASE WHEN ?='REJECTED' THEN SYSTIMESTAMP ELSE NULL END,UPDATED_AT=SYSTIMESTAMP WHERE APPLICATION_ID=?",to,to,id);
        history("APPLICATION",id,a.status(),to,"CREDIT_DECISION",actor);
        event("LoanDecisionRecorded",id,Map.of("applicationId",id,"decisionId",decision,"decision",input.code()));
        return Map.of("decisionId",decision,"applicationId",id,"status",to);
    }

    /** Issues a time-limited offer for the latest approved decision. */
    @Transactional
    public Map<String,Object> offer(UserPrincipal actor,long id,Offer input) {
        Application a=load(id,true);
        require(actor,"LOAN_OFFER",a.branchCode(),a.cifId(),null,null,null,null);
        if(!"APPROVED".equals(a.status()))throw conflict("INVALID_STATE","Application is not approved");
        if(!input.expiresAt().isAfter(OffsetDateTime.now(clock)))throw bad("Offer expiry must be in the future");
        List<Map<String,Object>> rows=db.queryForList("SELECT DECISION_ID,DECISION_SCOPE_HASH FROM M08_LOAN_DECISION WHERE APPLICATION_ID=? AND DECISION_CODE='APPROVE' ORDER BY DECIDED_AT DESC FETCH FIRST 1 ROWS ONLY",id);
        if(rows.isEmpty())throw conflict("DECISION_REQUIRED","Approved decision is missing");
        Long seq=db.queryForObject("SELECT M08_LOAN_OFFER_NO_SEQ.NEXTVAL FROM DUAL",Long.class);
        long offer=insert("OFFER_ID","INSERT INTO M08_LOAN_OFFER(OFFER_NUMBER,APPLICATION_ID,DECISION_ID,TERMS_HASH,OFFER_DOCUMENT_REF,EXPIRES_AT) VALUES(?,?,?,?,?,?)",
            "LNOFF-"+seq,id,rows.get(0).get("DECISION_ID"),rows.get(0).get("DECISION_SCOPE_HASH"),input.documentRef(),input.expiresAt());
        db.update("UPDATE M08_LOAN_APPLICATION SET STATUS='OFFERED',UPDATED_AT=SYSTIMESTAMP WHERE APPLICATION_ID=?",id);
        history("APPLICATION",id,"APPROVED","OFFERED","OFFER_ISSUED",actor);
        history("OFFER",offer,null,"ISSUED","OFFER_ISSUED",actor);
        event("LoanOfferIssued",id,Map.of("applicationId",id,"offerId",offer));
        return Map.of("offerId",offer,"applicationId",id,"status","ISSUED");
    }

    /** Captures customer acceptance against active Module 4 accounts; no disbursement occurs here. */
    @Transactional
    public Map<String,Object> accept(UserPrincipal actor,long offerId,AcceptOffer input) {
        Map<String,Object> row=one("SELECT APPLICATION_ID,STATUS,EXPIRES_AT FROM M08_LOAN_OFFER WHERE OFFER_ID=? FOR UPDATE",offerId);
        long appId=((Number)row.get("APPLICATION_ID")).longValue();
        Application a=load(appId,true);
        require(actor,"LOAN_ACCEPT",a.branchCode(),a.cifId(),null,null,null,null);
        if(!"OFFERED".equals(a.status())||!"ISSUED".equals(row.get("STATUS")))throw conflict("INVALID_STATE","Offer is not open");
        Long unexpired=db.queryForObject("SELECT COUNT(*) FROM M08_LOAN_OFFER WHERE OFFER_ID=? AND EXPIRES_AT>SYSTIMESTAMP",Long.class,offerId);
        if(unexpired==null||unexpired==0)throw conflict("OFFER_EXPIRED","Offer has expired");
        for(long accountId:List.of(input.disbursementAccountId(),input.repaymentAccountId())){
            List<Map<String,Object>> accounts=db.queryForList("SELECT PRIMARY_CIF_ID,LIFECYCLE_STATUS,CURRENCY_CODE FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID=?",accountId);
            if(accounts.isEmpty()||!a.cifId().equals(accounts.get(0).get("PRIMARY_CIF_ID"))||!"ACTIVE".equals(accounts.get(0).get("LIFECYCLE_STATUS"))||!"INR".equals(accounts.get(0).get("CURRENCY_CODE")))
                throw conflict("ACCOUNT_NOT_ELIGIBLE","Both accounts must be active INR accounts owned by the borrower");
        }
        db.update("""
            UPDATE M08_LOAN_OFFER SET STATUS='ACCEPTED',DISBURSEMENT_ACCOUNT_ID=?,REPAYMENT_ACCOUNT_ID=?,
              ACCEPTED_BY_USER_ID=?,ACCEPTED_AT=SYSTIMESTAMP WHERE OFFER_ID=?
            """,input.disbursementAccountId(),input.repaymentAccountId(),actor.userId(),offerId);
        db.update("UPDATE M08_LOAN_APPLICATION SET STATUS='ACCEPTED',UPDATED_AT=SYSTIMESTAMP WHERE APPLICATION_ID=?",appId);
        history("OFFER",offerId,"ISSUED","ACCEPTED","CUSTOMER_ACCEPTED",actor);
        history("APPLICATION",appId,"OFFERED","ACCEPTED","OFFER_ACCEPTED",actor);
        event("LoanOfferAccepted",appId,Map.of("applicationId",appId,"offerId",offerId));
        return Map.of("offerId",offerId,"applicationId",appId,"status","ACCEPTED");
    }

    /** Stores metadata and a content digest, never document bytes or a public download URL. */
    @Transactional
    public Map<String,Object> addDocument(UserPrincipal actor,long applicationId,Document input) {
        Application a=load(applicationId,true);
        require(actor,"LOAN_CREATE",a.branchCode(),a.cifId(),null,null,null,null);
        if(Set.of("REJECTED","WITHDRAWN","CONVERTED","CANCELLED").contains(a.status()))
            throw conflict("INVALID_STATE","Application no longer accepts documents");
        long id=insert("DOCUMENT_REF_ID","""
            INSERT INTO M08_LOAN_DOCUMENT_REF(APPLICATION_ID,DOCUMENT_TYPE,STORAGE_REFERENCE,CONTENT_HASH)
            VALUES(?,?,?,?)
            """,applicationId,input.type(),input.storageReference(),input.sha256Hex().toUpperCase(Locale.ROOT));
        event("LoanDocumentRecorded",applicationId,Map.of("applicationId",applicationId,"documentRefId",id,"type",input.type()));
        audit("DOCUMENT_RECORDED","DOCUMENT",id,actor);
        return Map.of("documentRefId",id,"applicationId",applicationId,"status","RECEIVED");
    }

    /** A separate reviewer marks evidence verified before contractual conversion. */
    @Transactional
    public Map<String,Object> verifyDocument(UserPrincipal actor,long applicationId,long documentId) {
        Application a=load(applicationId,true);
        String maker=db.queryForObject("SELECT CREATED_BY_USER_ID FROM M08_LOAN_APPLICATION WHERE APPLICATION_ID=?",String.class,applicationId);
        require(actor,actor.permissions().contains("LOAN_DOCUMENT_VERIFY")?"LOAN_DOCUMENT_VERIFY":"LOAN_ASSESS",
            a.branchCode(),a.cifId(),null,null,null,maker);
        Map<String,Object> doc=one("SELECT STATUS,DOCUMENT_TYPE FROM M08_LOAN_DOCUMENT_REF WHERE DOCUMENT_REF_ID=? AND APPLICATION_ID=? FOR UPDATE",documentId,applicationId);
        if(!"RECEIVED".equals(doc.get("STATUS")))throw conflict("INVALID_STATE","Only received documents can be verified");
        db.update("UPDATE M08_LOAN_DOCUMENT_REF SET STATUS='VERIFIED',VERIFIED_BY_USER_ID=?,VERIFIED_AT=SYSTIMESTAMP WHERE DOCUMENT_REF_ID=?",actor.userId(),documentId);
        event("LoanDocumentVerified",applicationId,Map.of("applicationId",applicationId,"documentRefId",documentId));
        audit("DOCUMENT_VERIFIED","DOCUMENT",documentId,actor);
        return Map.of("documentRefId",documentId,"applicationId",applicationId,"status","VERIFIED");
    }

    /** Creates the contractual facility; posting and the active schedule wait for Module 5 evidence. */
    @Transactional
    public Map<String,Object> convert(UserPrincipal actor,long applicationId) {
        Application a=load(applicationId,true);
        require(actor,"LOAN_CONVERT",a.branchCode(),a.cifId(),null,null,null,null);
        if(!"ACCEPTED".equals(a.status()))throw conflict("INVALID_STATE","Only an accepted offer can become a facility");
        Long documents=db.queryForObject("""
            SELECT COUNT(*) FROM M08_LOAN_DOCUMENT_REF WHERE APPLICATION_ID=?
              AND DOCUMENT_TYPE='SIGNED_OFFER' AND STATUS='VERIFIED'
            """,Long.class,applicationId);
        if(documents==null||documents==0)throw conflict("DOCUMENTS_INCOMPLETE","Verified SIGNED_OFFER document is required");
        Map<String,Object> offer=one("""
            SELECT OFFER_ID,DECISION_ID,DISBURSEMENT_ACCOUNT_ID,REPAYMENT_ACCOUNT_ID FROM M08_LOAN_OFFER
            WHERE APPLICATION_ID=? AND STATUS='ACCEPTED' FOR UPDATE
            """,applicationId);
        Map<String,Object> decision=one("""
            SELECT SANCTIONED_AMOUNT,SANCTIONED_TENURE_MONTHS,ANNUAL_RATE_PCT,FIXED_RATE_PCT,INTEREST_TYPE
            FROM M08_LOAN_DECISION WHERE DECISION_ID=? AND DECISION_CODE='APPROVE'
            """,offer.get("DECISION_ID"));
        Map<String,Object> rule=one("""
            SELECT L.DISBURSEMENT_MODE,L.REPAYMENT_FREQUENCY,L.AMORTIZATION_METHOD,I.DAY_COUNT_BASIS
            FROM M03_PM_LOAN_RULE L JOIN M03_PM_INTEREST_RULE I ON I.PRODUCT_VERSION_ID=L.PRODUCT_VERSION_ID
            WHERE L.PRODUCT_VERSION_ID=? AND I.INTEREST_TYPE='FIXED'
            ORDER BY I.INTEREST_RULE_ID FETCH FIRST 1 ROWS ONLY
            """,a.productVersionId());
        if("FLOATING".equals(decision.get("INTEREST_TYPE")))
            throw conflict("RATE_INTEGRATION_REQUIRED","Floating-rate index integration is not configured");
        Long seq=db.queryForObject("SELECT M08_LOAN_FACILITY_NO_SEQ.NEXTVAL FROM DUAL",Long.class);
        long facility=insert("FACILITY_ID","""
            INSERT INTO M08_LOAN_FACILITY(FACILITY_NUMBER,APPLICATION_ID,DECISION_ID,OFFER_ID,PRIMARY_CIF_ID,
              PRODUCT_ID,PRODUCT_VERSION_ID,PRODUCT_RULE_SET_HASH,BRANCH_CODE,CURRENCY_CODE,PRINCIPAL_AMOUNT,
              TENURE_MONTHS,DISBURSEMENT_MODE,REPAYMENT_FREQUENCY,AMORTIZATION_METHOD,DAY_COUNT_BASIS,
              INTEREST_TYPE,ANNUAL_RATE_PCT,FIXED_RATE_PCT,DISBURSEMENT_ACCOUNT_ID,REPAYMENT_ACCOUNT_ID)
            SELECT ?,A.APPLICATION_ID,?,?,A.PRIMARY_CIF_ID,A.PRODUCT_ID,A.PRODUCT_VERSION_ID,A.PRODUCT_RULE_SET_HASH,
              A.BRANCH_CODE,A.CURRENCY_CODE,?,?,?,?,?,?,?,?,?,?,?
            FROM M08_LOAN_APPLICATION A WHERE A.APPLICATION_ID=?
            ""","LNFAC-"+seq,offer.get("DECISION_ID"),offer.get("OFFER_ID"),decision.get("SANCTIONED_AMOUNT"),
            decision.get("SANCTIONED_TENURE_MONTHS"),"BOTH".equals(rule.get("DISBURSEMENT_MODE"))?"SINGLE":rule.get("DISBURSEMENT_MODE"),
            rule.get("REPAYMENT_FREQUENCY"),rule.get("AMORTIZATION_METHOD"),rule.get("DAY_COUNT_BASIS"),
            decision.get("INTEREST_TYPE"),decision.get("ANNUAL_RATE_PCT"),decision.get("FIXED_RATE_PCT"),
            offer.get("DISBURSEMENT_ACCOUNT_ID"),offer.get("REPAYMENT_ACCOUNT_ID"),applicationId);
        db.update("UPDATE M08_LOAN_APPLICATION SET STATUS='CONVERTED',TERMINATED_AT=SYSTIMESTAMP,UPDATED_AT=SYSTIMESTAMP WHERE APPLICATION_ID=?",applicationId);
        history("FACILITY",facility,null,"PENDING_DISBURSEMENT","FACILITY_CREATED",actor);
        history("APPLICATION",applicationId,"ACCEPTED","CONVERTED","FACILITY_CREATED",actor);
        event("LoanFacilityCreated",applicationId,Map.of("applicationId",applicationId,"facilityId",facility));
        return Map.of("facilityId",facility,"applicationId",applicationId,"status","PENDING_DISBURSEMENT");
    }

    /** Returns a facility projection; all monetary amounts are projections from posted ledger events. */
    public Map<String,Object> facility(UserPrincipal actor,long id) {
        Map<String,Object> row=one("""
            SELECT FACILITY_ID,FACILITY_NUMBER,APPLICATION_ID,PRIMARY_CIF_ID,BRANCH_CODE,STATUS,PRINCIPAL_AMOUNT,
              PRINCIPAL_OUTSTANDING,INTEREST_OUTSTANDING,FEE_OUTSTANDING,PENALTY_OUTSTANDING,OVERDUE_AMOUNT,
              FINANCIAL_AS_OF,START_DATE,MATURITY_DATE FROM M08_LOAN_FACILITY WHERE FACILITY_ID=?
            """,id);
        require(actor,"LOAN_READ",(String)row.get("BRANCH_CODE"),(String)row.get("PRIMARY_CIF_ID"),null,null,null,null);
        return row;
    }

    /** Gives clients the current schedule without allowing them to mutate accounting state. */
    public List<Map<String,Object>> schedule(UserPrincipal actor,long facilityId) {
        facility(actor,facilityId);
        return db.queryForList("""
            SELECT I.INSTALLMENT_NO,I.DUE_DATE,I.PRINCIPAL_DUE,I.INTEREST_DUE,I.FEE_DUE,I.STATUS
            FROM M08_LOAN_SCHEDULE S JOIN M08_LOAN_SCHEDULE_ITEM I ON I.SCHEDULE_ID=S.SCHEDULE_ID
            WHERE S.FACILITY_ID=? AND S.STATUS='ACTIVE' ORDER BY I.INSTALLMENT_NO
            """,facilityId);
    }

    /** Loads an application and optionally locks it for a state transition. */
    private Application load(long id,boolean lock) {
        List<Application> rows=db.query("SELECT * FROM M08_LOAN_APPLICATION WHERE APPLICATION_ID=?"+(lock?" FOR UPDATE":""),
            (rs,n)->application(rs),id);
        if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","Loan application not found");
        return rows.get(0);
    }

    /** Maps only the fields permitted in the external application summary. */
    private static Application application(java.sql.ResultSet rs) throws java.sql.SQLException {
        Number revision=(Number)rs.getObject("CURRENT_REVISION_ID");
        return new Application(rs.getLong("APPLICATION_ID"),rs.getString("APPLICATION_NUMBER"),rs.getString("PRIMARY_CIF_ID"),
            rs.getLong("PRODUCT_ID"),rs.getLong("PRODUCT_VERSION_ID"),rs.getBigDecimal("REQUESTED_AMOUNT"),
            rs.getInt("REQUESTED_TENURE_MONTHS"),rs.getString("LOAN_PURPOSE_CODE"),rs.getString("BRANCH_CODE"),
            rs.getString("STATUS"),revision==null?null:revision.longValue(),rs.getString("ASSIGNED_TO_USER_ID"),
            rs.getObject("CREATED_AT",OffsetDateTime.class));
    }

    /** Retrieves one bounded, parameterized lookup or raises a typed not-found error. */
    private Map<String,Object> one(String sql,Object... args) {
        List<Map<String,Object>> rows=db.queryForList(sql,args);
        if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","Loan resource not found");
        return rows.get(0);
    }

    /** Inserts one Oracle identity row and returns its generated key. */
    private long insert(String keyColumn,String sql,Object... args) {
        GeneratedKeyHolder keys=new GeneratedKeyHolder();
        db.update(c->{var ps=c.prepareStatement(sql,new String[]{keyColumn});
            for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;},keys);
        Number key=keys.getKey();
        if(key==null)throw new IllegalStateException("Oracle did not return an identity value");
        return key.longValue();
    }

    /** Applies IAM permission, scope, authority, and maker-checker rules. */
    private void require(UserPrincipal actor,String permission,String branch,String cif,BigDecimal amount,BigDecimal rate,String authority,String maker) {
        access.require(actor,new AuthorizationInput(permission,branch,null,cif,"LOAN","INR",authority,amount,rate,maker));
    }

    /** Records a status change with actor, session and correlation evidence. */
    private void history(String type,long id,String from,String to,String reason,UserPrincipal actor) {
        db.update("""
            INSERT INTO M08_LOAN_STATUS_HISTORY(RESOURCE_TYPE,RESOURCE_ID,FROM_STATUS,TO_STATUS,REASON_CODE,
              ACTOR_USER_ID,SESSION_ID,CORRELATION_ID) VALUES(?,?,?,?,?,?,?,?)
            """,type,id,from,to,reason,actor.userId(),actor.sessionId(),Correlation.current());
        audit(reason,type,id,actor);
    }

    /** Adds a minimal security audit row without storing document bytes or customer identity fields. */
    private void audit(String action,String type,long id,UserPrincipal actor) {
        db.update("""
            INSERT INTO M08_SECURITY_AUDIT_EVENT(ACTOR_USER_ID,SESSION_ID,CORRELATION_ID,ACTION_CODE,
              RESOURCE_TYPE,RESOURCE_ID,RESULT_CODE) VALUES(?,?,?,?,?,?,'SUCCESS')
            """,actor.userId(),actor.sessionId(),Correlation.current(),action,type,String.valueOf(id));
    }

    /** Adds an unpublished outbox event in the caller's Oracle transaction. */
    private void event(String type,long id,Object payload) {
        UUID uuid=UUID.randomUUID();
        java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits());
        db.update("""
            INSERT INTO M08_OUTBOX_EVENT(EVENT_ID,EVENT_TYPE,SCHEMA_VERSION,AGGREGATE_TYPE,AGGREGATE_ID,
              CORRELATION_ID,PARTITION_KEY,PAYLOAD_JSON) VALUES(?,?,1,'APPLICATION',?,?,?,?)
            """,b.array(),type,String.valueOf(id),Correlation.current(),String.valueOf(id),toJson(payload));
    }

    /** Serializes immutable revision or event payloads for Oracle JSON columns. */
    private String toJson(Object value) {
        return json.writeValueAsString(value);
    }

    /** Computes raw SHA-256 bytes for the idempotency record. */
    private static byte[] sha256(String value) {
        try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    /** Encodes SHA-256 as the schema's 64-character uppercase digest. */
    private static String hash(String value) {
        try{byte[] bytes=sha256(value);
            return HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
        }catch(Exception e){throw new IllegalStateException(e);}
    }

    /** Rejects missing or oversized command deduplication keys. */
    private static void requireKey(String key) {
        if(key==null||key.isBlank()||key.length()>120)throw bad("Idempotency-Key header is required (1-120 characters)");
    }
    /** Constructs a safe client-input error. */
    private static BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_LOAN_REQUEST",message);}
    /** Constructs a typed state or dependency conflict. */
    private static BusinessException conflict(String code,String message){return new BusinessException(HttpStatus.CONFLICT,code,message);}
}
