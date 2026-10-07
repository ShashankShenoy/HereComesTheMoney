package com.moneybags.integration;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.txn.api.Contracts.TransactionView;
import com.moneybags.payments.api.Contracts.Payment;
import java.util.*;
/** Applies to receipts as well as reads, so an alternate HTTP method cannot disclose amounts. */
@RestControllerAdvice
public class TransactionPrivacyAdvice implements ResponseBodyAdvice<Object> {
 private final CustomerHashService hashes;private final BusinessRepository db;private final BankingAccess access;private final com.fasterxml.jackson.databind.ObjectMapper json;
 public TransactionPrivacyAdvice(CustomerHashService hashes,BusinessRepository db,BankingAccess access,com.fasterxml.jackson.databind.ObjectMapper json){this.hashes=hashes;this.db=db;this.access=access;this.json=json;}
 public boolean supports(MethodParameter p,Class<? extends HttpMessageConverter<?>> c){
  return p.getContainingClass().getPackageName().startsWith("com.moneybags.account")||p.getContainingClass().getPackageName().startsWith("com.moneybags.integration")||p.getContainingClass().getPackageName().startsWith("com.moneybags.txn")||p.getContainingClass().getPackageName().startsWith("com.moneybags.payments");
 }
 public Object beforeBodyWrite(Object body,MethodParameter p,MediaType type,Class<? extends HttpMessageConverter<?>> c,ServerHttpRequest request,ServerHttpResponse response){
  response.getHeaders().setCacheControl("no-store");
  String hash=request.getHeaders().getFirst("X-Customer-Hash");
  String path=request.getURI().getPath();
  // BankingController has already scoped and redacted this account list. Rechecking
  // each row here would repeat the IAM and transaction queries up to 100 times.
  if(path.equals("/api/v1/banking/transactions")&&p.getContainingClass()==BankingController.class)return body;
  // This endpoint checks account proof or staff GL authority before constructing its scoped response.
  if(path.matches("/api/v1/banking/transactions/\\d+/details"))return body;
  if(!(body instanceof java.util.Map<?,?> error&&error.containsKey("code"))&&path.matches("/api/v1/payments/\\d+/(history|rail-evidence)")){
   long id=Long.parseLong(path.split("/")[4]);
   var row=db.one("SELECT SOURCE_ACCOUNT_ID,DESTINATION_ACCOUNT_ID AS TARGET_ACCOUNT_ID FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",id);
   if(!visible(row,hash))return Map.of("paymentId",id);
  }
  if(path.matches("/api/v1/journals/\\d+/control")){
   long id=Long.parseLong(path.split("/")[4]);
   var lines=db.rows("SELECT BANK_ACCOUNT_ID AS SOURCE_ACCOUNT_ID FROM M05_GL_POSTING WHERE JOURNAL_ID=? AND BANK_ACCOUNT_ID IS NOT NULL",id);
   if(lines.isEmpty()||lines.stream().noneMatch(r->visible(r,hash)))return Map.of("journalId",id);
  }
  // A tree avoids Jackson retaining the controller's List<TransactionView> element serializer
  // after redaction substitutes identifier-only maps.
  if(body instanceof List<?> list)return json.valueToTree(list.stream().map(v->mask(v,hash)).toList());
  if(body instanceof com.moneybags.account.api.Models.Page<?>)return json.valueToTree(mask(body,hash));
  return mask(body,hash);
 }
 private Object mask(Object body,String hash){
  if(body instanceof Map<?,?> m){
   Object txn=m.containsKey("TXN_ID")?m.get("TXN_ID"):m.get("transactionId");
   Object payment=m.containsKey("PAYMENT_ID")?m.get("PAYMENT_ID"):m.get("paymentId");
   if(txn instanceof Number n){var r=db.one("SELECT SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID FROM M05_TXN_TRANSACTION_LOG WHERE TXN_ID=?",n.longValue());if(!visibleTransaction(r,hash,n.longValue()))return Map.of("transactionId",n.longValue());}
   else if(payment instanceof Number n){var r=db.one("SELECT SOURCE_ACCOUNT_ID,DESTINATION_ACCOUNT_ID AS TARGET_ACCOUNT_ID FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",n.longValue());if(!visible(r,hash))return Map.of("paymentId",n.longValue());}
  }
  if(body instanceof com.moneybags.account.api.Models.Page<?> page)return new com.moneybags.account.api.Models.Page<>(page.items().stream().map(v->mask(v,hash)).toList(),page.limit(),page.offset());
  if(body instanceof com.moneybags.account.api.Models.AccountView a){
   if(hashes.permits(a.id(),hash)){
    try{access.account("ACCOUNT_READ",a.id());return body;}
    catch(com.moneybags.common.api.BusinessException denied){/* keep financial fields masked */}
   }
   var fields=new LinkedHashMap<String,Object>();
   for(var field:a.getClass().getRecordComponents())try{fields.put(field.getName(),field.getAccessor().invoke(a));}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
   for(String field:List.of("ledgerBalance","blockedBalance","lienBalance","overdraftLimit","availableBalance"))fields.remove(field);
   return fields;
  }
  if(body instanceof TransactionView t){
   var r=db.one("SELECT SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID FROM M05_TXN_TRANSACTION_LOG WHERE TXN_ID=?",t.transactionId());
   return visibleTransaction(r,hash,t.transactionId())?body:Map.of("transactionId",t.transactionId());
  }
  if(body instanceof Payment p){
   var r=db.one("SELECT SOURCE_ACCOUNT_ID,DESTINATION_ACCOUNT_ID AS TARGET_ACCOUNT_ID FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=?",p.paymentId());
   return visible(r,hash)?body:Map.of("paymentId",p.paymentId());
  }
  return body;
 }
 private boolean visible(Map<String,Object> row,String hash){
  for(String key:List.of("SOURCE_ACCOUNT_ID","TARGET_ACCOUNT_ID")){
   if(!(row.get(key) instanceof Number id))continue;
   if(hashes.permits(id.longValue(),hash)){
    try{access.account("TXN_READ",id.longValue());hashes.audit("FINANCIAL_DETAILS_VIEWED","ACCOUNT",id.toString(),hashes.proofLabel());return true;}
    catch(com.moneybags.common.api.BusinessException denied){/* preserve identifier-only receipt */}
   }
  }
  return false;
 }
 private boolean visibleTransaction(Map<String,Object> row,String hash,long id){
  if(access.bankAdminTransactionRead())for(String key:List.of("SOURCE_ACCOUNT_ID","TARGET_ACCOUNT_ID")){
   if(row.get(key) instanceof Number accountId)try{
    access.account("TXN_READ",accountId.longValue());
    hashes.audit("FINANCIAL_DETAILS_VIEWED","TRANSACTION",Long.toString(id),"BANK_ADMIN");
    return true;
   }catch(com.moneybags.common.api.BusinessException denied){/* try the other account */}
  }
  if(visible(row,hash))return true;
  if(row.get("SOURCE_ACCOUNT_ID")!=null||row.get("TARGET_ACCOUNT_ID")!=null)return false;
  if(db.count("SELECT COUNT(*) FROM M05_TXN_TRANSACTION_LOG WHERE TXN_ID=? AND LOAN_FACILITY_ID IS NOT NULL",id)>0)return false;
  if(db.count("SELECT COUNT(*) FROM M05_GL_JOURNAL J JOIN M05_GL_POSTING P ON P.JOURNAL_ID=J.JOURNAL_ID WHERE J.TXN_ID=? AND (P.BANK_ACCOUNT_ID IS NOT NULL OR P.LOAN_FACILITY_ID IS NOT NULL)",id)>0)return false;
  try{access.global("GL_RECONCILE");hashes.audit("INTERNAL_TRANSACTION_VIEWED","TRANSACTION",Long.toString(id),"GL_RECONCILE");return true;}
  catch(com.moneybags.common.api.BusinessException denied){return false;}
 }
}
