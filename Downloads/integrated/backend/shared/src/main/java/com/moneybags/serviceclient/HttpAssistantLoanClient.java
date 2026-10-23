package com.moneybags.serviceclient;

import com.fasterxml.jackson.databind.*;
import com.moneybags.integration.*;
import com.moneybags.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.*;
import java.net.URI;
import java.util.*;

/** Uses the same authenticated loan endpoints as the banking workspace. */
public class HttpAssistantLoanClient implements AssistantLoanPort {
    private final URI base; private final ObjectMapper json;
    public HttpAssistantLoanClient(@Value("${moneybags.services.loans-url:http://127.0.0.1:8096}")String url,ObjectMapper json){this.base=ServiceUrls.base(url);this.json=json;}
    public JsonNode read(Read operation,long id){
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs))throw unavailable();
        String bearer=attrs.getRequest().getHeader("Authorization");
        if(!("Bearer "+CurrentActor.get().sessionId()).equals(bearer))throw new BusinessException(HttpStatus.UNAUTHORIZED,"LOGIN_REQUIRED","Please sign in");
        String path=switch(operation){
            case APPLICATIONS->"/my/applications";case APPLICATION->"/applications/"+id;
            case OFFERS->"/applications/"+id+"/offers";case FACILITY->"/facilities/"+id;
            case SCHEDULE->"/facilities/"+id+"/schedule";case PREPAYMENT_QUOTE->"/facilities/"+id+"/prepayment-quote";};
        try{
            var headers=new LinkedHashMap<String,String>();headers.put("Authorization",bearer);
            for(String name:List.of("X-Customer-Hash","X-Correlation-ID")){String value=attrs.getRequest().getHeader(name);if(value!=null)headers.put(name,value);}
            var response=ServiceHttp.call(base.resolve("/api/v1/loans"+path),"GET",headers,null,30000);
            if(response.status()>=400&&response.status()<500){var error=json.readTree(response.body());throw new BusinessException(HttpStatus.valueOf(response.status()),error.path("code").asText("LOAN_REJECTED"),error.path("message").asText("Loan request was rejected"));}
            if(response.status()!=200)throw unavailable();
            var value=json.readTree(response.body());return value.has("data")?value.path("data"):value;
        }catch(BusinessException e){throw e;}catch(Exception e){throw unavailable();}
    }
    private static BusinessException unavailable(){return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"LOANS_UNAVAILABLE","Loan service is unavailable. Try again later.");}
}
