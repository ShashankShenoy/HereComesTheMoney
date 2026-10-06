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
 @GetMapping("/beneficiaries/pending") public List<Map<String,Object>> pending(){access.global("BENEFICIARY_VERIFY");return db.rows("SELECT BENEFICIARY_ID,DISPLAY_NAME,BANK_CODE,STATUS FROM MBX_BENEFICIARY WHERE STATUS='PENDING' FETCH FIRST 100 ROWS ONLY");}
 @GetMapping("/currencies") public List<Map<String,Object>> currencies(){CurrentActor.require("FX_READ");return db.rows("SELECT * FROM MBX_CURRENCY ORDER BY CURRENCY_CODE");}
 @GetMapping("/fx/rates") public List<Map<String,Object>> rates(){
  CurrentActor.require("FX_READ");var rows=db.rows("SELECT RATE_ID,BASE_CURRENCY,QUOTE_CURRENCY,BUY_RATE,MID_RATE,SELL_RATE,SOURCE_CODE,OBSERVED_AT,VALID_UNTIL,STATUS FROM MBX_FX_RATE ORDER BY OBSERVED_AT DESC FETCH FIRST 100 ROWS ONLY");
  rows.forEach(r->r.put("STALE",((OffsetDateTime)r.get("VALID_UNTIL")).isBefore(OffsetDateTime.now())));return rows;
 }
 public record RateInput(@NotBlank @Pattern(regexp="[A-Z]{3}")String baseCurrency,@NotBlank @Pattern(regexp="[A-Z]{3}")String quoteCurrency,@NotNull @DecimalMin("0.00000001")BigDecimal buyRate,@NotNull @DecimalMin("0.00000001")BigDecimal midRate,@NotNull @DecimalMin("0.00000001")BigDecimal sellRate,@NotBlank @Size(max=80)String sourceCode,@NotNull OffsetDateTime observedAt,@NotNull OffsetDateTime validUntil,@NotBlank @Size(max=300)String reason){}
 @PostMapping("/fx/rates") @Transactional public Map<String,String> rate(@Valid @RequestBody RateInput r){
  access.global("FX_MANAGE");
  if(r.baseCurrency().equals(r.quoteCurrency())||r.buyRate().compareTo(r.midRate())>0||r.midRate().compareTo(r.sellRate())>0||!r.validUntil().isAfter(r.observedAt())||r.observedAt().isAfter(OffsetDateTime.now().plusMinutes(1)))throw conflict("Invalid rates or validity window");
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
  CurrentActor.require("FX_READ");var r=db.one("SELECT MID_RATE,SOURCE_CODE,OBSERVED_AT,VALID_UNTIL FROM MBX_FX_RATE WHERE BASE_CURRENCY=? AND QUOTE_CURRENCY=? AND STATUS='APPROVED' ORDER BY OBSERVED_AT DESC FETCH FIRST 1 ROW ONLY",base,quote);
  if(((OffsetDateTime)r.get("VALID_UNTIL")).isBefore(OffsetDateTime.now()))throw conflict("Cached rate is stale; obtain a new approved rate");
  return Map.of("amount",amount.multiply((BigDecimal)r.get("MID_RATE")).setScale(2,java.math.RoundingMode.HALF_EVEN),"currency",quote,"source",r.get("SOURCE_CODE"),"observedAt",r.get("OBSERVED_AT"),"indicative",true);
 }
 private BusinessException conflict(String message){return new BusinessException(HttpStatus.CONFLICT,"OPERATION_NOT_ALLOWED",message);}
}
