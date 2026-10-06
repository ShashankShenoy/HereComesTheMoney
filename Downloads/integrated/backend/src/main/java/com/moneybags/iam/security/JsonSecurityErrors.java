package com.moneybags.iam.security;
import com.moneybags.common.api.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.io.IOException;
import java.util.Map;
@Component
public class JsonSecurityErrors {
    private final JsonMapper json;
    public JsonSecurityErrors(JsonMapper json) { this.json=json; }
    public void write(HttpServletRequest request,HttpServletResponse response,int status,String code,String message) throws IOException {
        response.setStatus(status);response.setContentType("application/json");
        if(status==401)response.setHeader("WWW-Authenticate","Bearer");
        json.writeValue(response.getOutputStream(),new ApiError(status,code,message,request.getRequestURI(),Correlation.current(),Instant.now(),Map.of()));
    }
}
