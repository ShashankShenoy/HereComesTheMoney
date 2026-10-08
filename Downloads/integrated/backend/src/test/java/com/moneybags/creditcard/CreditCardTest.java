package com.moneybags.creditcard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:creditcards;MODE=Oracle;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH","moneybags.auth.allowed-origins=http://localhost:5173"})
@AutoConfigureMockMvc @ActiveProfiles("local")
class CreditCardTest {
    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock creditTestClock(){return new MutableClock();}
    }
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> time=new AtomicReference<>(Instant.now());
        void set(Instant instant){time.set(instant);}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}
        public Instant instant(){return time.get();}
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    String admin,checker,customer,other;
    final ZoneId zone=ZoneId.of("Asia/Kolkata");

    @BeforeEach void sessions() throws Exception {
        clock.set(Instant.now());admin=login("admin");checker=login("checker");customer=login("customer");other=login("customer2");
    }
    String key(){return UUID.randomUUID().toString().toUpperCase(Locale.ROOT);}
    String login(String name)throws Exception{
        var r=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",name,"password","LocalBanking!2026","clientId","MONEYBAGS_WEB")))).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
    ResultActions send(String path,String token,Object body)throws Exception{return mvc.perform(post("/api/v1/credit-cards"+path).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));}
    JsonNode value(ResultActions r)throws Exception{return json.readTree(r.andReturn().getResponse().getContentAsString());}
    JsonNode getCard(String id,String token)throws Exception{return value(mvc.perform(get("/api/v1/credit-cards/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk()));}
    Map<String,Object> product(String apr){return new LinkedHashMap<>(Map.ofEntries(Map.entry("requestKey",key()),Map.entry("productCode","TEST_"+key().substring(0,8)),Map.entry("productName","Test Card"),Map.entry("versionNumber",1),Map.entry("minimumLimit","1000"),Map.entry("maximumLimit","200000"),Map.entry("annualRatePct",apr),Map.entry("minimumPaymentPct","5"),Map.entry("minimumPaymentFloor","200"),Map.entry("billingDay",1),Map.entry("paymentDueDays",20),Map.entry("description","Disclosed synthetic test terms")));}
    Map<String,Object> decision(long version,String decision,String amount){var r=new LinkedHashMap<String,Object>();r.put("requestKey",key());r.put("rowVersion",version);r.put("decision",decision);r.put("reason","Independent test review");if(amount!=null)r.put("approvedLimit",amount);return r;}
    String approvedProduct(String apr)throws Exception{
        String id=value(send("/products",admin,product(apr)).andExpect(status().isOk())).path("PRODUCT_ID").asText();
        send("/products/"+id+"/decision",checker,decision(1,"APPROVED",null)).andExpect(status().isOk());return id;
    }
    Map<String,Object> application(String product,String limit){return Map.of("requestKey",key(),"productId",product,"cifId","demo-cif-1","repaymentAccountId",1,"requestedLimit",limit,"termsAccepted",true);}
    String activeCard(String apr,String limit)throws Exception{
        String product=approvedProduct(apr);
        String app=value(send("/applications",customer,application(product,limit)).andExpect(status().isOk())).path("APPLICATION_ID").asText();
        String id=value(send("/applications/"+app+"/decision",checker,decision(1,"APPROVED",limit)).andExpect(status().isOk())).path("CARD_ID").asText();
        control(id,"ACTIVATE",customer).andExpect(status().isOk());return id;
    }
    ResultActions control(String id,String action,String token)throws Exception{
        return send("/"+id+"/controls",token,Map.of("requestKey",key(),"rowVersion",getCard(id,token).path("ROW_VERSION").asLong(),"action",action,"reason","Test "+action));
    }
    Map<String,Object> purchase(String amount,String requestKey){return Map.of("requestKey",requestKey,"amount",amount,"merchantName","Synthetic merchant");}
    BigDecimal balance(){return db.queryForObject("SELECT POSTED_BALANCE FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=1",BigDecimal.class);}
    void equalMoney(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    void reconciled(String id)throws Exception{
        mvc.perform(get("/api/v1/credit-cards/"+id+"/reconciliation").header("Authorization","Bearer "+customer)).andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(true));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M05_V_GL_JOURNAL_CONTROL WHERE IS_BALANCED='N'",Integer.class));
    }

    @Test void customerDirectoriesRespectCurrentCifAndExecutePagedQueries()throws Exception{
        for(String token:List.of(customer,other)){
            var applications=value(mvc.perform(get("/api/v1/credit-cards/applications").header("Authorization","Bearer "+token)).andExpect(status().isOk()));
            var cards=value(mvc.perform(get("/api/v1/credit-cards").header("Authorization","Bearer "+token)).andExpect(status().isOk()));
            String cif=token.equals(customer)?"demo-cif-1":"demo-cif-2";
            for(var application:applications)assertEquals(cif,application.path("CIF_ID").asText());
            for(var card:cards)assertEquals(cif,card.path("CIF_ID").asText());
        }
    }

    @Test void productTermsApprovalAndApplicationGuards()throws Exception{
        mvc.perform(get("/api/v1/credit-cards/products")).andExpect(status().isUnauthorized());
        var r=product("24");send("/products",customer,r).andExpect(status().isForbidden());
        var created=value(send("/products",admin,r).andExpect(status().isOk()));String p=created.path("PRODUCT_ID").asText();
        assertEquals(p,value(send("/products",admin,r).andExpect(status().isOk())).path("PRODUCT_ID").asText());
        r.put("annualRatePct","25");send("/products",admin,r).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        send("/products/"+p+"/decision",admin,decision(1,"APPROVED",null)).andExpect(status().isConflict());
        send("/applications",customer,application(p,"10000")).andExpect(status().isConflict());
        send("/products/"+p+"/decision",checker,decision(99,"APPROVED",null)).andExpect(status().isConflict());
        send("/products/"+p+"/decision",checker,decision(1,"APPROVED",null)).andExpect(status().isOk());
        var app=new LinkedHashMap<>(application(p,"10000"));app.put("termsAccepted",false);send("/applications",customer,app).andExpect(status().isBadRequest());
        app.put("termsAccepted",true);app.put("repaymentAccountId",2);send("/applications",customer,app).andExpect(status().isForbidden());
        app.put("repaymentAccountId",1);
        String a=value(send("/applications",admin,app).andExpect(status().isOk())).path("APPLICATION_ID").asText();
        send("/applications",customer,application(p,"10000")).andExpect(status().isConflict());
        send("/applications/"+a+"/decision",admin,decision(1,"APPROVED","10000")).andExpect(status().isConflict());
        send("/applications/"+a+"/decision",checker,decision(1,"APPROVED","15000")).andExpect(status().isConflict());
        send("/applications/"+a+"/decision",checker,decision(1,"APPROVED","9000")).andExpect(status().isOk());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM M11_CC_CARD WHERE APPLICATION_ID=?",Integer.class,a));
    }

    @Test void purchaseReplayIsolationControlsAndRepayment()throws Exception{
        String id=activeCard("0","10000");String requestKey=key();
        var request=purchase("125.50",requestKey);
        long entry=value(send("/"+id+"/purchases",customer,request).andExpect(status().isOk())).path("ENTRY_ID").asLong();
        assertEquals(entry,value(send("/"+id+"/purchases",customer,purchase("125.5",requestKey)).andExpect(status().isOk())).path("ENTRY_ID").asLong());
        send("/"+id+"/purchases",customer,purchase("126",requestKey)).andExpect(status().isConflict());
        send("/"+id+"/purchases",customer,purchase("10000",key())).andExpect(status().isConflict());
        for(String path:List.of("/"+id,"/"+id+"/transactions","/"+id+"/statements","/"+id+"/reconciliation"))mvc.perform(get("/api/v1/credit-cards"+path).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        send("/"+id+"/purchases",other,purchase("1",key())).andExpect(status().isForbidden());
        send("/"+id+"/purchases",checker,purchase("1",key())).andExpect(status().isForbidden());
        control(id,"FREEZE",customer).andExpect(status().isOk());
        send("/"+id+"/purchases",customer,purchase("1",key())).andExpect(status().isConflict());
        control(id,"CLOSE",customer).andExpect(status().isConflict());
        BigDecimal before=balance();var repay=Map.of("requestKey",key(),"amount","125.50");
        send("/"+id+"/repayments",customer,repay).andExpect(status().isOk());send("/"+id+"/repayments",customer,repay).andExpect(status().isOk());
        equalMoney("125.50",before.subtract(balance()));equalMoney("0",getCard(id,customer).path("OUTSTANDING_BALANCE").decimalValue());
        reconciled(id);control(id,"CLOSE",customer).andExpect(status().isOk());control(id,"ACTIVATE",customer).andExpect(status().isConflict());
    }

    @Test void concurrentRequestsCannotOverspendOrPostTwice()throws Exception{
        String id=activeCard("0","10000");ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            var one=pool.submit(()->send("/"+id+"/purchases",customer,purchase("7000",key())).andReturn().getResponse().getStatus());
            var two=pool.submit(()->send("/"+id+"/purchases",admin,purchase("7000",key())).andReturn().getResponse().getStatus());
            assertEquals(Set.of(200,409),Set.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS)));
            var request=purchase("100",key());
            var same1=pool.submit(()->value(send("/"+id+"/purchases",customer,request).andExpect(status().isOk())).path("ENTRY_ID").asLong());
            var same2=pool.submit(()->value(send("/"+id+"/purchases",customer,request).andExpect(status().isOk())).path("ENTRY_ID").asLong());
            assertEquals(same1.get(20,TimeUnit.SECONDS),same2.get(20,TimeUnit.SECONDS));
            equalMoney("7100",getCard(id,customer).path("OUTSTANDING_BALANCE").decimalValue());reconciled(id);
        }finally{pool.shutdownNow();}
    }

    @Test void failedRepaymentRollsBackAllWritesAndCanBeRetried()throws Exception{
        String id=activeCard("0","200000");
        send("/"+id+"/purchases",customer,purchase("150000",key())).andExpect(status().isOk());
        long journals=db.queryForObject("SELECT COUNT(*) FROM M05_GL_JOURNAL",Long.class);
        BigDecimal before=balance();String k=key();
        send("/"+id+"/repayments",customer,Map.of("requestKey",k,"amount","150000")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        assertEquals(journals,db.queryForObject("SELECT COUNT(*) FROM M05_GL_JOURNAL",Long.class));assertEquals(0,before.compareTo(balance()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M11_CC_REQUEST WHERE REQUEST_KEY=?",Integer.class,k));
        send("/"+id+"/repayments",customer,Map.of("requestKey",k,"amount","100")).andExpect(status().isOk());reconciled(id);
    }

    @Test void refundPaysExcessBackToDepositAndCannotRepeat()throws Exception{
        String id=activeCard("0","10000");
        long entry=value(send("/"+id+"/purchases",customer,purchase("500",key())).andExpect(status().isOk())).path("ENTRY_ID").asLong();
        send("/"+id+"/repayments",customer,Map.of("requestKey",key(),"amount","300")).andExpect(status().isOk());
        BigDecimal before=balance();var refund=Map.of("requestKey",key(),"reason","Returned goods");
        send("/"+id+"/purchases/"+entry+"/refund",customer,refund).andExpect(status().isForbidden());
        send("/"+id+"/purchases/"+entry+"/refund",admin,refund).andExpect(status().isOk()).andExpect(jsonPath("$.DEPOSIT_CREDIT").value(300));
        send("/"+id+"/purchases/"+entry+"/refund",admin,refund).andExpect(status().isOk());
        send("/"+id+"/purchases/"+entry+"/refund",admin,Map.of("requestKey",key(),"reason","Duplicate")).andExpect(status().isConflict());
        equalMoney("300",balance().subtract(before));equalMoney("0",getCard(id,customer).path("OUTSTANDING_BALANCE").decimalValue());reconciled(id);
    }

    @Test void billingAccruesInterestAndProtectsImmutableCutoffs()throws Exception{
        String id=activeCard("24","10000");LocalDate purchaseDate=LocalDate.now(clock.withZone(zone));
        send("/"+id+"/purchases",customer,purchase("1000",key())).andExpect(status().isOk());
        LocalDate billing=LocalDate.parse(getCard(id,customer).path("NEXT_STATEMENT_DATE").asText());
        send("/"+id+"/statements",customer,Map.of("requestKey",key(),"statementDate",billing.toString())).andExpect(status().isConflict());
        clock.set(billing.atTime(12,0).atZone(zone).toInstant());customer=login("customer");
        send("/"+id+"/purchases",customer,purchase("1",key())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BILLING_REQUIRED"));
        var bill=Map.of("requestKey",key(),"statementDate",billing.toString());
        var statement=value(send("/"+id+"/statements",customer,bill).andExpect(status().isOk()));
        BigDecimal interest=CreditCardMath.cents(CreditCardMath.interest(new BigDecimal("1000"),new BigDecimal("24"),purchaseDate,billing));
        assertEquals(0,interest.compareTo(statement.path("INTEREST_AMOUNT").decimalValue()));
        assertEquals(statement.path("STATEMENT_ID"),value(send("/"+id+"/statements",customer,Map.of("requestKey",key(),"statementDate",billing.toString())).andExpect(status().isOk())).path("STATEMENT_ID"));
        String original=json.writeValueAsString(statement);
        LocalDate late=billing.plusDays(21);clock.set(late.atTime(12,0).atZone(zone).toInstant());customer=login("customer");
        send("/"+id+"/purchases",customer,purchase("1",key())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MINIMUM_PAYMENT_OVERDUE"));
        send("/"+id+"/repayments",customer,Map.of("requestKey",key(),"amount","200")).andExpect(status().isOk());
        send("/"+id+"/purchases",customer,purchase("1",key())).andExpect(status().isOk());
        assertEquals(original,json.writeValueAsString(value(send("/"+id+"/statements",customer,bill).andExpect(status().isOk()))));reconciled(id);
    }

    @Test void retiredTermsExpiredCardAndInvalidMoneyFailCleanly()throws Exception{
        String id=activeCard("0","10000");
        for(String amount:List.of("0","-1","1.001","10000001"))send("/"+id+"/purchases",customer,purchase(amount,key())).andExpect(status().isBadRequest());
        var p=getCard(id,customer).path("TERMS");
        send("/products/"+p.path("PRODUCT_ID").asText()+"/retire",admin,Map.of("requestKey",key(),"rowVersion",p.path("ROW_VERSION").asLong(),"reason","Withdraw from sale")).andExpect(status().isOk());
        // Accepted terms remain serviceable after withdrawal from sale.
        send("/"+id+"/purchases",customer,purchase("100",key())).andExpect(status().isOk());
        db.update("UPDATE M11_CC_CARD SET EXPIRY_DATE=ISSUED_DATE+1 WHERE CARD_ID=?",id);
        LocalDate tomorrow=LocalDate.now(clock.withZone(zone)).plusDays(1);clock.set(tomorrow.atTime(12,0).atZone(zone).toInstant());customer=login("customer");
        // If tomorrow is a billing day, catch up the zero-interest statement first.
        if(getCard(id,customer).path("BILLING_REQUIRED").asBoolean())send("/"+id+"/statements",customer,Map.of("requestKey",key(),"statementDate",tomorrow.toString())).andExpect(status().isOk());
        send("/"+id+"/purchases",customer,purchase("1",key())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CARD_EXPIRED"));
        send("/"+id+"/repayments",customer,Map.of("requestKey",key(),"amount","101")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OVERPAYMENT"));
        send("/"+id+"/repayments",customer,Map.of("requestKey",key(),"amount","100")).andExpect(status().isOk());reconciled(id);
    }

    @Test void creditAuthorityAndRevokedOwnershipAreEnforced()throws Exception{
        String p=approvedProduct("24");String app=value(send("/applications",customer,application(p,"10000")).andExpect(status().isOk())).path("APPLICATION_ID").asText();
        db.update("UPDATE M01_IAM_ROLE_AUTHORITY SET MAX_AMOUNT=5000 WHERE AUTHORITY_CODE='CC_LIMIT' AND ROLE_ID=(SELECT ROLE_ID FROM M01_IAM_ROLE WHERE ROLE_CODE='BANK_CHECKER')");
        try{send("/applications/"+app+"/decision",checker,decision(1,"APPROVED","10000")).andExpect(status().isForbidden());}
        finally{db.update("UPDATE M01_IAM_ROLE_AUTHORITY SET MAX_AMOUNT=200000 WHERE AUTHORITY_CODE='CC_LIMIT' AND ROLE_ID=(SELECT ROLE_ID FROM M01_IAM_ROLE WHERE ROLE_CODE='BANK_CHECKER')");}
        String id=value(send("/applications/"+app+"/decision",checker,decision(1,"APPROVED","10000")).andExpect(status().isOk())).path("CARD_ID").asText();
        db.update("UPDATE M01_IAM_CUSTOMER_LINK SET STATUS='REVOKED' WHERE CIF_ID='demo-cif-1'");
        try{mvc.perform(get("/api/v1/credit-cards/"+id).header("Authorization","Bearer "+customer)).andExpect(status().isForbidden());}
        finally{db.update("UPDATE M01_IAM_CUSTOMER_LINK SET STATUS='ACTIVE' WHERE CIF_ID='demo-cif-1'");}
    }

    @Test void kycAgeAndStaffBranchRestrictionsApplyToCards()throws Exception{
        String p=approvedProduct("0");
        db.update("UPDATE M02_CIF_CUSTOMER SET NEXT_REVIEW_DUE_AT=SYSTIMESTAMP-INTERVAL '1' DAY WHERE CIF_ID='demo-cif-1'");
        try{send("/applications",customer,application(p,"10000")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KYC_REVIEW_DUE"));}
        finally{db.update("UPDATE M02_CIF_CUSTOMER SET NEXT_REVIEW_DUE_AT=NULL WHERE CIF_ID='demo-cif-1'");}
        db.update("UPDATE M02_CIF_PARTY SET DATE_OF_BIRTH=DATE '2020-01-01' WHERE PARTY_ID='demo-party-1'");
        try{send("/applications",customer,application(p,"10000")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADULT_INDIVIDUAL_REQUIRED"));}
        finally{db.update("UPDATE M02_CIF_PARTY SET DATE_OF_BIRTH=DATE '1992-05-18' WHERE PARTY_ID='demo-party-1'");}
        String id=activeCard("0","10000");
        db.update("UPDATE M01_IAM_USER_ROLE SET SCOPE_TYPE='BRANCH',SCOPE_REF='OTHER' WHERE USER_ID=(SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='admin')");
        try{
            mvc.perform(get("/api/v1/credit-cards/"+id).header("Authorization","Bearer "+admin)).andExpect(status().isForbidden());
            send("/"+id+"/purchases",admin,purchase("1",key())).andExpect(status().isForbidden());
        }finally{db.update("UPDATE M01_IAM_USER_ROLE SET SCOPE_TYPE='GLOBAL',SCOPE_REF=NULL WHERE USER_ID=(SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='admin')");}
    }

    @Test void blockedCardAndDepositFenceKeepStateAndBalancesConsistent()throws Exception{
        String id=activeCard("0","10000");
        send("/"+id+"/controls",customer,Map.of("requestKey",key(),"rowVersion",1,"action","FREEZE","reason","Stale screen")).andExpect(status().isConflict());
        send("/"+id+"/purchases",customer,purchase("150",key())).andExpect(status().isOk());
        control(id,"BLOCK",customer).andExpect(status().isForbidden());control(id,"BLOCK",admin).andExpect(status().isOk());
        control(id,"UNFREEZE",customer).andExpect(status().isConflict());
        db.update("UPDATE M05_POSTING_FENCE SET DEBIT_STATUS='CLOSED' WHERE BANK_ACCOUNT_ID=1");
        BigDecimal before=balance();
        try{send("/"+id+"/repayments",customer,Map.of("requestKey",key(),"amount","150")).andExpect(status().isConflict());}
        finally{db.update("UPDATE M05_POSTING_FENCE SET DEBIT_STATUS='OPEN' WHERE BANK_ACCOUNT_ID=1");}
        assertEquals(0,before.compareTo(balance()));equalMoney("150",getCard(id,customer).path("OUTSTANDING_BALANCE").decimalValue());
        send("/"+id+"/repayments",customer,Map.of("requestKey",key(),"amount","150")).andExpect(status().isOk());reconciled(id);
    }
}
