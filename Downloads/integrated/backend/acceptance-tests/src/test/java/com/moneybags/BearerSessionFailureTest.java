package com.moneybags;

import com.moneybags.iam.security.BearerSessionFilter;
import com.moneybags.iam.security.JsonSecurityErrors;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.TransactionSystemException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class BearerSessionFailureTest {
 @Test void brokenDatabaseRollbackReturnsJsonWithoutAuthenticating()throws Exception {
  var request=new MockHttpServletRequest("GET","/api/v1/auth/me");
  request.addHeader("Authorization","Bearer "+java.util.UUID.randomUUID());
  var response=new MockHttpServletResponse();
  var filter=new BearerSessionFilter(token->{throw new TransactionSystemException("rollback failed");},new JsonSecurityErrors(JsonMapper.builder().build()));
  filter.doFilter(request,response,(incoming,outgoing)->fail("Database failure must not authenticate or invoke the endpoint"));
  assertEquals(503,response.getStatus());assertEquals("application/json",response.getContentType());
  assertEquals("DATABASE_UNAVAILABLE",JsonMapper.builder().build().readTree(response.getContentAsString()).path("code").asString());
 }
}
