package com.moneybags.integration;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.common.api.BusinessException;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
@RestController @RequestMapping("/api/v1")
public class BankingOperationsController {
 private final BusinessRepository db;private final BankingAccess access;private final CustomerHashService audit;
 public BankingOperationsController(BusinessRepository db,BankingAccess access,CustomerHashService audit){this.db=db;this.access=access;this.audit=audit;}
 public record BeneficiaryInput(@NotBlank @Size(max=100)String displayName,@NotBlank @Pattern(regexp="[A-Za-z0-9._@-]{4,120}")String accountToken,@NotBlank @Pattern(regexp="[A-Z]{4}0[A-Z0-9]{6}")String bankCode){}
 @GetMapping("/beneficiaries") public List<Map<String,Object>> beneficiaries(){return db.rows("SELECT BENEFICIARY_ID,DISPLAY_NAME,BANK_CODE,STATUS,CREATED_AT FROM MBX_BENEFICIARY WHERE OWNER_USER_ID=? ORDER BY CREATED_AT DESC",CurrentActor.get().userId());}
 @PostMapping("/beneficiaries") @Transactional public Map<String,String> beneficiary(@Valid @RequestBody BeneficiaryInput b){
  CurrentActor.require("PAYMENT_CREATE");String id=UUID.randomUUID().toString();
  db.jdbc().update("INSERT INTO MBX_BENEFICIARY(BENEFICIARY_ID,OWNER_USER_ID,DISPLAY_NAME,ACCOUNT_TOKEN,BANK_CODE) VALUES (?,?,?,?,?)",id,CurrentActor.get().userId(),b.displayName(),b.accountToken(),b.bankCode());
  audit.audit("BENEFICIARY_CREATED","BENEFICIARY",id,"AWAITING_VERIFICATION");return Map.of("beneficiaryId",id,"status","PENDING");
 }
 @PostMapping("/beneficiaries/{id}/verify") @Transactional public Map<String,String> verify(@PathVariable String id){
  access.global("BENEFICIARY_VERIFY");var b=db.one("SELECT OWNER_USER_ID,STATUS FROM MBX_BENEFICIARY WHERE BENEFICIARY_ID=? FOR UPDATE",id);
  if(b.get("OWNER_USER_ID").equals(CurrentActor.get().userId()))throw conflict("A different employee must verify the beneficiary");
  if(!"PENDING".equals(b.get("STATUS")))throw conflict("Only pending beneficiaries can be verified");
  db.jdbc().update("UPDATE MBX_BENEFICIARY SET STATUS='ACTIVE',VERIFIED_BY=?,VERIFIED_AT=SYSTIMESTAMP WHERE BENEFICIARY_ID=?",CurrentActor.get().userId(),id);
  audit.audit("BENEFICIARY_VERIFIED","BENEFICIARY",id,"MANUAL_SIMULATION_CHECK");return Map.of("beneficiaryId",id,"status","ACTIVE");
 }
 @PostMapping("/beneficiaries/{id}/not-verified") @Transactional public Map<String,String> notVerified(@PathVariable String id){
  access.global("BENEFICIARY_VERIFY");var b=db.one("SELECT OWNER_USER_ID,STATUS FROM MBX_BENEFICIARY WHERE BENEFICIARY_ID=? FOR UPDATE",id);
  if(b.get("OWNER_USER_ID").equals(CurrentActor.get().userId()))throw conflict("A different employee must review the beneficiary");
  if(!"PENDING".equals(b.get("STATUS")))throw conflict("Only pending beneficiaries can be reviewed");
  db.jdbc().update("UPDATE MBX_BENEFICIARY SET STATUS='DISABLED' WHERE BENEFICIARY_ID=?",id);
  audit.audit("BENEFICIARY_NOT_VERIFIED","BENEFICIARY",id,"MANUAL_SIMULATION_CHECK");return Map.of("beneficiaryId",id,"status","DISABLED");
 }
 @GetMapping("/beneficiaries/pending") public List<Map<String,Object>> pending(){access.global("BENEFICIARY_VERIFY");return db.rows("SELECT BENEFICIARY_ID,DISPLAY_NAME,ACCOUNT_TOKEN,BANK_CODE,STATUS FROM MBX_BENEFICIARY WHERE STATUS='PENDING' ORDER BY CREATED_AT FETCH FIRST 100 ROWS ONLY");}
 @GetMapping("/currencies") public List<Map<String,Object>> currencies(){CurrentActor.require("FX_READ");return db.rows("SELECT * FROM MBX_CURRENCY ORDER BY CURRENCY_CODE");}
 @GetMapping("/fx/rates") public List<Map<String,Object>> rates(){
  CurrentActor.require("FX_READ");
  boolean customer="CUSTOMER".equals(CurrentActor.get().userType());
  String customerFilter=customer
   ?"JOIN MBX_CURRENCY B ON B.CURRENCY_CODE=R.BASE_CURRENCY AND B.ACTIVE_FLAG='Y' JOIN MBX_CURRENCY Q ON Q.CURRENCY_CODE=R.QUOTE_CURRENCY AND Q.ACTIVE_FLAG='Y' WHERE R.STATUS='APPROVED' AND R.OBSERVED_AT<=SYSTIMESTAMP AND R.VALID_UNTIL>SYSTIMESTAMP "
   :"";
  String columns=customer?"R.RATE_ID,R.BASE_CURRENCY,R.QUOTE_CURRENCY,R.MID_RATE,R.SOURCE_CODE,R.OBSERVED_AT,R.VALID_UNTIL,R.STATUS":"R.RATE_ID,R.BASE_CURRENCY,R.QUOTE_CURRENCY,R.BUY_RATE,R.MID_RATE,R.SELL_RATE,R.SOURCE_CODE,R.OBSERVED_AT,R.VALID_UNTIL,R.STATUS";
  var rows=db.rows("SELECT "+columns+" FROM MBX_FX_RATE R "+customerFilter+"ORDER BY R.OBSERVED_AT DESC FETCH FIRST 100 ROWS ONLY");
  rows.forEach(r->r.put("STALE",((OffsetDateTime)r.get("VALID_UNTIL")).isBefore(OffsetDateTime.now())));return rows;
 }
 public record RateInput(@NotBlank @Pattern(regexp="[A-Z]{3}")String baseCurrency,@NotBlank @Pattern(regexp="[A-Z]{3}")String quoteCurrency,@NotNull @DecimalMin("0.00000001")BigDecimal buyRate,@NotNull @DecimalMin("0.00000001")BigDecimal midRate,@NotNull @DecimalMin("0.00000001")BigDecimal sellRate,@NotBlank @Size(max=80)String sourceCode,@NotNull OffsetDateTime observedAt,@NotNull OffsetDateTime validUntil,@NotBlank @Size(max=300)String reason){}
 @PostMapping("/fx/rates") @Transactional public Map<String,String> rate(@Valid @RequestBody RateInput r){
  access.global("FX_MANAGE");
  if(r.baseCurrency().equals(r.quoteCurrency())||r.buyRate().compareTo(r.midRate())>0||r.midRate().compareTo(r.sellRate())>0||!r.validUntil().isAfter(r.observedAt())||r.observedAt().isAfter(OffsetDateTime.now().plusMinutes(1)))throw conflict("Invalid rates or validity window");
  if(db.count("SELECT COUNT(*) FROM MBX_CURRENCY WHERE CURRENCY_CODE IN (?,?) AND ACTIVE_FLAG='Y'",r.baseCurrency(),r.quoteCurrency())!=2)throw conflict("Both currencies must be active before proposing a rate");
  String id=UUID.randomUUID().toString();
  db.jdbc().update("INSERT INTO MBX_FX_RATE(RATE_ID,BASE_CURRENCY,QUOTE_CURRENCY,BUY_RATE,MID_RATE,SELL_RATE,SOURCE_CODE,OBSERVED_AT,VALID_UNTIL,MAKER_ID,REASON_TEXT) VALUES (?,?,?,?,?,?,?,?,?,?,?)",id,r.baseCurrency(),r.quoteCurrency(),r.buyRate(),r.midRate(),r.sellRate(),r.sourceCode(),r.observedAt(),r.validUntil(),CurrentActor.get().userId(),r.reason());
  audit.audit("FX_RATE_PROPOSED","FX_RATE",id,"MAKER_SUBMISSION");return Map.of("rateId",id,"status","PENDING");
 }
 public record Decision(@NotBlank @Pattern(regexp="APPROVED|REJECTED")String decision){}
 @PostMapping("/fx/rates/{id}/decision") @Transactional public Map<String,String> decideRate(@PathVariable String id,@Valid @RequestBody Decision d){
  access.global("FX_APPROVE");var r=db.one("SELECT MAKER_ID,STATUS,VALID_UNTIL FROM MBX_FX_RATE WHERE RATE_ID=? FOR UPDATE",id);
  if(r.get("MAKER_ID").equals(CurrentActor.get().userId())||!"PENDING".equals(r.get("STATUS")))throw conflict("A distinct checker must decide a pending rate");
  if("APPROVED".equals(d.decision())&&((OffsetDateTime)r.get("VALID_UNTIL")).isBefore(OffsetDateTime.now()))throw conflict("Rate has expired");
  db.jdbc().update("UPDATE MBX_FX_RATE SET STATUS=?,CHECKER_ID=? WHERE RATE_ID=?",d.decision(),CurrentActor.get().userId(),id);
  audit.audit("FX_RATE_DECIDED","FX_RATE",id,d.decision());return Map.of("rateId",id,"status",d.decision());
 }
 @GetMapping("/fx/quote") public Map<String,Object> quote(@RequestParam String base,@RequestParam String quote,@RequestParam @DecimalMin("0.01")BigDecimal amount){
  CurrentActor.require("FX_READ");
  if(!base.matches("[A-Z]{3}")||!quote.matches("[A-Z]{3}")||base.equals(quote)||amount==null||amount.compareTo(new BigDecimal("0.01"))<0||amount.scale()>2||amount.precision()>18)throw conflict("Enter two different currency codes and a valid amount");
  var rows=db.rows("SELECT R.RATE_ID,R.BASE_CURRENCY,R.MID_RATE,R.SOURCE_CODE,R.OBSERVED_AT,R.VALID_UNTIL,CASE WHEN R.BASE_CURRENCY=? THEN C.DECIMAL_PLACES ELSE B.DECIMAL_PLACES END AS DECIMAL_PLACES FROM MBX_FX_RATE R JOIN MBX_CURRENCY B ON B.CURRENCY_CODE=R.BASE_CURRENCY AND B.ACTIVE_FLAG='Y' JOIN MBX_CURRENCY C ON C.CURRENCY_CODE=R.QUOTE_CURRENCY AND C.ACTIVE_FLAG='Y' WHERE ((R.BASE_CURRENCY=? AND R.QUOTE_CURRENCY=?) OR (R.BASE_CURRENCY=? AND R.QUOTE_CURRENCY=?)) AND R.STATUS='APPROVED' AND R.OBSERVED_AT<=SYSTIMESTAMP AND R.VALID_UNTIL>SYSTIMESTAMP ORDER BY CASE WHEN R.BASE_CURRENCY=? THEN 0 ELSE 1 END,R.OBSERVED_AT DESC,R.RATE_ID DESC FETCH FIRST 1 ROW ONLY",base,base,quote,quote,base,base);
  if(rows.isEmpty())throw conflict("No current approved rate is available for this pair");
  var r=rows.get(0);
  boolean reciprocal=!base.equals(r.get("BASE_CURRENCY"));
  BigDecimal rate=reciprocal?BigDecimal.ONE.divide((BigDecimal)r.get("MID_RATE"),16,java.math.RoundingMode.HALF_EVEN):(BigDecimal)r.get("MID_RATE");
  int decimalPlaces=((Number)r.get("DECIMAL_PLACES")).intValue();
  return Map.ofEntries(Map.entry("amount",amount.multiply(rate).setScale(decimalPlaces,java.math.RoundingMode.HALF_EVEN)),Map.entry("currency",quote),Map.entry("baseAmount",amount),Map.entry("baseCurrency",base),Map.entry("rate",rate),Map.entry("rateId",r.get("RATE_ID")),Map.entry("source",r.get("SOURCE_CODE")),Map.entry("observedAt",r.get("OBSERVED_AT")),Map.entry("validUntil",r.get("VALID_UNTIL")),Map.entry("reciprocal",reciprocal),Map.entry("indicative",true));
 }
 private BusinessException conflict(String message){return new BusinessException(HttpStatus.CONFLICT,"OPERATION_NOT_ALLOWED",message);}
}
