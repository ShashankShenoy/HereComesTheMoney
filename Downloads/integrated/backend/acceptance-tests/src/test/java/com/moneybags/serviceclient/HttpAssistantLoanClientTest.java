package com.moneybags.serviceclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.common.api.BusinessException;
import com.moneybags.integration.AssistantLoanPort.Read;
import com.moneybags.iam.security.UserPrincipal;
import java.io.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.context.request.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpAssistantLoanClientTest {
 ServerSocket server;HttpAssistantLoanClient client;List<String> paths;volatile int status;volatile String body;
 @BeforeEach void setup()throws Exception{
  paths=Collections.synchronizedList(new ArrayList<>());status=200;body="{\"data\":{\"status\":\"ACTIVE\"}}";
  // Plain sockets avoid the Windows JDK selector's Unix-domain loopback pipe.
  server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
  Thread worker=new Thread(()->{
   while(!server.isClosed())try(var socket=server.accept()){
    socket.setSoTimeout(3000);var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
    String line=reader.readLine();if(line==null)continue;paths.add(line.split(" ")[1]);String authorization=null;
    while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("authorization:"))authorization=line.substring(line.indexOf(':')+1).trim();
    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);int code="Bearer synthetic-session".equals(authorization)?status:401;
    var output=socket.getOutputStream();output.write(("HTTP/1.1 "+code+" Response\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));output.write(bytes);output.flush();
   }catch(IOException error){if(!server.isClosed())throw new UncheckedIOException(error);}
  },"assistant-loan-http-qa");worker.setDaemon(true);worker.start();
  client=new HttpAssistantLoanClient("http://127.0.0.1:"+server.getLocalPort(),new ObjectMapper());
  var principal=new UserPrincipal("qa","qa","CUSTOMER","synthetic-session",1L,List.of(),List.of("LOAN_READ"),List.of("cif"));
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of()));
  var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer synthetic-session");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
 }
 @AfterEach void cleanup()throws Exception{if(server!=null)server.close();RequestContextHolder.resetRequestAttributes();SecurityContextHolder.clearContext();}
 @Test void allReadsUseFixedLoanRoutesAndForwardTheAuthenticatedSession(){
  for(Read read:Read.values())assertEquals("ACTIVE",client.read(read,7).path("status").asText());
  assertEquals(List.of("/api/v1/loans/my/applications","/api/v1/loans/applications/7","/api/v1/loans/applications/7/offers","/api/v1/loans/facilities/7","/api/v1/loans/facilities/7/schedule","/api/v1/loans/facilities/7/prepayment-quote"),paths);
 }
 @Test void callerMismatchIsRejectedBeforeAnyNetworkRequest(){
  RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
  assertEquals("LOGIN_REQUIRED",assertThrows(BusinessException.class,()->client.read(Read.FACILITY,7)).code());assertTrue(paths.isEmpty());
 }
 @Test void businessDenialsAndUnavailableServicesStayDistinct(){
  status=403;body="{\"code\":\"LOAN_ACCESS\",\"message\":\"Outside your scope\"}";
  assertEquals("LOAN_ACCESS",assertThrows(BusinessException.class,()->client.read(Read.FACILITY,7)).code());
  status=503;body="unavailable";assertEquals("LOANS_UNAVAILABLE",assertThrows(BusinessException.class,()->client.read(Read.FACILITY,7)).code());
 }
}
