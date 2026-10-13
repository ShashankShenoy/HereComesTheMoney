package com.moneybags.iam.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.mock.web.*;
import jakarta.servlet.FilterChain;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BearerSessionFailureTest {
    @ParameterizedTest @ValueSource(strings={"query", "transaction"})
    void databaseFailureReturns503WithoutDispatchingOrClaimingInvalidCredentials(String failure) throws Exception {
        var sessions=mock(SessionAuthenticator.class);
        RuntimeException error=failure.equals("query") ? new DataAccessResourceFailureException("database offline") : new CannotCreateTransactionException("database offline");
        when(sessions.authenticate("session")).thenThrow(error);
        var filter=new BearerSessionFilter(sessions,new JsonSecurityErrors(tools.jackson.databind.json.JsonMapper.builder().build()));
        var request=new MockHttpServletRequest("GET","/api/v1/auth/me");request.addHeader("Authorization","Bearer session");
        var response=new MockHttpServletResponse();var chain=mock(FilterChain.class);
        filter.doFilter(request,response,chain);
        assertEquals(503,response.getStatus());assertTrue(response.getContentAsString().contains("DATABASE_UNAVAILABLE"));
        assertNull(response.getHeader("WWW-Authenticate"));verifyNoInteractions(chain);
    }
}
