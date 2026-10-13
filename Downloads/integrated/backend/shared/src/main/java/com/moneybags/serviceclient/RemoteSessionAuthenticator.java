package com.moneybags.serviceclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.common.api.BusinessException;
import com.moneybags.iam.security.SessionAuthenticator;
import com.moneybags.iam.security.UserPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import java.net.URI;

/** Revalidates the original bearer token; never trusts gateway-supplied identity headers. */
public class RemoteSessionAuthenticator implements SessionAuthenticator {
    private final URI endpoint;
    private final ObjectMapper json;
    public RemoteSessionAuthenticator(@Value("${moneybags.services.identity-url:http://127.0.0.1:8092}") String url, ObjectMapper json) {
        this.endpoint = ServiceUrls.base(url).resolve("/api/v1/auth/me");
        this.json = json;
    }
    @Override public UserPrincipal authenticate(String token) {
        try {
            var response = ServiceHttp.call(endpoint, "GET", java.util.Map.of("Authorization", "Bearer " + token), null, 5000);
            if (response.status() == 401 || response.status() == 403)
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_INVALID", "Session is expired or revoked; sign in again");
            if (response.status() != 200) throw unavailable();
            var principal = json.treeToValue(json.readTree(response.body()).required("data"), UserPrincipal.class);
            if (principal == null || principal.userId() == null || !token.equals(principal.sessionId())) throw unavailable();
            return principal;
        } catch (BusinessException e) { throw e; }
          catch (Exception e) { throw unavailable(); }
    }
    private static BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE", "Identity service is unavailable");
    }
}
