package com.moneybags.serviceclient;

import com.fasterxml.jackson.databind.*;
import com.moneybags.common.api.BusinessException;
import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.statements.ApiException;
import com.moneybags.statements.ApiModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class ServiceClientsTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    final UserPrincipal user = new UserPrincipal("user", "customer", "CUSTOMER", "session", 1L, List.of("RETAIL_CUSTOMER"), List.of("STATEMENT_READ"), List.of("cif"));
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    void paymentRequest(){
        request("Bearer session");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user,null,List.of()));
    }
    void request(String bearer) {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", bearer); request.addHeader("X-Customer-Hash", "access-key"); request.addHeader("X-Correlation-ID", "trace");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @Test void identityRevalidatesOriginalBearerAndMapsPrincipal() throws Exception {
        try (var server = new FakeServer(req -> new Reply(200, "{\"data\":" + serialize(user) + "}"))) {
            var result = new RemoteSessionAuthenticator(server.url(), json).authenticate("session");
            assertEquals(user, result); var req = server.requests.poll(2, TimeUnit.SECONDS);
            assertEquals("GET /api/v1/auth/me HTTP/1.1", req.line); assertEquals("Bearer session", req.headers.get("authorization"));
        }
    }
    @ParameterizedTest @ValueSource(ints={401,403,429,500,503}) void identityNeverAuthenticatesRejectedResponses(int status) throws Exception {
        try (var server = new FakeServer(req -> new Reply(status, "{\"message\":\"rejected\"}"))) {
            var error = assertThrows(BusinessException.class, () -> new RemoteSessionAuthenticator(server.url(), json).authenticate("session"));
            assertEquals(status == 401 || status == 403 ? HttpStatus.UNAUTHORIZED : HttpStatus.SERVICE_UNAVAILABLE, error.status());
        }
    }
    @ParameterizedTest @ValueSource(strings={"not-json", "{}", "{\"data\":null}", "{\"data\":{\"userId\":\"other\",\"sessionId\":\"wrong\"}}"})
    void identityFailsClosedOnInvalidPrincipal(String body) throws Exception {
        try (var server = new FakeServer(req -> new Reply(200, body))) {
            var error = assertThrows(BusinessException.class, () -> new RemoteSessionAuthenticator(server.url(), json).authenticate("session"));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status()); assertEquals("IDENTITY_UNAVAILABLE", error.code());
        }
    }
    @Test void identityConnectionFailureIsUnavailable() throws Exception {
        String url; try (var server = new FakeServer(req -> new Reply(200,"{}"))) { url=server.url(); }
        var error=assertThrows(BusinessException.class, () -> new RemoteSessionAuthenticator(url,json).authenticate("session"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.status());
    }
    @Test void reportingForwardsOriginalSecurityHeadersBodyAndIdempotency() throws Exception {
        request("Bearer session");
        var view = new RequestView("request",1L,"AUTHORIZED","AD_HOC",LocalDate.of(2026,1,1),LocalDate.of(2026,1,2),"CSV",null,null);
        try (var server = new FakeServer(req -> new Reply(201,serialize(view)))) {
            var body = new CreateRequest(1L,null,"AD_HOC",view.periodStart(),view.periodEnd(),"en-IN","CSV","WEB",null,null,null,null,null);
            var response = new HttpStatementClient(server.url(),json).create(user,"stable-key",body);
            assertEquals(view,response.getBody()); var req=server.requests.poll(2,TimeUnit.SECONDS);
            assertEquals("POST /api/v1/reporting/statement-requests HTTP/1.1",req.line);
            assertEquals("Bearer session",req.headers.get("authorization")); assertEquals("access-key",req.headers.get("x-customer-hash"));
            assertEquals("trace",req.headers.get("x-correlation-id")); assertEquals("stable-key",req.headers.get("idempotency-key"));
            assertEquals(json.readTree(serialize(body)),json.readTree(req.body));
        }
    }
    @Test void reportingRefusesMissingRequestOrDifferentCaller() throws Exception {
        try (var server = new FakeServer(req -> new Reply(200,"{}"))) {
            var client=new HttpStatementClient(server.url(),json);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ApiException.class,()->client.request(user,"id")).status);
            request("Bearer different-session");
            assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ApiException.class,()->client.request(user,"id")).status);
            assertTrue(server.requests.isEmpty());
        }
    }
    @Test void reportingPreservesAccessRejection() throws Exception {
        request("Bearer session");
        try (var server = new FakeServer(req -> new Reply(403,"{\"code\":\"ACCESS_DENIED\",\"message\":\"Denied\"}"))) {
            var error=assertThrows(ApiException.class,()->new HttpStatementClient(server.url(),json).request(user,"id"));
            assertEquals(HttpStatus.FORBIDDEN,error.status); assertEquals("ACCESS_DENIED",error.code);
        }
    }
    @ParameterizedTest @ValueSource(strings={"500", "malformed", "redirect"}) void reportingFailsSafelyOnTransportOrInvalidPayload(String mode) throws Exception {
        request("Bearer session");
        try (var server = new FakeServer(req -> new Reply(mode.equals("500") ? 500 : mode.equals("redirect") ? 302 : 200,"not-json"))) {
            var error=assertThrows(ApiException.class,()->new HttpStatementClient(server.url(),json).request(user,"id"));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.status); assertEquals("REPORTING_UNAVAILABLE",error.code);
        }
    }
    @Test void transportReadDeadlineIsBounded() throws Exception {
        try (var server=new FakeServer(req->{try{Thread.sleep(300);}catch(InterruptedException ignored){}return new Reply(200,"{}");})) {
            long start=System.nanoTime();
            assertThrows(SocketTimeoutException.class,()->ServiceHttp.call(URI.create(server.url()),"GET",Map.of(),null,75));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500);
        }
    }
    @Test void paymentInitiationForwardsIdentityExactAmountAndStableKey() throws Exception {
        paymentRequest();
        try(var server=new FakeServer(req -> new Reply(200,"{\"paymentId\":91}"))){
            assertEquals(91,new HttpPaymentClient(server.url(),json).initiatePayment(1,"payee","UPI",new java.math.BigDecimal("0.01"),"intent-key"));
            var sent=server.requests.poll(2,TimeUnit.SECONDS);
            assertEquals("POST /api/v1/payments/initiate HTTP/1.1",sent.line);
            assertEquals("Bearer session",sent.headers.get("authorization"));
            assertEquals("intent-key",sent.headers.get("idempotency-key"));
            assertEquals("access-key",sent.headers.get("x-customer-hash"));
            assertEquals("trace",sent.headers.get("x-correlation-id"));
            var body=json.readTree(sent.body);assertEquals("0.01",body.path("amount").asText());assertEquals("intent-key",body.path("requestKey").asText());
        }
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,409,429}) void paymentRejectionsKeepTheirStatusAndAvailableCode(int status) throws Exception {
        paymentRequest();
        try(var server=new FakeServer(req -> new Reply(status,"{\"code\":\"PAYMENT_RULE\",\"message\":\"Rejected\"}"))){
            var error=assertThrows(BusinessException.class,()->new HttpPaymentClient(server.url(),json).initiatePayment(1,"payee","UPI",java.math.BigDecimal.ONE,"key"));
            assertEquals(HttpStatus.valueOf(status),error.status());
            // HttpURLConnection's streamed POST can omit a 401 challenge body.
            assertEquals(status==401 ? "PAYMENT_REJECTED" : "PAYMENT_RULE",error.code());
        }
    }
    @ParameterizedTest @ValueSource(strings={"not-json","{}","{\"paymentId\":0}","{\"paymentId\":\"91\"}","{\"paymentId\":1.2}"})
    void paymentMalformedSuccessFailsClosed(String response) throws Exception {
        paymentRequest();
        try(var server=new FakeServer(req -> new Reply(200,response))){
            var error=assertThrows(BusinessException.class,()->new HttpPaymentClient(server.url(),json).initiatePayment(1,"payee","UPI",java.math.BigDecimal.ONE,"key"));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.status());
        }
    }
    @Test void paymentRetryAfterLostReplyKeepsTheOriginalIntentKey() throws Exception {
        paymentRequest();var processed=new java.util.HashSet<String>();var calls=new java.util.concurrent.atomic.AtomicInteger();
        try(var server=new FakeServer(req -> {processed.add(req.headers.get("idempotency-key"));return calls.incrementAndGet()==1 ? null : new Reply(200,"{\"paymentId\":91}");})){
            var client=new HttpPaymentClient(server.url(),json);
            try{assertEquals(91,client.initiatePayment(1,"payee","UPI",java.math.BigDecimal.ONE,"original-intent"));}
            catch(BusinessException error){assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.status());}
            assertEquals(91,client.initiatePayment(1,"payee","UPI",java.math.BigDecimal.ONE,"original-intent"));
            assertEquals(Set.of("original-intent"),processed);
            assertTrue(calls.get()>=2);
        }
    }
    @Test void paymentClientNeverAcceptsABearerForAnotherPrincipal() throws Exception {
        paymentRequest();request("Bearer another-session");
        var error=assertThrows(BusinessException.class,()->new HttpPaymentClient("http://127.0.0.1:1",json).initiatePayment(1,"payee","UPI",java.math.BigDecimal.ONE,"key"));
        assertEquals(HttpStatus.UNAUTHORIZED,error.status());
    }
    @Test void paymentConnectionFailureHasAnExplicitUnavailableCode() throws Exception {
        paymentRequest();String url;try(var server=new FakeServer(req -> new Reply(200,"{}"))){url=server.url();}
        var error=assertThrows(BusinessException.class,()->new HttpPaymentClient(url,json).initiatePayment(1,"payee","UPI",java.math.BigDecimal.ONE,"key"));
        assertEquals("PAYMENTS_UNAVAILABLE",error.code());
    }
    @ParameterizedTest @ValueSource(strings={"ftp://localhost", "http://user:secret@localhost", "http://localhost/path", "http://localhost?query=1", "http://localhost/#fragment"})
    void invalidServiceOriginsFailAtConstruction(String url) {
        assertThrows(IllegalArgumentException.class,()->new RemoteSessionAuthenticator(url,json));
        assertThrows(IllegalArgumentException.class,()->new HttpStatementClient(url,json));
        assertThrows(IllegalArgumentException.class,()->new HttpPaymentClient(url,json));
    }
    String serialize(Object value) { try {return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);} }
    record Incoming(String line,Map<String,String> headers,String body) {}
    record Reply(int status,String body) {}
    // Blocking sockets avoid Windows Unix-domain selector dependencies in the test fixture too.
    static final class FakeServer implements AutoCloseable {
        final ServerSocket socket; final Thread thread; final BlockingQueue<Incoming> requests=new LinkedBlockingQueue<>();
        FakeServer(Function<Incoming,Reply> handler) throws IOException {
            socket=new ServerSocket(0,20,InetAddress.getLoopbackAddress());
            thread=new Thread(()->{
                while(!socket.isClosed()) try(var client=socket.accept()) {
                    client.setSoTimeout(2000); var input=client.getInputStream(); var raw=new ByteArrayOutputStream(); int next;
                    while((next=input.read())!=-1){raw.write(next);byte[] b=raw.toByteArray();int n=b.length;if(n>=4&&b[n-4]==13&&b[n-3]==10&&b[n-2]==13&&b[n-1]==10)break;}
                    String[] lines=raw.toString(StandardCharsets.ISO_8859_1).split("\r\n");var headers=new HashMap<String,String>();
                    for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon>0)headers.put(lines[i].substring(0,colon).toLowerCase(Locale.ROOT),lines[i].substring(colon+1).trim());}
                    int length=Integer.parseInt(headers.getOrDefault("content-length","0"));
                    var req=new Incoming(lines[0],headers,new String(input.readNBytes(length),StandardCharsets.UTF_8)); requests.add(req);
                    Reply reply=handler.apply(req);if(reply==null)continue;byte[] bytes=reply.body.getBytes(StandardCharsets.UTF_8);
                    client.getOutputStream().write(("HTTP/1.1 "+reply.status+" Result\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    client.getOutputStream().write(bytes);
                }catch(IOException ignored){} });
            thread.setDaemon(true);thread.start();
        }
        String url(){return "http://127.0.0.1:"+socket.getLocalPort();}
        @Override public void close() throws Exception {socket.close();thread.join(1000);}
    }
}
