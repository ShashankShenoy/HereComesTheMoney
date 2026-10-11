import com.moneybags.MoneybagsApplication;
import com.moneybags.common.database.*;
import com.moneybags.iam.repository.IamRepository;
import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.iam.service.CustomerSignupService;
import com.moneybags.iam.dto.CustomerSignupRequest;
import com.moneybags.product.service.*;
import com.moneybags.product.dto.ProductDtos;
import com.moneybags.account.domain.AccountService;
import com.moneybags.account.api.Models;
import com.moneybags.loan.*;
import com.moneybags.creditcard.*;
import com.moneybags.integration.*;
import com.moneybags.txn.core.LedgerService;
import com.moneybags.txn.api.Contracts.TransferRequest;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.nio.file.*;

/** Opt-in live Oracle acceptance. Commits only its dedicated product records.
 * Uses existing staff entitlements without granting roles. Future-date scenarios
 * and negative assertions run in savepoints and roll back. Never run on H2.
 */
public class ProductOracleAcceptance {
 @org.springframework.context.annotation.Configuration
 static class Nio2Source {
  @org.springframework.context.annotation.Bean
  org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory> acceptanceConnector(){return f->f.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");}
 }
 static ConfigurableApplicationContext ctx;
 static JdbcTemplate jdbc; static BusinessRepository db; static IamRepository iam;
 static ProductService products; static AccountService accounts; static LoanService loans;
 static LoanServicingController servicing; static LedgerService ledger; static CreditCardService cards;
 static TermDepositController deposits; static TransactionTemplate tx,nested;
 static UserPrincipal maker,checker,customer; static String cif;
 static long funding; static final String RUN="product-review-20261011";
 static final List<String> results=new ArrayList<>();
 static final Set<String> completed=new HashSet<>();
 static final List<String> sessions=new ArrayList<>();
 static BigDecimal dec(Object x){return new BigDecimal(x.toString());}
 static long num(Map<String,Object> m,String k){return ((Number)m.get(k)).longValue();}
 static String key(String s){return RUN+"-"+s;}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void money(BigDecimal actual,BigDecimal expected,String why){check(actual.compareTo(expected)==0,why+": expected "+expected+", got "+actual);}
 static void actor(UserPrincipal u){if(u!=null&&iam!=null)iam.setAuthLevel(u.sessionId(),"MFA@"+Instant.now().getEpochSecond()); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,List.of()));}
 static UserPrincipal identity(String name){
  var u=iam.userByName(name).orElseThrow(); var now=OffsetDateTime.now(ZoneOffset.UTC); String sid=UUID.randomUUID().toString();
  iam.createSession(sid,u.userId(),"MONEYBAGS_WEB",RUN,now,now.plusHours(1),now.plusHours(1));
  iam.setAuthLevel(sid,"MFA@"+Instant.now().getEpochSecond()); sessions.add(sid);
  return new UserPrincipal(u.userId(),name,u.userType(),sid,1L,iam.roles(u.userId(),now),iam.permissions(u.userId(),now),iam.cifIds(u.userId(),now));
 }
 static void denied(Runnable action,String why){
  nested.execute(s->{s.setRollbackOnly();boolean rejected=false;try{action.run();}catch(com.moneybags.common.api.BusinessException|com.moneybags.txn.api.ApiException|com.moneybags.account.api.ApiException e){rejected=true;}check(rejected,why);return null;});
 }
 static void scenario(String name,Runnable action){
  if(completed.contains(name)){System.out.println("RECHECK | "+name+" | previous committed acceptance retained");return;}
  try{tx.execute(s->{action.run();balanced();return null;});results.add("PASS | "+name);}
  catch(Throwable e){results.add("FAIL | "+name+" | "+e.getClass().getSimpleName()+" | "+e.getMessage());e.printStackTrace();}
  System.out.println(results.get(results.size()-1));save();
 }
 static void save(){try{Files.write(Path.of(".runtime/product-oracle-results.txt"),results);}catch(Exception e){throw new RuntimeException(e);}}
 static long rowVersion(long version){return jdbc.queryForObject("SELECT ROW_VERSION FROM M03_PM_PRODUCT_VERSION WHERE PRODUCT_VERSION_ID=?",Long.class,version);}
 static void copyRules(long source,long target){
  for(var f:ProductRuleCatalog.FAMILIES.values()){
   check(!f.code().equals("tiers")||db.count("SELECT COUNT(*) FROM M03_PM_INTEREST_TIER T JOIN M03_PM_INTEREST_RULE I ON I.INTEREST_RULE_ID=T.INTEREST_RULE_ID WHERE I.PRODUCT_VERSION_ID=?",source)==0,"Tier copying requires parent rebinding");
   if(f.code().equals("tiers"))continue;
   for(var row:db.rows("SELECT * FROM "+f.table()+" WHERE PRODUCT_VERSION_ID=?",source)){
    var values=new LinkedHashMap<String,Object>();for(var field:f.fields())values.put(field.name(),row.get(field.name()));
    products.addRule(maker,BigDecimal.valueOf(target),f.code(),new ProductDtos.RuleInput(rowVersion(target),values),"MUM001");
   }
  }
 }
 static void approve(long v){
  var request=products.submit(maker,BigDecimal.valueOf(v),new ProductDtos.Revision(rowVersion(v),"Complete product configuration",RUN),"MUM001");
  actor(checker);products.decide(checker,dec(request.get("APPROVAL_ID")),new ProductDtos.Decision(num(request,"ROW_VERSION"),"APPROVED","Product rules verified"),"MUM001");
  products.activate(checker,BigDecimal.valueOf(v),new ProductDtos.Revision(rowVersion(v),"Make completed product available",RUN),"MUM001");actor(maker);
 }
 static void mappings(long from,long to){
  jdbc.update("INSERT INTO M05_GL_PRODUCT_MAPPING(PRODUCT_VERSION_ID,POSTING_TYPE,GL_ROLE_CODE,GL_ACCOUNT_ID,STATUS,EFFECTIVE_FROM,EFFECTIVE_TO,CREATED_BY) SELECT ?,POSTING_TYPE,GL_ROLE_CODE,GL_ACCOUNT_ID,STATUS,EFFECTIVE_FROM,EFFECTIVE_TO,? FROM M05_GL_PRODUCT_MAPPING S WHERE PRODUCT_VERSION_ID=? AND STATUS='ACTIVE' AND NOT EXISTS(SELECT 1 FROM M05_GL_PRODUCT_MAPPING T WHERE T.PRODUCT_VERSION_ID=? AND T.POSTING_TYPE=S.POSTING_TYPE AND T.GL_ROLE_CODE=S.GL_ROLE_CODE AND T.STATUS='ACTIVE')",to,maker.userId(),from,to);
 }
 static void repairCatalog(){
  actor(maker);
  long liberty=db.count("SELECT COUNT(*) FROM M03_PM_PRODUCT_VERSION WHERE PRODUCT_ID=28 AND VERSION_STATE='ACTIVE'");
  if(liberty==0){
   var draft=products.draft(maker,BigDecimal.valueOf(28),new ProductDtos.DraftInput(null,OffsetDateTime.now().minusMinutes(2),null,"ALLOW","Complete Liberty Savings using existing approved savings terms"),"MUM001");
   @SuppressWarnings("unchecked") var version=(Map<String,Object>)draft.get("version");long v=num(version,"PRODUCT_VERSION_ID");
   copyRules(41,v);mappings(41,v);approve(v);results.add("REPAIRED | LIB_SAVINGS | active version="+v+" | terms match approved savings version 41");
  }
  if(db.count("SELECT COUNT(*) FROM M03_PM_PRODUCT WHERE PRODUCT_ID=29 AND PRODUCT_TYPE='SAVINGS'")==1){
   check(db.count("SELECT COUNT(*) FROM M04_BANK_ACCOUNT WHERE PRODUCT_ID=29")==0,"Misclassified product has accounts; manual review required");
   check(db.count("SELECT COUNT(*) FROM M03_PM_PRODUCT_VERSION WHERE PRODUCT_ID=29 AND VERSION_STATE<>'DRAFT'")==0,"Misclassified product has approved history; manual review required");
   check(db.count("SELECT COUNT(*) FROM M03_PM_APPROVAL WHERE PRODUCT_ID=29 AND REQUEST_STATUS='PENDING'")==0,"Misclassified product has pending approval");
   jdbc.update("UPDATE M03_PM_PRODUCT SET PRODUCT_TYPE='LOAN',ROW_VERSION=ROW_VERSION+1,UPDATED_AT=SYSTIMESTAMP WHERE PRODUCT_ID=29 AND PRODUCT_CODE='PERSONAL_LOAN_WEB_01' AND PRODUCT_TYPE='SAVINGS'");
   jdbc.update("INSERT INTO MBX_AUDIT(EVENT_ID,ACTOR_ID,ACTION_CODE,RESOURCE_TYPE,RESOURCE_ID,REASON_CODE) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),maker.userId(),"PRODUCT_TYPE_CORRECTED","PRODUCT","29","Corrected unused draft Personal Loan from SAVINGS to LOAN; "+RUN);
   var existing=products.versionDetail(maker,BigDecimal.valueOf(33),"MUM001");
   @SuppressWarnings("unchecked") var rules=(Map<String,Object>)existing.get("rules");
   for(var family:ProductRuleCatalog.FAMILIES.values()){
    @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)rules.get(family.code());
    for(var row:rows)products.deleteRule(maker,BigDecimal.valueOf(33),family.code(),dec(row.get(family.key())),rowVersion(33),"MUM001");
   }
   copyRules(34,33);mappings(34,33);approve(33);results.add("REPAIRED | PERSONAL_LOAN_WEB_01 | LOAN type, active version=33 | terms match approved Personal Loan version 34");
  }
 }
 static long open(long product,long version,String suffix){
  actor(customer);var request=new Models.SelfOpenAccount(key(suffix),cif,product,version);var account=accounts.openSelf(request);
  check(accounts.openSelf(request).id()==account.id(),"Account opening replay duplicated account");
  if(!"ACTIVE".equals(account.lifecycleStatus()))accounts.activateSelf(account.id(),key(suffix+"-activate"));
  ctx.getBean(AccountControlBridge.class).apply(account.id()); check("ACTIVE".equals(accounts.get(account.id()).lifecycleStatus()),"Account failed activation");return account.id();
 }
 static void loan(Map<String,Object> p){
  long product=num(p,"PRODUCT_ID"),v=num(p,"PRODUCT_VERSION_ID");String code=(String)p.get("PRODUCT_CODE");
  var rule=db.one("SELECT * FROM M03_PM_LOAN_RULE WHERE PRODUCT_VERSION_ID=?",v);
  BigDecimal amount=((BigDecimal)rule.get("MIN_LOAN_AMOUNT")).max(new BigDecimal("10000")); int term=((Number)rule.get("MIN_TENURE_MONTHS")).intValue();
  BigDecimal rate=(BigDecimal)db.one("SELECT FIXED_RATE_PCT FROM M03_PM_INTEREST_RULE WHERE PRODUCT_VERSION_ID=?",v).get("FIXED_RATE_PCT");
  actor(customer);BigDecimal before=ledger.position(funding).posted();
  var input=new LoanDtos.SelfApplication(cif,product,v,amount,term,(String)rule.get("LOAN_CATEGORY"));
  var app=loans.apply(customer,input,key(code));check(loans.apply(customer,input,key(code)).id()==app.id(),"Application replay duplicated");
  check("SUBMITTED".equals(app.status()),"Customer loan not submitted");
  denied(()->loans.apply(customer,new LoanDtos.SelfApplication(cif,product,v,new BigDecimal("0.01"),term,input.purposeCode()),key(code+"-invalid")),"Below-minimum loan accepted");
  actor(maker);loans.assess(maker,app.id(),new LoanDtos.Assessment(new BigDecimal("1000000"),BigDecimal.ZERO,"APPROVE","PRODUCT_REVIEW",800,"verification://"+RUN));
  var decision=new LoanDtos.Decision("APPROVE","PRODUCT_REVIEW",amount,term,rate,"LOAN_SANCTION","verification://"+RUN);
  actor(checker);loans.decide(checker,app.id(),decision);
  actor(maker);long offer=num(loans.offer(maker,app.id(),new LoanDtos.Offer("verification://"+RUN+"/"+code+"/offer",OffsetDateTime.now().plusDays(7))),"offerId");
  actor(customer);loans.accept(customer,offer,new LoanDtos.AcceptOffer(funding,funding));
  actor(maker);String document="Product review signed offer "+code;String hash=java.util.HexFormat.of().formatHex(CustomerHashService.digest(document));
  long doc=num(loans.addDocument(maker,app.id(),new LoanDtos.Document("SIGNED_OFFER","verification://"+RUN+"/"+code+"/signed-offer",hash)),"documentRefId");
  actor(checker);loans.verifyDocument(checker,app.id(),doc);
  actor(maker);long facility=num(loans.convert(maker,app.id()),"facilityId");
  long disbursement=num(servicing.request(facility,new LoanServicingController.Disburse(key(code+"-disburse"))),"disbursementId");
  denied(()->servicing.approve(disbursement,new LoanServicingController.Approve("LOAN_SANCTION")),"Maker approved own disbursement");
  actor(checker);var posted=servicing.approve(disbursement,new LoanServicingController.Approve("LOAN_SANCTION"));
  check(servicing.approve(disbursement,new LoanServicingController.Approve("LOAN_SANCTION")).transactionId()==posted.transactionId(),"Disbursement replay duplicated");
  money(ledger.position(funding).posted(),before.add(amount),"Loan credit mismatch");
  actor(customer);var schedule=loans.schedule(customer,facility);check(schedule.size()==term,"Wrong installment count");
  money(schedule.stream().map(r->(BigDecimal)r.get("PRINCIPAL_DUE")).reduce(BigDecimal.ZERO,BigDecimal::add),amount,"Schedule principal mismatch");
  actor(maker);denied(()->servicing.repay(facility,new LoanServicingController.Repay(key(code+"-early"),BigDecimal.ONE)),"Unsupported early repayment accepted");
  nested.execute(s->{s.setRollbackOnly();
   jdbc.update("UPDATE M08_LOAN_SCHEDULE_ITEM SET DUE_DATE=? WHERE FACILITY_ID=? AND INSTALLMENT_NO=1",LocalDate.now(ZoneId.of("Asia/Kolkata")),facility);
   check(servicing.accrue(facility).size()==1,"Due interest did not accrue");check(servicing.accrue(facility).isEmpty(),"Interest accrued twice");
   var first=db.one("SELECT PRINCIPAL_DUE,INTEREST_DUE FROM M08_LOAN_SCHEDULE_ITEM WHERE FACILITY_ID=? AND INSTALLMENT_NO=1",facility);
   BigDecimal due=((BigDecimal)first.get("PRINCIPAL_DUE")).add((BigDecimal)first.get("INTEREST_DUE"));BigDecimal balance=ledger.position(funding).posted();
   var repay=new LoanServicingController.Repay(key(code+"-repayment"),due);long id=servicing.repay(facility,repay).transactionId();
   check(servicing.repay(facility,repay).transactionId()==id,"Repayment replay duplicated");money(ledger.position(funding).posted(),balance.subtract(due),"Repayment debit mismatch");balanced();return null;
  });
  money(ledger.position(funding).posted(),before.add(amount),"Repayment date fixture did not roll back");
  results.add("RECORD | "+code+" | application="+app.id()+" | facility="+facility+" | disbursement="+disbursement+" | principal="+amount+" | installments="+term);
 }
 static void savings(Map<String,Object> p){
  long id=(num(p,"PRODUCT_ID")==27)?funding:open(num(p,"PRODUCT_ID"),num(p,"PRODUCT_VERSION_ID"),(String)p.get("PRODUCT_CODE"));
  long other=(id==funding)?open(27,num(p,"PRODUCT_VERSION_ID"),"savings-transfer-target"):id;
  actor(customer);BigDecimal a=ledger.position(funding).posted(),b=ledger.position(other).posted();

  var transfer=new TransferRequest(funding,other,new BigDecimal("1.00"),"WEB",key(p.get("PRODUCT_CODE")+"-transfer"),UUID.randomUUID().toString(),LocalDate.now(ZoneId.of("Asia/Kolkata")));
  var posted=ctx.getBean(BankingService.class).transfer(transfer);check(ctx.getBean(BankingService.class).transfer(transfer).transactionId()==posted.transactionId(),"Transfer replay duplicated");
  money(ledger.position(funding).posted(),a.subtract(transfer.amount()),"Transfer debit mismatch");money(ledger.position(other).posted(),b.add(transfer.amount()),"Transfer credit mismatch");
  denied(()->ctx.getBean(BankingService.class).transfer(new TransferRequest(funding,other,new BigDecimal("999999999.00"),"WEB",key(p.get("PRODUCT_CODE")+"-overdraft"),UUID.randomUUID().toString(),transfer.valueDate())),"Insufficient funds accepted");
  var preview=ctx.getBean(DepositInterestController.class).accrualPreview(id,null);check(!Set.of("NOT_AVAILABLE","NOT_CONFIGURED","NOT_ACTIVE").contains(preview.get("status")),"Savings interest unavailable");
  results.add("RECORD | "+p.get("PRODUCT_CODE")+" | account="+id+" | transfer="+posted.transactionId()+" | interest="+preview.get("status"));
 }
 static void deposit(Map<String,Object> p){
  long product=num(p,"PRODUCT_ID"),v=num(p,"PRODUCT_VERSION_ID");var terms=db.one("SELECT * FROM M03_PM_TERM_DEPOSIT_RULE WHERE PRODUCT_VERSION_ID=?",v);
  BigDecimal amount=(BigDecimal)terms.get("MIN_DEPOSIT_AMOUNT");int term=((Number)terms.get("MIN_TENURE_MONTHS")).intValue();actor(customer);BigDecimal before=ledger.position(funding).posted();
  var request=new TermDepositController.OpenRequest(product,v,funding,amount,term,key((String)p.get("PRODUCT_CODE")));var opened=deposits.open(request);long id=num(opened,"TERM_DEPOSIT_ID");
  check(num(deposits.open(request),"TERM_DEPOSIT_ID")==id,"Deposit replay duplicated");money(ledger.position(funding).posted(),before.subtract(amount),"Deposit funding debit mismatch");
  actor(maker); denied(()->deposits.mature(id),"Premature fixed deposit payout accepted");
  nested.execute(s->{s.setRollbackOnly();jdbc.update("UPDATE MBX_TERM_DEPOSIT SET START_DATE=?,MATURITY_DATE=? WHERE TERM_DEPOSIT_ID=?",LocalDate.now(ZoneId.of("Asia/Kolkata")).minusMonths(term),LocalDate.now(ZoneId.of("Asia/Kolkata")),id);
   var matured=deposits.mature(id);check("MATURED".equals(matured.get("STATUS")),"Maturity failed");BigDecimal earned=(BigDecimal)matured.get("MATURITY_INTEREST");
   money(ledger.position(funding).posted(),before.add(earned),"Maturity principal and interest mismatch");deposits.mature(id);money(ledger.position(funding).posted(),before.add(earned),"Maturity replay paid twice");balanced();return null;});
  check("ACTIVE".equals(deposits.get(id).get("STATUS")),"Maturity fixture failed rollback");money(ledger.position(funding).posted(),before.subtract(amount),"Maturity fixture balance rollback failed");
  results.add("RECORD | "+p.get("PRODUCT_CODE")+" | deposit="+id+" | principal="+amount+" | maturityInterest="+opened.get("MATURITY_INTEREST"));
 }
 static void card(Map<String,Object> p){
  String product=(String)p.get("PRODUCT_ID");BigDecimal limit=(BigDecimal)p.get("MINIMUM_LIMIT");actor(customer);
  var input=new CreditCardDtos.ApplicationInput(key("classic-apply"),product,cif,funding,limit,true);var application=cards.apply(input);String app=(String)application.get("APPLICATION_ID");check(app.equals(cards.apply(input).get("APPLICATION_ID")),"Card application replay duplicated");
  actor(checker);cards.decideApplication(app,new CreditCardDtos.Decision(key("classic-approve"),num(application,"ROW_VERSION"),"APPROVED","Product review",limit));
  actor(customer);String id=(String)db.one("SELECT CARD_ID FROM M11_CC_CARD WHERE APPLICATION_ID=?",app).get("CARD_ID");
  cards.control(id,new CreditCardDtos.Control(key("classic-activate"),num(cards.card(id),"ROW_VERSION"),"ACTIVATE","Product review"));
  var purchase=new CreditCardDtos.Purchase(key("classic-purchase"),new BigDecimal("10.00"),"Product Review Merchant");var entry=cards.purchase(id,purchase);check(num(cards.purchase(id,purchase),"ENTRY_ID")==num(entry,"ENTRY_ID"),"Purchase replay duplicated");
  denied(()->cards.purchase(id,new CreditCardDtos.Purchase(key("classic-overlimit"),limit.add(BigDecimal.ONE),"Product Review Merchant")),"Over-limit purchase accepted");
  cards.control(id,new CreditCardDtos.Control(key("classic-freeze"),num(cards.card(id),"ROW_VERSION"),"FREEZE","Product review"));
  denied(()->cards.purchase(id,new CreditCardDtos.Purchase(key("classic-frozen-spend"),BigDecimal.ONE,"Product Review Merchant")),"Frozen card purchase accepted");
  cards.control(id,new CreditCardDtos.Control(key("classic-unfreeze"),num(cards.card(id),"ROW_VERSION"),"UNFREEZE","Product review"));
  BigDecimal before=ledger.position(funding).posted();var repayment=new CreditCardDtos.Repayment(key("classic-repay"),new BigDecimal("10.00"));var repaid=cards.repay(id,repayment);check(num(cards.repay(id,repayment),"ENTRY_ID")==num(repaid,"ENTRY_ID"),"Card repayment replay duplicated");
  money(ledger.position(funding).posted(),before.subtract(new BigDecimal("10.00")),"Card repayment debit mismatch");
  check(Boolean.TRUE.equals(cards.reconciliation(id).get("matched")),"Credit card ledger mismatch"); money((BigDecimal)cards.card(id).get("OUTSTANDING_BALANCE"),BigDecimal.ZERO,"Card repayment outstanding balance"); results.add("RECORD | "+p.get("PRODUCT_CODE")+" | application="+app+" | card="+id+" | purchase="+entry.get("ENTRY_ID")+" | repayment="+repaid.get("ENTRY_ID")+" | reconciliation="+cards.reconciliation(id));
 }
 static void balanced(){
  check(db.count("SELECT COUNT(*) FROM (SELECT P.JOURNAL_ID FROM M05_GL_POSTING P JOIN M05_GL_JOURNAL J ON J.JOURNAL_ID=P.JOURNAL_ID WHERE J.CREATED_AT>=TRUNC(SYSDATE) GROUP BY P.JOURNAL_ID HAVING SUM(CASE WHEN P.ENTRY_SIDE='DR' THEN P.AMOUNT ELSE -P.AMOUNT END)<>0)")==0,"Unbalanced journal found");
 }
 static void calendarChecks(){
  var month=YearMonth.now(ZoneId.of("Asia/Kolkata"));
  for(var a:db.rows("SELECT ACCOUNT_ID,PRODUCT_ID,PRODUCT_VERSION_ID FROM M04_BANK_ACCOUNT WHERE PRIMARY_CIF_ID=? AND PRODUCT_ID IN (27,28) AND LIFECYCLE_STATUS='ACTIVE' ORDER BY ACCOUNT_ID",cif)){
   long id=num(a,"ACCOUNT_ID");
   nested.execute(s->{s.setRollbackOnly();
    actor(customer);

    BigDecimal before=ledger.position(id).posted();actor(maker);
    try{
     var zone=ZoneId.of("Asia/Kolkata");
     var futureClock=Clock.fixed(month.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant(),zone);
     var futureLedger=new LedgerService(jdbc,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),new com.moneybags.txn.core.AccountCatalog(jdbc),new com.moneybags.txn.core.PaymentCatalog(jdbc),futureClock);
     var interest=new DepositInterestController(db,ctx.getBean(BankingAccess.class),ctx.getBean(CustomerHashService.class),futureLedger,tx,true);
     var method=DepositInterestController.class.getDeclaredMethod("postMonth",long.class,YearMonth.class,LocalDate.class);method.setAccessible(true);
     var rollout=BusinessRepository.localDate(db.one("SELECT START_DATE FROM MBX_INTEREST_ROLLOUT WHERE CONFIG_ID=1").get("START_DATE"));
     @SuppressWarnings("unchecked") var paid=(Map<String,Object>)method.invoke(interest,id,month,rollout);
     BigDecimal earned=(BigDecimal)paid.get("amount");check(earned!=null&&earned.signum()>=0,"Savings interest did not post");money(ledger.position(id).posted(),before.add(earned),"Savings interest ledger credit mismatch");
     @SuppressWarnings("unchecked") var replay=(Map<String,Object>)method.invoke(interest,id,month,rollout);check("ALREADY_POSTED".equals(replay.get("status")),"Savings interest replay duplicated");balanced();
     results.add("CALENDAR | savings account="+id+" | month="+month+" | credited="+earned+" | future month-end rolled back");
    }catch(ReflectiveOperationException e){throw new RuntimeException(e.getCause()==null?e:e.getCause());}return null;
   });
  }
  String card=(String)db.one("SELECT CARD_ID FROM M11_CC_CARD WHERE CIF_ID=? AND STATUS='ACTIVE'",cif).get("CARD_ID");
  nested.execute(s->{s.setRollbackOnly();actor(customer);LocalDate today=LocalDate.now(ZoneId.of("Asia/Kolkata"));jdbc.update("UPDATE M11_CC_CARD SET NEXT_STATEMENT_DATE=? WHERE CARD_ID=?",today,card);
   var request=new CreditCardDtos.Billing(key("classic-billing"),today);var billed=cards.bill(card,request);check(billed.get("STATEMENT_ID").equals(cards.bill(card,request).get("STATEMENT_ID")),"Billing replay duplicated");money((BigDecimal)billed.get("CLOSING_BALANCE"),BigDecimal.ZERO,"Paid card statement balance");results.add("CALENDAR | credit card="+card+" | statement generated and replay checked | billing date rolled back");return null;
  });
 }
 static int api(String path,String token)throws Exception{
  var connection=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:18101"+path).openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(30000);if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);
  int code=connection.getResponseCode();var stream=code<400?connection.getInputStream():connection.getErrorStream();if(stream!=null)stream.close();connection.disconnect();return code;
 }
 static void apiChecks(){try{
  for(String path:List.of("/api/v1/products/customer-offers?cifId="+cif+"&channel=WEB&currency=INR","/api/v1/banking/my-dashboard","/api/v1/loans/my/applications","/api/v1/term-deposits/my","/api/v1/credit-cards","/api/v1/accounts/"+funding+"/position"))check(api(path,customer.sessionId())==200,"API failed: "+path);
  check(api("/api/v1/banking/my-dashboard",null)==401,"Unauthenticated request accepted");check(api("/api/v1/accounts/3/position",customer.sessionId())==403,"Unrelated account access accepted");results.add("API | 6 authenticated Oracle endpoints passed; unauthenticated and unrelated-account reads denied");
 }catch(Exception e){throw new RuntimeException(e);}}
 public static void main(String[] args)throws Exception{
  Path previous=Path.of(".runtime/product-oracle-results.txt");if(Files.exists(previous))for(String line:Files.readAllLines(previous)){
   if(line.startsWith("PASS | ")&&!line.equals("PASS | Catalogue completion")){completed.add(line.substring(7));results.add(line);}
   else if(line.startsWith("RECORD | ")||line.startsWith("REPAIRED | ")||line.startsWith("CALENDAR | "))results.add(line);
  }
  check(System.getenv("DB_URL")!=null&&System.getenv("DB_URL").startsWith("jdbc:oracle:"),"Oracle configuration required");
  var app=new SpringApplication(MoneybagsApplication.class,Nio2Source.class);
  try{
   ctx=app.run("--server.port=18101","--server.address=127.0.0.1","--spring.profiles.active=oracle","--moneybags.customer-signup.enabled=true","--moneybags.customer-signup.branch-ref=MUM001","--moneybags.deposit-interest.enabled=true","--spring.main.banner-mode=off","--logging.level.root=WARN");
   jdbc=ctx.getBean(JdbcTemplate.class);db=ctx.getBean(BusinessRepository.class);iam=ctx.getBean(IamRepository.class);
   products=ctx.getBean(ProductService.class);accounts=ctx.getBean(AccountService.class);loans=ctx.getBean(LoanService.class);servicing=ctx.getBean(LoanServicingController.class);ledger=ctx.getBean(LedgerService.class);cards=ctx.getBean(CreditCardService.class);deposits=ctx.getBean(TermDepositController.class);
   tx=new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));nested=new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
   jdbc.update("UPDATE M01_IAM_SESSION SET STATUS='REVOKED',ENDED_AT=SYSTIMESTAMP WHERE DEVICE_REF=? AND STATUS='ACTIVE'",RUN); maker=identity("admin");checker=identity("checker1");check(!maker.userId().equals(checker.userId()),"Independent checker required");
   scenario("Catalogue completion",ProductOracleAcceptance::repairCatalog);
   actor(maker);String name="product.review.20261011";
   if(iam.userByName(name).isEmpty())ctx.getBean(CustomerSignupService.class).registerAsAdmin(maker,new CustomerSignupRequest(name,"Product Review Customer",LocalDate.of(1990,1,1),"Review!"+UUID.randomUUID()));
   customer=identity(name);cif=customer.cifIds().get(0);funding=open(27,41,"funding");results.add("CUSTOMER | username="+name+" | cif="+cif+" | fundingAccount="+funding);save();
   var active=db.rows("SELECT P.PRODUCT_ID,P.PRODUCT_CODE,P.PRODUCT_TYPE,V.PRODUCT_VERSION_ID FROM M03_PM_PRODUCT P JOIN M03_PM_PRODUCT_VERSION V ON V.PRODUCT_ID=P.PRODUCT_ID WHERE P.STATUS='ACTIVE' AND V.VERSION_STATE='ACTIVE' ORDER BY CASE WHEN P.PRODUCT_CODE='PERSONAL_LOAN_WEB_02' THEN 0 WHEN P.PRODUCT_TYPE='LOAN' THEN 1 WHEN P.PRODUCT_TYPE IN ('SAVINGS','CURRENT') THEN 2 ELSE 3 END,P.PRODUCT_CODE");
   for(var p:active)scenario((String)p.get("PRODUCT_CODE"),()->{switch((String)p.get("PRODUCT_TYPE")){case "LOAN"->loan(p);case "SAVINGS","CURRENT"->savings(p);case "DEPOSIT"->deposit(p);default->throw new AssertionError("Unsupported product type");}});
   for(var p:db.rows("SELECT * FROM M11_CC_PRODUCT WHERE STATUS='APPROVED' ORDER BY PRODUCT_CODE"))scenario((String)p.get("PRODUCT_CODE"),()->card(p));
   scenario("Calendar interest and billing",ProductOracleAcceptance::calendarChecks);
   scenario("Transfer allowance after fixed deposit",()->nested.execute(s->{s.setRollbackOnly();actor(customer);
    long target=num(db.one("SELECT ACCOUNT_ID FROM M04_BANK_ACCOUNT WHERE PRIMARY_CIF_ID=? AND PRODUCT_ID=28 AND LIFECYCLE_STATUS='ACTIVE'",cif),"ACCOUNT_ID");
    BigDecimal before=ledger.position(funding).posted();var request=new TransferRequest(funding,target,BigDecimal.ONE,"WEB",UUID.randomUUID().toString(),UUID.randomUUID().toString(),LocalDate.now(ZoneId.of("Asia/Kolkata")));
    check("POSTED".equals(ctx.getBean(BankingService.class).transfer(request).status()),"Fixed-deposit placement blocked a normal transfer");money(ledger.position(funding).posted(),before.subtract(BigDecimal.ONE),"Transfer after fixed deposit debit mismatch");balanced();return null;
   }));
   scenario("Authenticated Oracle APIs",ProductOracleAcceptance::apiChecks);
  }finally{
   if(jdbc!=null)for(String sid:sessions)jdbc.update("UPDATE M01_IAM_SESSION SET STATUS='REVOKED' WHERE SESSION_ID=?",sid);
   SecurityContextHolder.clearContext();save();if(ctx!=null)ctx.close();
  }
  if(results.stream().anyMatch(s->s.startsWith("FAIL")))System.exit(1);
 }
}
