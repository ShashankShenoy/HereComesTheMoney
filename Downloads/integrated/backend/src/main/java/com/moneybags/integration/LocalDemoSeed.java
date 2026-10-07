package com.moneybags.integration;

import com.moneybags.iam.service.BootstrapService;
import com.moneybags.txn.core.LedgerService;
import com.moneybags.txn.api.Contracts.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;

/** Synthetic fixture data only. The local profile never connects to Oracle. */
@Component @Profile("local")
public class LocalDemoSeed implements ApplicationRunner {
 private final JdbcTemplate db;private final BootstrapService bootstrap;private final PasswordEncoder encoder;private final LedgerService ledger;
 public LocalDemoSeed(JdbcTemplate db,BootstrapService bootstrap,PasswordEncoder encoder,LedgerService ledger){this.db=db;this.bootstrap=bootstrap;this.encoder=encoder;this.ledger=ledger;}

 @Override @Transactional public void run(ApplicationArguments args){
  bootstrap.createFirstAdmin("admin","LocalBanking!2026");
  String admin=db.queryForObject("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='admin'",String.class);
  String role=db.queryForObject("SELECT ROLE_ID FROM M01_IAM_ROLE WHERE ROLE_CODE='BANK_ADMIN'",String.class);
  String checkerRole=db.queryForObject("SELECT ROLE_ID FROM M01_IAM_ROLE WHERE ROLE_CODE=?",String.class,com.moneybags.iam.service.BankRolePolicy.CHECKER);
  String checker=user("checker","EMPLOYEE",checkerRole,"GLOBAL");
  String customerRole=UUID.randomUUID().toString();
  db.update("INSERT INTO M01_IAM_ROLE(ROLE_ID,ROLE_CODE,DISPLAY_NAME,STATUS,SENSITIVE_FLAG) VALUES (?,'RETAIL_CUSTOMER','Retail customer','ACTIVE','N')",customerRole);
  for(String code:List.of("ACCOUNT_READ","TXN_READ","TXN_POST","PAYMENT_READ","PAYMENT_CREATE","STATEMENT_READ","PRIVACY_CONSENT_SELF","PRIVACY_CONSENT_VIEW","FX_READ","LOAN_READ","LOAN_ACCEPT","CIF_READ","PRODUCT_READ"))
   db.update("INSERT INTO M01_IAM_ROLE_PERMISSION(ROLE_ID,PERMISSION_ID) SELECT ?,PERMISSION_ID FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE=?",customerRole,code);
  String customer=user("customer","CUSTOMER",customerRole,"SELF");
  String other=user("customer2","CUSTOMER",customerRole,"SELF");
  for(int i=1;i<=2;i++){
   String cif="demo-cif-"+i,party="demo-party-"+i;
   db.update("INSERT INTO M02_CIF_PARTY(PARTY_ID,PARTY_TYPE,DATE_OF_BIRTH) VALUES (?,'INDIVIDUAL',DATE '1992-05-18')",party);
   db.update("INSERT INTO M02_CIF_CUSTOMER(CIF_ID,CIF_NUMBER,PARTY_ID,STATUS,HOME_BRANCH_REF,KYC_STATUS,RISK_LEVEL) VALUES (?,?,?,'ACTIVE','MUM001','VERIFIED','LOW')",cif,"MB-CIF-000"+i,party);
   db.update("INSERT INTO M02_KYC_CASE(CASE_ID,CIF_ID,CASE_TYPE,STATUS,MAKER_USER_ID,ASSIGNED_OFFICER_USER_ID,DECIDED_AT) VALUES (?,?,'ONBOARDING','APPROVED',?,?,SYSTIMESTAMP)",UUID.randomUUID().toString(),cif,admin,checker);
   db.update("INSERT INTO M02_CIF_NAME(NAME_ID,PARTY_ID,NAME_TYPE,FULL_NAME,VERIFICATION_STATUS,VALID_FROM) VALUES (?,?,'LEGAL',?,'VERIFIED',SYSTIMESTAMP)",UUID.randomUUID().toString(),party,i==1?"Aarav Mehta (demo)":"Mira Shah (demo)");
   String uid=i==1?customer:other;
   db.update("INSERT INTO M01_IAM_CUSTOMER_LINK(LINK_ID,USER_ID,CIF_ID,RELATIONSHIP_TYPE,STATUS,VALID_FROM) VALUES (?,?,?,'SELF','ACTIVE',SYSTIMESTAMP)",UUID.randomUUID().toString(),uid,cif);
   // Public demonstration secret, accepted only in the isolated, in-memory profile.
   String secret=(i==1?"customer":"customer2")+"-demo-hash-";
   secret=(secret+"x".repeat(43)).substring(0,43);
   db.update("INSERT INTO MBX_CUSTOMER_SECRET(USER_ID,SECRET_DIGEST) VALUES (?,?)",uid,CustomerHashService.digest(secret));
  }
  db.update("INSERT INTO M03_PM_PRODUCT(PRODUCT_ID,PRODUCT_CODE,PRODUCT_NAME,PRODUCT_TYPE,CURRENCY_CODE,BUSINESS_OWNER_REF,SALES_START_AT,CREATED_BY_USER_ID) VALUES (1,'SAV-01','Everyday Savings','SAVINGS','INR','RETAIL',?,?)",OffsetDateTime.now().minusDays(30),admin);
  db.update("INSERT INTO M03_PM_PRODUCT_VERSION(PRODUCT_VERSION_ID,PRODUCT_ID,VERSION_NO,VERSION_STATE,EFFECTIVE_FROM_AT,DEFAULT_TXN_ACTION,CHANGE_REASON,RULE_SET_HASH,CREATED_BY_USER_ID) VALUES (1,1,1,'ACTIVE',?,'ALLOW','Synthetic local fixture',?,?)",OffsetDateTime.now().minusDays(30),"a".repeat(64),admin);
  db.update("INSERT INTO M03_PM_ACCOUNT_RULE(PRODUCT_VERSION_ID,MIN_OPENING_BALANCE,MINOR_ALLOWED) VALUES (1,0,'N')");
  db.update("INSERT INTO M03_PM_AVAILABILITY(PRODUCT_VERSION_ID,CURRENCY_CODE,OFFER_FROM_AT) VALUES (1,'INR',?)",OffsetDateTime.now().minusDays(30));
  db.update("INSERT INTO M03_PM_TRANSACTION_RULE(PRODUCT_VERSION_ID,RULE_CODE,OPERATION_CODE,DIRECTION_CODE,ACTION_CODE,PRIORITY_NO) VALUES (1,'TRANSFER','INTERNAL_TRANSFER','BOTH','ALLOW',1)");
  db.update("INSERT INTO M03_PM_LIMIT_RULE(PRODUCT_VERSION_ID,RULE_CODE,OPERATION_CODE,PERIOD_CODE,MAX_AMOUNT,MAX_COUNT,CURRENCY_CODE) VALUES (1,'DAY_TRANSFER','INTERNAL_TRANSFER','DAY',100000,50,'INR')");
  for(int i=1;i<=2;i++){
   db.update("INSERT INTO M04_BANK_ACCOUNT(ACCOUNT_ID,ACCOUNT_NUMBER,PRIMARY_CIF_ID,PRODUCT_ID,PRODUCT_VERSION_ID,BRANCH_CODE,CURRENCY_CODE,LIFECYCLE_STATUS,ACCOUNT_STATUS,ACCOUNT_OPERATION_MODE,MAJORITY_REVIEW_STATUS,OPEN_REQUEST_ID,CREATED_BY_USER_ID,ACTIVATED_AT) VALUES (?,?,?,1,1,'MUM001','INR','ACTIVE','ACTIVE','SELF_OPERATED','NOT_APPLICABLE',?,?,SYSTIMESTAMP)",i,"MB00000000"+i,"demo-cif-"+i,"demo-opening-"+i,admin);
   db.update("INSERT INTO M04_ACCOUNT_PARTY(ACCOUNT_ID,CIF_ID,PARTY_ROLE,OPERATING_INSTRUCTION,IS_ACTIVE,EFFECTIVE_FROM,CREATED_BY_USER_ID) VALUES (?,?,'PRIMARY_HOLDER','SELF','Y',SYSDATE,?)",i,"demo-cif-"+i,admin);
   ledger.applyControl(i,new ControlRequest(UUID.randomUUID(),1L,"OPEN","OPEN",BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"DEMO_OPENING"),admin);
   db.update("UPDATE M04_BANK_ACCOUNT SET FINANCIAL_CONTROL_VERSION=1 WHERE ACCOUNT_ID=?",i);
  }
  for(Object[] gl:List.of(new Object[]{1,"CUSTOMER","Customer deposits","LIABILITY","CR"},new Object[]{2,"CASH","Teller cash","ASSET","DR"},new Object[]{3,"SUSPENSE","Payment settlement suspense","LIABILITY","CR"},new Object[]{4,"LOANS","Loans receivable","ASSET","DR"},new Object[]{5,"INCOME","Interest income","INCOME","CR"}))
   db.update("INSERT INTO M05_GL_ACCOUNT(GL_ACCOUNT_ID,GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE) VALUES (?,?,?,?,?)",gl);
  for(String type:List.of("TRANSFER","DEPOSIT","WITHDRAWAL","PAYMENT","LOAN"))
   db.update("INSERT INTO M05_GL_PRODUCT_MAPPING(PRODUCT_VERSION_ID,POSTING_TYPE,GL_ROLE_CODE,GL_ACCOUNT_ID,EFFECTIVE_FROM,CREATED_BY) VALUES (1,?,'CUSTOMER_LIABILITY',1,?,?)",type,LocalDate.now().minusDays(30),admin);
  db.update("INSERT INTO M05_GL_ACCOUNT(GL_ACCOUNT_ID,GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE) VALUES (6,'RESERVE','Simulated reserve','ASSET','DR')");
  db.update("INSERT INTO M05_GL_ACCOUNT(GL_ACCOUNT_ID,GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE) VALUES (7,'EQUITY','Demo opening equity','EQUITY','CR')");
  db.update("INSERT INTO M05_GL_ACCOUNT(GL_ACCOUNT_ID,GL_CODE,GL_NAME,ACCOUNT_CLASS,NORMAL_SIDE) VALUES (8,'VAULT_CASH','Branch vault cash','ASSET','DR')");
  db.update("INSERT INTO M05_GL_PRODUCT_MAPPING(PRODUCT_VERSION_ID,POSTING_TYPE,GL_ROLE_CODE,GL_ACCOUNT_ID,EFFECTIVE_FROM,CREATED_BY) VALUES (1,'PAYMENT','SETTLEMENT_SUSPENSE',3,?,?)",LocalDate.now().minusDays(30),admin);
  db.update("INSERT INTO M07_RESERVE_ACCOUNT(RESERVE_ACCOUNT_ID,RESERVE_ACCOUNT_CODE,ACCOUNT_TYPE,EXTERNAL_ACCOUNT_REF,GL_ACCOUNT_ID) VALUES (1,'DEMO-RBI','RBI_CURRENT','SIMULATED-NOT-REAL',6)");
  db.update("INSERT INTO M05_TXN_TRANSACTION_LOG(TXN_ID,ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,AMOUNT,VALUE_DATE) VALUES (3,?,'BRANCH','demo-reserve',?,'demo-reserve','ADJUSTMENT','POSTED',1000000,?)",admin,CustomerHashService.digest("demo-reserve"),LocalDate.now());
  long openingJournal=ledger.postJournal(new JournalRequest("demo-reserve-funding","ADJUSTMENT",3L,null,null,null,null,null,LocalDate.now(),"Synthetic reserve opening",List.of(new JournalLine(6L,null,null,null,"DR",new BigDecimal("1000000"),"Demo reserve"),new JournalLine(7L,null,null,null,"CR",new BigDecimal("1000000"),"Demo equity"))),admin).journalId();
  db.update("INSERT INTO M07_SETTLEMENT_CYCLE(RAIL_CODE,RESERVE_ACCOUNT_ID,CYCLE_REFERENCE,CYCLE_DIRECTION,GROSS_IN_AMOUNT,NET_AMOUNT,NET_MOVEMENT_SIDE,STATUS,CLOSED_AT,SETTLED_AT) VALUES ('RTGS',1,'SIMULATED-OPENING','INBOUND',1000000,1000000,'IN','SETTLED',SYSTIMESTAMP,SYSTIMESTAMP)");
  long openingCycle=db.queryForObject("SELECT SETTLEMENT_CYCLE_ID FROM M07_SETTLEMENT_CYCLE WHERE CYCLE_REFERENCE='SIMULATED-OPENING'",Long.class);
  db.update("INSERT INTO M07_SETTLEMENT_EVIDENCE(RESERVE_ACCOUNT_ID,SETTLEMENT_CYCLE_ID,RAIL_CODE,EVIDENCE_TYPE,EVIDENCE_STATUS,MOVEMENT_SIDE,AMOUNT,EXTERNAL_SETTLEMENT_REF,EVIDENCE_HASH,OCCURRED_AT) VALUES (1,?,'RTGS','MANUAL_VERIFICATION','VERIFIED','IN',1000000,'SIMULATED-OPENING',?,SYSTIMESTAMP)",openingCycle,CustomerHashService.digest("simulated-reserve-opening"));
  long openingEvidence=db.queryForObject("SELECT EVIDENCE_ID FROM M07_SETTLEMENT_EVIDENCE WHERE EXTERNAL_SETTLEMENT_REF='SIMULATED-OPENING'",Long.class);
  db.update("INSERT INTO M07_CENTRAL_TREASURY_LEDGER(RESERVE_ACCOUNT_ID,SETTLEMENT_CYCLE_ID,EVIDENCE_ID,RAIL_CODE,MOVEMENT_SIDE,AMOUNT,EXTERNAL_SETTLEMENT_REF,GL_JOURNAL_ID,SETTLED_AT) VALUES (1,?,?,'RTGS','IN',1000000,'SIMULATED-OPENING',?,SYSTIMESTAMP)",openingCycle,openingEvidence,openingJournal);
  long openingEntry=db.queryForObject("SELECT TREASURY_ENTRY_ID FROM M07_CENTRAL_TREASURY_LEDGER WHERE EXTERNAL_SETTLEMENT_REF='SIMULATED-OPENING'",Long.class);
  db.update("INSERT INTO M07_RESERVE_POSITION(RESERVE_ACCOUNT_ID,CONFIRMED_BALANCE,LAST_TREASURY_ENTRY_ID) VALUES (1,1000000,?)",openingEntry);
  for(int i=1;i<=2;i++){
   BigDecimal amount=BigDecimal.valueOf(i==1?75000:25000);
   db.update("INSERT INTO M05_TXN_TRANSACTION_LOG(TXN_ID,ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,TARGET_ACCOUNT_ID,PRODUCT_VERSION_ID,AMOUNT,VALUE_DATE) VALUES (?,?,'BRANCH',?,?,'demo-opening','OPENING_FUNDING','POSTED',?,1,?,?)",i,admin,"demo-funding-"+i,CustomerHashService.digest("demo-funding-"+i),i,amount,LocalDate.now());
   ledger.postJournal(new JournalRequest("demo-funding-"+i,"OPENING_FUNDING",(long)i,null,null,null,null,null,LocalDate.now(),"Synthetic opening funds",List.of(new JournalLine(8L,null,null,null,"DR",amount,"Demo vault cash"),new JournalLine(1L,(long)i,null,null,"CR",amount,"Opening funds"))),admin);
  }
  db.update("INSERT INTO MBX_BRANCH_VAULT(BRANCH_CODE,VAULT_GL_ID,CASH_BALANCE,OPENING_EVIDENCE_REF) VALUES ('MUM001',8,100000,'LOCAL_SYNTHETIC_OPENING')");
  db.update("INSERT INTO MBX_CURRENCY(CURRENCY_CODE,DISPLAY_NAME) VALUES ('INR','Indian rupee')");
  db.update("INSERT INTO MBX_CURRENCY(CURRENCY_CODE,DISPLAY_NAME) VALUES ('USD','US dollar')");
  db.update("INSERT INTO MBX_CURRENCY(CURRENCY_CODE,DISPLAY_NAME) VALUES ('EUR','Euro')");
  db.update("INSERT INTO MBX_FX_RATE(RATE_ID,BASE_CURRENCY,QUOTE_CURRENCY,BUY_RATE,MID_RATE,SELL_RATE,SOURCE_CODE,OBSERVED_AT,VALID_UNTIL,STATUS,MAKER_ID,CHECKER_ID,REASON_TEXT) VALUES (?,'USD','INR',83,84,85,'DEMO_NOT_MARKET',?,?,'APPROVED',?,?,'Illustrative data, not live market pricing')",UUID.randomUUID().toString(),OffsetDateTime.now(),OffsetDateTime.now().plusHours(12),admin,checker);
  db.update("INSERT INTO M03_PM_PRODUCT(PRODUCT_ID,PRODUCT_CODE,PRODUCT_NAME,PRODUCT_TYPE,CURRENCY_CODE,BUSINESS_OWNER_REF,SALES_START_AT,CREATED_BY_USER_ID) VALUES (2,'LN-01','Monthly Personal Loan','LOAN','INR','RETAIL',?,?)",OffsetDateTime.now().minusDays(30),admin);
  db.update("INSERT INTO M03_PM_PRODUCT_VERSION(PRODUCT_VERSION_ID,PRODUCT_ID,VERSION_NO,VERSION_STATE,EFFECTIVE_FROM_AT,DEFAULT_TXN_ACTION,CHANGE_REASON,RULE_SET_HASH,CREATED_BY_USER_ID) VALUES (2,2,1,'ACTIVE',?,'ALLOW','Synthetic loan fixture',?,?)",OffsetDateTime.now().minusDays(30),"c".repeat(64),admin);
  db.update("INSERT INTO M03_PM_AVAILABILITY(PRODUCT_VERSION_ID,CURRENCY_CODE,OFFER_FROM_AT) VALUES (2,'INR',?)",OffsetDateTime.now().minusDays(30));
  db.update("INSERT INTO M03_PM_LOAN_RULE(PRODUCT_VERSION_ID,LOAN_CATEGORY,MIN_LOAN_AMOUNT,MAX_LOAN_AMOUNT,MIN_TENURE_MONTHS,MAX_TENURE_MONTHS,ALLOWED_INTEREST_TYPE,REPAYMENT_FREQUENCY,AMORTIZATION_METHOD) VALUES (2,'PERSONAL',1000,1000000,1,60,'FIXED','MONTHLY','REDUCING_BALANCE')");
  db.update("INSERT INTO M03_PM_INTEREST_RULE(PRODUCT_VERSION_ID,RULE_CODE,INTEREST_TYPE,INTEREST_METHOD,FIXED_RATE_PCT,DAY_COUNT_BASIS,COMPOUND_FREQUENCY,PAYOUT_FREQUENCY,ROUNDING_MODE) VALUES (2,'BASE','FIXED','SIMPLE',12,'ACT/365','NONE','MONTHLY','HALF_EVEN')");
  db.update("INSERT INTO M01_IAM_ROLE_AUTHORITY(AUTHORITY_ID,ROLE_ID,AUTHORITY_CODE,CURRENCY_CODE,MAX_AMOUNT,MAX_RATE_PCT,VALID_FROM) VALUES (?,?,'LOAN_SANCTION','INR',1000000,30,?)",UUID.randomUUID().toString(),role,OffsetDateTime.now().minusDays(1));
  db.update("INSERT INTO M01_IAM_ROLE_AUTHORITY(AUTHORITY_ID,ROLE_ID,AUTHORITY_CODE,CURRENCY_CODE,MAX_AMOUNT,MAX_RATE_PCT,VALID_FROM) VALUES (?,?,'LOAN_SANCTION','INR',1000000,30,?)",UUID.randomUUID().toString(),checkerRole,OffsetDateTime.now().minusDays(1));
  for(String component:List.of("LOAN_PRINCIPAL","LOAN_INTEREST","INTEREST_INCOME"))db.update("INSERT INTO M05_GL_PRODUCT_MAPPING(PRODUCT_VERSION_ID,POSTING_TYPE,GL_ROLE_CODE,GL_ACCOUNT_ID,EFFECTIVE_FROM,CREATED_BY) VALUES (2,'LOAN',?,?,?,?)",component,component.equals("INTEREST_INCOME")?5:4,LocalDate.now().minusDays(30),admin);
  for(String audience:List.of("CUSTOMER","STAFF")){
   String rules="{\"showBalances\":true,\"showSourceReferences\":false}";
   db.update("INSERT INTO M10_MASKING_PROFILE(MASKING_PROFILE_ID,PROFILE_CODE,VERSION_NO,CHANNEL_CODE,AUDIENCE_CODE,RULES_JSON,RULES_SHA256,STATUS,APPROVED_BY,APPROVED_AT,CREATED_BY) VALUES (?,?,1,'WEB',?,?,?,'APPROVED',?,SYSTIMESTAMP,?)",UUID.randomUUID().toString(),"DEMO_"+audience,audience,rules,HexFormat.of().formatHex(CustomerHashService.digest(rules)),checker,admin);
   for(String type:List.of("NORMAL","REVERSAL","RETURN","ADJUSTMENT"))
    db.update("INSERT INTO M10_NARRATION_CATALOG(NARRATION_CATALOG_ID,NARRATION_CODE,LOCALE_CODE,AUDIENCE_CODE,VERSION_NO,RENDERED_TEXT,EFFECTIVE_FROM,APPROVAL_STATUS,CHECKSUM_SHA256,APPROVED_BY,APPROVED_AT,CREATED_BY) VALUES (?,?,'en-IN',?,1,?,?,'APPROVED',?,?,SYSTIMESTAMP,?)",UUID.randomUUID().toString(),type,audience,type.equals("NORMAL")?"Account transaction":type,OffsetDateTime.now().minusDays(30),"b".repeat(64),checker,admin);
  }
  for(String pair:List.of("M03_PM_PRODUCT:PRODUCT_ID","M03_PM_PRODUCT_VERSION:PRODUCT_VERSION_ID","M04_BANK_ACCOUNT:ACCOUNT_ID","M05_GL_ACCOUNT:GL_ACCOUNT_ID","M05_TXN_TRANSACTION_LOG:TXN_ID","M07_RESERVE_ACCOUNT:RESERVE_ACCOUNT_ID")){var names=pair.split(":");db.execute("ALTER TABLE "+names[0]+" ALTER COLUMN "+names[1]+" RESTART WITH 100");}
 }
 private String user(String name,String type,String role,String scope){
  String id=UUID.randomUUID().toString();
  db.update("INSERT INTO M01_IAM_USER(USER_ID,USERNAME,USER_TYPE,EMPLOYEE_REF,STATUS) VALUES (?,?,?,?,'ACTIVE')",id,name,type,type.equals("EMPLOYEE")?"DEMO-"+name:null);
  db.update("INSERT INTO M01_IAM_CREDENTIAL(CREDENTIAL_ID,USER_ID,PASSWORD_HASH,HASH_SCHEME,STATUS) VALUES (?,?,?,'BCRYPT','ACTIVE')",UUID.randomUUID().toString(),id,encoder.encode("LocalBanking!2026"));
  db.update("INSERT INTO M01_IAM_USER_ROLE(ASSIGNMENT_ID,USER_ID,ROLE_ID,SCOPE_TYPE,STATUS,VALID_FROM) VALUES (?,?,?,?,'ACTIVE',?)",UUID.randomUUID().toString(),id,role,scope,OffsetDateTime.now().minusMinutes(1));
  return id;
 }
}

