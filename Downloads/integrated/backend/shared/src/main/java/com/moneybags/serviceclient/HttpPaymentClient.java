package com.moneybags.serviceclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.common.api.BusinessException;
import com.moneybags.integration.CurrentActor;
import com.moneybags.integration.PaymentInitiationPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;

/** Propagates the caller identity and stable intent key; never trusts identity headers. */
public class HttpPaymentClient implements PaymentInitiationPort {
    private final URI base;
    private final ObjectMapper json;
    public HttpPaymentClient(@Value("${moneybags.services.payments-url:http://127.0.0.1:8095}") String url,ObjectMapper json) {
        this.base=ServiceUrls.base(url);this.json=json;
    }
    public long initiatePayment(long sourceAccountId,String beneficiaryId,String railCode,BigDecimal amount,String requestKey) {
        var actor=CurrentActor.get();
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs))throw unavailable();
        String bearer=attrs.getRequest().getHeader("Authorization");
        if(!("Bearer "+actor.sessionId()).equals(bearer))throw new BusinessException(HttpStatus.UNAUTHORIZED,"LOGIN_REQUIRED","Please sign in");
        try{
            var headers=new java.util.LinkedHashMap<String,String>();
            headers.put("Authorization",bearer);headers.put("Content-Type","application/json");headers.put("Idempotency-Key",requestKey);
            for(String name:java.util.List.of("X-Customer-Hash","X-Correlation-ID")){
                String value=attrs.getRequest().getHeader(name);if(value!=null)headers.put(name,value);
            }
            var body=Map.of("sourceAccountId",sourceAccountId,"beneficiaryId",beneficiaryId,"railCode",railCode,"amount",amount.toPlainString(),"requestKey",requestKey);
            var response=ServiceHttp.call(base.resolve("/api/v1/payments/initiate"),"POST",headers,json.writeValueAsString(body),30000);
            if(response.status()>=400&&response.status()<500){
                var error=json.readTree(response.body());
                throw new BusinessException(HttpStatus.valueOf(response.status()),error.path("code").asText("PAYMENT_REJECTED"),error.path("message").asText("Payment request was rejected"));
            }
            if(response.status()!=200)throw unavailable();
            var value=json.readTree(response.body()).path("paymentId");
            if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<1)throw unavailable();
            return value.longValue();
        }catch(BusinessException e){throw e;}catch(Exception e){throw unavailable();}
    }
    private static BusinessException unavailable(){return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENTS_UNAVAILABLE","Payments service is unavailable; retry the same confirmed action");}
}
