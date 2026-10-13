package com.moneybags.integration;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.common.api.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** Configured HTTPS adapter accepts an explicit, documented rate contract and proposes rates for human approval. */
@RestController @RequestMapping("/api/v1/fx/provider")
public class FxProviderController {
 private final String url,token;private final BankingOperationsController rates;private final BankingAccess access;private final CustomerHashService audit;private final ObjectMapper json;
 private HttpClient client;private int failures;private Instant openUntil=Instant.MIN;
 public FxProviderController(@Value("${moneybags.fx.provider-url:}")String url,@Value("${moneybags.fx.api-token:}")String token,BankingOperationsController rates,BankingAccess access,CustomerHashService audit,ObjectMapper json){this.url=url;this.token=token;this.rates=rates;this.access=access;this.audit=audit;this.json=json;}
 public record Pair(@NotBlank @Pattern(regexp="[A-Z]{3}")String base,@NotBlank @Pattern(regexp="[A-Z]{3}")String quote){}
 @GetMapping("/status") public synchronized Map<String,Object> status(){CurrentActor.require("FX_READ");return Map.of("configured",!url.isBlank(),"circuitOpen",Instant.now().isBefore(openUntil),"consecutiveFailures",failures,"cachedRatesPath","/api/v1/fx/rates");}
 @PostMapping("/refresh") public synchronized Map<String,String> refresh(@Valid @RequestBody Pair p){
  access.global("FX_MANAGE");
  if(url.isBlank())throw fail("PROVIDER_NOT_CONFIGURED","Set FX_PROVIDER_URL to the trusted HTTPS provider endpoint");
  if(Instant.now().isBefore(openUntil))throw fail("CIRCUIT_OPEN","Provider temporarily unavailable; inspect cached rates and their expiry");
  URI uri=URI.create(url+ (url.contains("?")?"&":"?")+"base="+p.base()+"&quote="+p.quote());
  if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw fail("PROVIDER_CONFIG_INVALID","A trusted HTTPS endpoint without embedded credentials is required");
  for(int attempt=0;attempt<2;attempt++)try{
   if(client==null)client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
   var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).header("Accept","application/json").GET();if(!token.isBlank())builder.header("Authorization","Bearer "+token);
   var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());byte[] body;try(var stream=response.body()){body=stream.readNBytes(65537);}
   if(response.statusCode()!=200||body.length>65536)throw new IllegalStateException("Provider response rejected");
   var data=json.readTree(body);
   if(!p.base().equals(data.path("base").asText())||!p.quote().equals(data.path("quote").asText()))throw new IllegalStateException("Provider currency mismatch");
   var rate=new BankingOperationsController.RateInput(p.base(),p.quote(),new java.math.BigDecimal(data.path("buy").asText()),new java.math.BigDecimal(data.path("mid").asText()),new java.math.BigDecimal(data.path("sell").asText()),"CONFIGURED_PROVIDER",OffsetDateTime.parse(data.path("observedAt").asText()),OffsetDateTime.parse(data.path("validUntil").asText()),"Provider refresh; independent approval required");
   if(rate.buyRate().signum()<=0||rate.midRate().signum()<=0||rate.sellRate().signum()<=0||rate.validUntil().isBefore(OffsetDateTime.now()))throw new IllegalStateException("Invalid provider rate");
   var result=rates.rate(rate);failures=0;openUntil=Instant.MIN;audit.audit("FX_PROVIDER_RESPONSE","FX_RATE",result.get("rateId"),"VALIDATED_PENDING_APPROVAL");return result;
  }catch(Exception error){
   audit.audit("FX_PROVIDER_FAILURE","FX_PROVIDER","CONFIGURED_PROVIDER",error.getClass().getSimpleName());
   if(error instanceof InterruptedException){Thread.currentThread().interrupt();break;}
  }
  failures++;if(failures>=3)openUntil=Instant.now().plusSeconds(60);throw fail("PROVIDER_UNAVAILABLE","Provider failed validation or timed out; cached approved rates remain available until expiry");
 }
 private BusinessException fail(String code,String message){return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,code,message);}
}
