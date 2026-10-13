package com.moneybags.serviceclient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.statements.ApiException;
import com.moneybags.statements.ApiModels.*;
import com.moneybags.statements.StatementPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class HttpStatementClient implements StatementPort {
    private final URI base;
    private final ObjectMapper json;
    public HttpStatementClient(@Value("${moneybags.services.reporting-url:http://127.0.0.1:8093}") String url, ObjectMapper json) {
        this.base = ServiceUrls.base(url); this.json = json;
    }
    private static String segment(String id) { return URLEncoder.encode(id, StandardCharsets.UTF_8); }
    private <T> T call(UserPrincipal user, String method, String path, Object body, String key, TypeReference<T> type) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) throw unavailable();
        String bearer = attrs.getRequest().getHeader("Authorization");
        if (bearer == null || !bearer.equals("Bearer " + user.sessionId()))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "Please sign in");
        try {
            var headers = new java.util.LinkedHashMap<String,String>();
            headers.put("Authorization", bearer);
            for (String name : List.of("X-Customer-Hash", "X-Correlation-ID")) {
                String value = attrs.getRequest().getHeader(name);
                if (value != null) headers.put(name, value);
            }
            if (key != null) headers.put("Idempotency-Key", key);
            if (body != null) headers.put("Content-Type", "application/json");
            var response = ServiceHttp.call(base.resolve("/api/v1/reporting" + path), method, headers, body == null ? null : json.writeValueAsString(body), 30000);
            if (response.status() >= 400 && response.status() < 500) {
                var error = json.readTree(response.body());
                throw new ApiException(HttpStatus.valueOf(response.status()), error.path("code").asText("REPORTING_REJECTED"), error.path("message").asText("Reporting request was rejected"));
            }
            if (response.status() < 200 || response.status() >= 300) throw unavailable();
            return json.readValue(response.body(), type);
        } catch (ApiException e) { throw e; }
          catch (Exception e) { throw unavailable(); }
    }
    private static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "REPORTING_UNAVAILABLE", "Reporting service is unavailable"); }
    public List<StatementView> statements(UserPrincipal u,long account,int limit) { return call(u,"GET","/statements?accountId="+account+"&limit="+limit,null,null,new TypeReference<>(){}); }
    public List<RequestView> requests(UserPrincipal u,long account,int limit) { return call(u,"GET","/statement-requests?accountId="+account+"&limit="+limit,null,null,new TypeReference<>(){}); }
    public RequestView request(UserPrincipal u,String id) { return call(u,"GET","/statement-requests/"+segment(id),null,null,new TypeReference<>(){}); }
    public StatementView statement(UserPrincipal u,String id) { return call(u,"GET","/statements/"+segment(id),null,null,new TypeReference<>(){}); }
    public ResponseEntity<RequestView> create(UserPrincipal u,String key,CreateRequest body) { return ResponseEntity.status(201).body(call(u,"POST","/statement-requests",body,key,new TypeReference<RequestView>(){})); }
    public RequestView issue(UserPrincipal u,String id) { return call(u,"POST","/statement-requests/"+segment(id)+"/process",null,null,new TypeReference<>(){}); }
}
