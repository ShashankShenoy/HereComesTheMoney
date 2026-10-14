package com.moneybags;

import com.fasterxml.jackson.databind.*;
import com.moneybags.cif.service.CifService;
import com.moneybags.iam.service.MfaService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Explicit Oracle-only acceptance. Fixture and business mutations roll back at test end.
 * Normal rejection audit uses its independent transaction and is retained.
 * Fixtures use unique new identities/CIFs; no existing credential or financial row is edited.
 * Execute with MONEYBAGS_ORACLE_M12_TEST=true and Oracle DB_* settings.
 */
@EnabledIfEnvironmentVariable(named="MONEYBAGS_ORACLE_M12_TEST", matches="true")
@SpringBootTest(properties={"spring.sql.init.mode=never", "moneybags.bootstrap.enabled=false",
    "moneybags.customer-signup.enabled=true", "moneybags.deposit-interest.enabled=false",
    "moneybags.kafka.enabled=false", "moneybags.ai.provider=openai",
    "moneybags.cif.key-file=${M12_CIF_KEY_FILE}", "moneybags.auth.mfa-key-file=${M12_MFA_KEY_FILE}",
    "moneybags.documents.root=${M12_DOCUMENT_ROOT}",
    "moneybags.auth.allowed-origins=http://localhost:15173"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE) @ActiveProfiles("oracle") @Transactional
@Import(Module12OracleTest.TestTime.class)
class Module12OracleTest {
 @TestConfiguration static class TestTime {
  @Bean @Primary MutableClock oracleTestClock(){return new MutableClock();}
 }
 static class MutableClock extends Clock {
  Instant now=Instant.now(); public ZoneId getZone(){return ZoneOffset.UTC;}
  public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}
  public Instant instant(){return now;}
 }
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
 @Autowired DataSource datasource; @Autowired PasswordEncoder passwords;
 @Autowired MutableClock clock; @Autowired CifService cifService;
 String maker,checker,reader,none,makerId,checkerId,readerId,noneId,run;
 static final String PASSWORD="OracleQa!2026Secure";
 static final byte[] PDF="%PDF-1.4\nSynthetic Oracle QA evidence\n%%EOF".getBytes(StandardCharsets.UTF_8);
 record Identity(String id,String name,String token){}
 record Customer(String id,String party,String caseId){}

 @BeforeEach void fixture() throws Exception {
  try(var connection=datasource.getConnection()){
   assertEquals("Oracle",connection.getMetaData().getDatabaseProductName(),"Refusing non-Oracle database");
  }
  clock.now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);run="M12_"+UUID.randomUUID().toString().substring(0,8);
  var a=identity("maker","EMPLOYEE","GLOBAL",null,List.of("IAM_USER_READ","IAM_USER_CREATE","IAM_USER_MANAGE","IAM_ROLE_MANAGE","IAM_ACCESS_MANAGE","IAM_ACCESS_APPROVE","IAM_AUDIT_READ","CIF_READ","CIF_CREATE","CIF_UPDATE","KYC_UPLOAD","KYC_REVIEW"));
  var b=identity("checker","EMPLOYEE","GLOBAL",null,List.of("IAM_USER_READ","IAM_ACCESS_APPROVE","IAM_AUDIT_READ","CIF_READ","KYC_REVIEW"));
  var c=identity("reader","EMPLOYEE","BRANCH","M12_B1",List.of("CIF_READ"));
  var d=identity("none","EMPLOYEE",null,null,List.of());
  maker=a.token();makerId=a.id();checker=b.token();checkerId=b.id();reader=c.token();readerId=c.id();none=d.token();noneId=d.id();
 }
 Identity identity(String suffix,String type,String scope,String ref,List<String> permissions)throws Exception{
  String id=UUID.randomUUID().toString(), name=run+"_"+suffix, role=UUID.randomUUID().toString();
  db.update("INSERT INTO M01_IAM_USER(USER_ID,USERNAME,USER_TYPE,STATUS,EMPLOYEE_REF) VALUES (?,?,?,'ACTIVE',?)",id,name,type,"EMPLOYEE".equals(type)?run:null);
  db.update("INSERT INTO M01_IAM_CREDENTIAL(CREDENTIAL_ID,USER_ID,PASSWORD_HASH,HASH_SCHEME,STATUS) VALUES (?,?,?,'BCRYPT','ACTIVE')",UUID.randomUUID().toString(),id,passwords.encode(PASSWORD));
  if(scope!=null){
   db.update("INSERT INTO M01_IAM_ROLE(ROLE_ID,ROLE_CODE,DISPLAY_NAME,STATUS,SENSITIVE_FLAG) VALUES (?,?,?,'ACTIVE','N')",role,(run+"_"+suffix).toUpperCase(Locale.ROOT),"Oracle QA fixture");
   for(String permission:permissions)assertEquals(1,db.update("INSERT INTO M01_IAM_ROLE_PERMISSION(ROLE_ID,PERMISSION_ID) SELECT ?,PERMISSION_ID FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE=?",role,permission),"Missing installed permission "+permission);
   db.update("INSERT INTO M01_IAM_USER_ROLE(ASSIGNMENT_ID,USER_ID,ROLE_ID,SCOPE_TYPE,SCOPE_REF,STATUS,VALID_FROM) VALUES (?,?,?,?,?,'ACTIVE',?)",UUID.randomUUID().toString(),id,role,scope,ref,OffsetDateTime.now(clock).minusMinutes(1));
  }
  String token=ok("POST","/auth/login",null,Map.of("username",name,"password",PASSWORD,"clientId","M12_ORACLE_QA")).path("accessToken").asText();
  db.update("UPDATE M01_IAM_SESSION SET AUTH_LEVEL=? WHERE SESSION_ID=?","MFA@"+clock.instant().getEpochSecond(),token);
  return new Identity(id,name,token);
 }
 JsonNode request(String method,String path,String token,Object body,int expected)throws Exception{
  MockHttpServletRequestBuilder b=switch(method){case "POST"->post("/api/v1"+path);case "PUT"->put("/api/v1"+path);case "PATCH"->patch("/api/v1"+path);case "DELETE"->delete("/api/v1"+path);default->get("/api/v1"+path);};
  if(token!=null)b.header("Authorization","Bearer "+token);
  b.header("X-Correlation-ID",UUID.randomUUID().toString());
  if(body!=null)b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
  var result=mvc.perform(b).andReturn();var response=result.getResponse();
  // Do not print credential-bearing request/response bodies on assertion failure.
  String diagnostic="";
  if(result.getResolvedException()!=null){
   for(Throwable cause=result.getResolvedException();cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql){diagnostic=" Oracle code "+sql.getErrorCode();
    var match=java.util.regex.Pattern.compile("[A-Z0-9_]+[.]MB_[A-Z0-9_]+").matcher(Objects.toString(sql.getMessage(),""));if(match.find())diagnostic+=" constraint "+match.group();}
  }
  assertEquals(expected,response.getStatus(),method+" "+path+" expected "+expected+"; response status "+response.getStatus()+diagnostic);
  var node=response.getContentAsByteArray().length==0?json.createObjectNode():json.readTree(response.getContentAsString());
  return node.has("data")?node.path("data"):node;
 }
 JsonNode ok(String method,String path,String token,Object body)throws Exception{return request(method,path,token,body,200);}
 long version(String table,String key,String id){return db.queryForObject("SELECT ROW_VERSION FROM "+table+" WHERE "+key+"=?",Long.class,id);}
 long cv(Customer c){return version("M02_CIF_CUSTOMER","CIF_ID",c.id());}
 long kv(String id){return version("M02_KYC_CASE","CASE_ID",id);}
 long uv(String id){return version("M01_IAM_USER","USER_ID",id);}
 String status(String table,String key,String id){return db.queryForObject("SELECT STATUS FROM "+table+" WHERE "+key+"=?",String.class,id);}
 Customer customer(String name)throws Exception{
  var d=ok("POST","/cif/customers",maker,Map.of("partyType","INDIVIDUAL","legalName",name,"dateOfBirth","1990-01-01","homeBranchRef","M12_B1","segmentCode","RETAIL"));
  return new Customer(d.path("customer").path("CIF_ID").asText(),d.path("customer").path("PARTY_ID").asText(),d.path("cases").get(0).path("CASE_ID").asText());
 }
 void facts(Customer c)throws Exception{
  ok("POST","/cif/customers/"+c.id()+"/contacts",maker,Map.of("contactType","EMAIL","value",run+"@example.test"));
  ok("POST","/cif/customers/"+c.id()+"/identifiers",maker,Map.of("identifierType","QA_ID","value",UUID.randomUUID().toString(),"expiresOn",LocalDate.now(clock).plusYears(1).toString()));
  ok("POST","/cif/customers/"+c.id()+"/addresses",maker,Map.of("addressType","RESIDENTIAL","line1","Synthetic QA Road","city","Mumbai","countryCode","IN"));
 }
 JsonNode upload(String caseId,String type,String token,byte[] bytes,int expected)throws Exception{
  var response=mvc.perform(multipart("/api/v1/cif/cases/"+caseId+"/documents").file(new MockMultipartFile("file","evidence.pdf","application/pdf",bytes)).param("documentType",type).header("Authorization","Bearer "+token)).andReturn().getResponse();
  assertEquals(expected,response.getStatus(),"Evidence upload status");
  var n=json.readTree(response.getContentAsString());return n.has("data")?n.path("data"):n;
 }
 void submit(String caseId)throws Exception{ok("POST","/cif/cases/"+caseId+"/submit",maker,Map.of("rowVersion",kv(caseId),"reason","Oracle QA complete evidence"));}
 void attest(String caseId)throws Exception{
  for(String id:db.queryForList("SELECT DOCUMENT_ID FROM M02_KYC_DOCUMENT WHERE CASE_ID=?",String.class,caseId))
   ok("POST","/cif/documents/"+id+"/review",checker,Map.of("scanStatus","CLEAN","verificationStatus","VERIFIED","reason","Synthetic independent QA attestation"));
 }
 void prepare(Customer c)throws Exception{facts(c);upload(c.caseId(),"IDENTITY",maker,PDF,200);upload(c.caseId(),"ADDRESS",maker,PDF,200);submit(c.caseId());attest(c.caseId());}
 void decide(String caseId,String outcome,String token)throws Exception{
  var body=new LinkedHashMap<String,Object>(Map.of("rowVersion",kv(caseId),"outcome",outcome,"reasonCode","QA_CHECKED","comments","Synthetic Oracle QA review"));
  if(outcome.equals("APPROVE")){body.put("riskLevel","LOW");body.put("reviewDueAt",OffsetDateTime.now(clock).plusDays(90).toString());}
  ok("POST","/cif/cases/"+caseId+"/review",token,body);
 }
 void activate(Customer c)throws Exception{prepare(c);decide(c.caseId(),"APPROVE",checker);}
 Map<String,Object> access(String target,String role,String scope,String ref){
  var body=new LinkedHashMap<String,Object>(Map.of("targetUserId",target,"roleId",role,"changeType","GRANT","scopeType",scope,"validFrom",OffsetDateTime.now(clock).minusMinutes(1).toString(),"reason","Oracle QA scoped grant","dimensions",List.of()));
  if(ref!=null)body.put("scopeRef",ref);return body;
 }
 String role(List<String> perms)throws Exception{
  var ids=db.queryForList("SELECT PERMISSION_ID FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE IN ('CIF_READ','CIF_UPDATE','KYC_REVIEW')",String.class);
  var chosen=new ArrayList<String>();for(String name:perms)chosen.add(db.queryForObject("SELECT PERMISSION_ID FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE=?",String.class,name));
  return ok("POST","/iam/roles",maker,Map.of("roleCode",(run+"_"+UUID.randomUUID().toString().substring(0,5)).toUpperCase(Locale.ROOT),"displayName","Oracle QA role","sensitive",false,"permissionIds",chosen)).path("roleId").asText();
 }

 @Test @DisplayName("M1-AUTH-01 02 03 04: Oracle authentication states lockout and errors")
 void authenticationAndLockout()throws Exception{
  assertEquals(makerId,ok("GET","/auth/me",maker,null).path("userId").asText());
  request("POST","/auth/login",null,Map.of("username",run+"_maker","password","wrong"),401);
  request("POST","/auth/login",null,Map.of("username",run+"_missing","password",PASSWORD),401);
  request("POST","/auth/login",null,Map.of("username","","password",""),400);
  var x=identity("locked","EMPLOYEE",null,null,List.of());
  for(int i=0;i<5;i++)request("POST","/auth/login",null,Map.of("username",x.name(),"password","bad"),401);
  assertEquals(5,db.queryForObject("SELECT FAILED_ATTEMPTS FROM M01_IAM_CREDENTIAL WHERE USER_ID=?",Integer.class,x.id()));
  request("POST","/auth/login",null,Map.of("username",x.name(),"password",PASSWORD),401);
  clock.now=clock.now.plusSeconds(901);
  ok("POST","/auth/login",null,Map.of("username",x.name(),"password",PASSWORD));
  assertEquals(0,db.queryForObject("SELECT FAILED_ATTEMPTS FROM M01_IAM_CREDENTIAL WHERE USER_ID=?",Integer.class,x.id()));
  for(String state:List.of("PENDING_ACTIVATION","AUTH_LOCKED","SUSPENDED","DISABLED","CLOSED")){
   db.update("UPDATE M01_IAM_USER SET STATUS=? WHERE USER_ID=?",state,x.id());
   request("POST","/auth/login",null,Map.of("username",x.name(),"password",PASSWORD),401);
  }
 }
 @Test @DisplayName("M1-AUTH-07 08: Oracle signup atomicity adult and field boundaries")
 void signupBoundaries()throws Exception{
  var body=new LinkedHashMap<String,Object>(Map.of("username",run+"_signup","legalName","Synthetic Oracle QA Customer","dateOfBirth",LocalDate.now(clock).minusYears(18).toString(),"password",PASSWORD));
  var created=ok("POST","/auth/signup",null,body);assertFalse(created.path("cifId").asText().isBlank());
  request("POST","/auth/signup",null,body,409);body.put("username",run+"_minor");body.put("dateOfBirth",LocalDate.now(clock).minusYears(18).plusDays(1).toString());
  request("POST","/auth/signup",null,body,400);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M01_IAM_USER WHERE USERNAME=?",Integer.class,run+"_minor"));
  body.put("dateOfBirth","1990-01-01");for(String name:List.of("ab","a".repeat(121),"bad name")){body.put("username",name);request("POST","/auth/signup",null,body,400);}
 }
 @Test @DisplayName("M1-USR-01 02 04 05: Oracle directories and normal identity creation")
 void usersCreationAndPaging()throws Exception{
  for(String type:List.of("EMPLOYEE","SERVICE","CUSTOMER")){
   var b=new LinkedHashMap<String,Object>(Map.of("username",run+"_"+type,"userType",type,"password",PASSWORD));if(type.equals("EMPLOYEE"))b.put("employeeRef","QA_EMP");
   var response=ok("POST","/customer-access/users",maker,b);assertEquals("PENDING_ACTIVATION",response.path("user").path("status").asText());
   assertEquals(type.equals("CUSTOMER"),response.has("customerHash"));request("POST","/customer-access/users",maker,b,409);
  }
  for(String password:List.of("x".repeat(9),"x".repeat(73),"界".repeat(25)))request("POST","/customer-access/users",maker,Map.of("username",run+"_invalid","userType","SERVICE","password",password),400);
  request("POST","/customer-access/users",maker,Map.of("username",run+"_emp","userType","EMPLOYEE","password",PASSWORD),400);
  for(String path:List.of("/iam/users?page=-1","/iam/users?size=0","/iam/users?size=101","/iam/users?status=FAKE"))request("GET",path,maker,null,400);
  var users=ok("GET","/iam/users?search="+run+"&size=15",maker,null);assertTrue(users.path("total").asInt()>=7);
  assertFalse(ok("GET","/iam/users/"+makerId,maker,null).has("password"));request("GET","/iam/users",none,null,403);
 }
 @Test @DisplayName("M1-USR-06 07: Oracle identity transitions stale version and self protection")
 void statusTransitions()throws Exception{
  var x=identity("target","EMPLOYEE",null,null,List.of());long v=uv(x.id());
  ok("PATCH","/iam/users/"+x.id()+"/status",maker,Map.of("rowVersion",v,"status","SUSPENDED","reason","QA suspend"));
  request("GET","/auth/me",x.token(),null,401);
  request("PATCH","/iam/users/"+x.id()+"/status",maker,Map.of("rowVersion",v,"status","ACTIVE","reason","stale"),409);
  for(String target:List.of("ACTIVE","DISABLED","ACTIVE","CLOSED"))ok("PATCH","/iam/users/"+x.id()+"/status",maker,Map.of("rowVersion",uv(x.id()),"status",target,"reason","QA lifecycle"));
  request("PATCH","/iam/users/"+x.id()+"/status",maker,Map.of("rowVersion",uv(x.id()),"status","ACTIVE","reason","terminal"),400);
  request("PATCH","/iam/users/"+makerId+"/status",maker,Map.of("rowVersion",uv(makerId),"status","DISABLED","reason","self"),400);
 }
 @Test @DisplayName("M1-USR-08 and M1-SEC-02: Oracle password reset and session termination")
 void passwordsAndSessions()throws Exception{
  var x=identity("reset","EMPLOYEE",null,null,List.of());
  request("POST","/iam/password",x.token(),Map.of("currentPassword","wrong","newPassword","Replacement!2026"),400);
  request("POST","/iam/password",x.token(),Map.of("currentPassword",PASSWORD,"newPassword",PASSWORD),400);
  ok("POST","/iam/users/"+x.id()+"/password-reset",maker,Map.of("password","Replacement!2026","reason","QA reset"));
  request("GET","/auth/me",x.token(),null,401);request("POST","/auth/login",null,Map.of("username",x.name(),"password",PASSWORD),401);
  String token=ok("POST","/auth/login",null,Map.of("username",x.name(),"password","Replacement!2026")).path("accessToken").asText();
  ok("POST","/iam/password",token,Map.of("currentPassword","Replacement!2026","newPassword","Another!2026Secure"));request("GET","/auth/me",token,null,401);
 }
 @Test @DisplayName("M1-SEC-01 07 08: Oracle refresh replay own-session isolation and expiry")
 void refreshAndExpiry()throws Exception{
  var x=identity("refresh","EMPLOYEE",null,null,List.of());
  var login=ok("POST","/auth/login",null,Map.of("username",x.name(),"password",PASSWORD));
  var updated=ok("POST","/auth/refresh",null,Map.of("refreshToken",login.path("refreshToken").asText()));
  assertNotEquals(login.path("refreshToken"),updated.path("refreshToken"));
  request("POST","/auth/refresh",null,Map.of("refreshToken",login.path("refreshToken").asText()),401);
  request("GET","/auth/me",updated.path("accessToken").asText(),null,401);
  request("DELETE","/auth/sessions/"+maker,x.token(),null,403);
  ok("DELETE","/auth/sessions/"+x.token(),x.token(),null);request("GET","/auth/me",x.token(),null,401);
  clock.now=db.queryForObject("SELECT IDLE_EXPIRES_AT FROM M01_IAM_SESSION WHERE SESSION_ID=?",OffsetDateTime.class,reader).toInstant();
  request("GET","/auth/me",reader,null,401);
 }
 @Test @DisplayName("M1-AUTH-05 and M1-SEC-03 04 05 06: Oracle TOTP enrollment replay freshness and revoke")
 void mfaLifecycle()throws Exception{
  var x=identity("mfa","EMPLOYEE",null,null,List.of());var other=ok("POST","/auth/login",null,Map.of("username",x.name(),"password",PASSWORD));
  var enrollment=ok("POST","/iam/factors/totp",x.token(),Map.of());String factor=enrollment.path("factorId").asText(),secret=enrollment.path("secret").asText();
  request("POST","/iam/factors/"+factor+"/confirm",x.token(),Map.of("code","abc"),400);
  ok("POST","/iam/factors/"+factor+"/confirm",x.token(),Map.of("code",MfaService.totp(secret,clock.instant().getEpochSecond()/30)));
  request("GET","/auth/me",other.path("accessToken").asText(),null,401);
  assertFalse(ok("GET","/iam/factors",x.token(),null).toString().contains(secret));
  request("POST","/iam/factors/totp",x.token(),Map.of(),409);
  request("POST","/auth/login",null,Map.of("username",x.name(),"password",PASSWORD),401);
  request("POST","/iam/step-up",x.token(),Map.of("code",MfaService.totp(secret,clock.instant().getEpochSecond()/30)),401);
  clock.now=clock.now.plusSeconds(31);ok("POST","/iam/step-up",x.token(),Map.of("code",MfaService.totp(secret,clock.instant().getEpochSecond()/30)));
  clock.now=clock.now.plusSeconds(31);ok("POST","/iam/factors/"+factor+"/revoke",x.token(),Map.of("password",PASSWORD,"code",MfaService.totp(secret,clock.instant().getEpochSecond()/30)));
  ok("POST","/auth/login",null,Map.of("username",x.name(),"password",PASSWORD));
 }
 @Test @DisplayName("M1-ROLE-01 02 03 05: Oracle role and permission contracts protections and live retirement")
 void rolesPermissions()throws Exception{
  String id=role(List.of("CIF_READ"));var d=ok("GET","/iam/roles/"+id,maker,null);assertEquals(1,d.path("permissions").size());
  ok("PUT","/iam/roles/"+id,maker,Map.of("rowVersion",d.path("role").path("rowVersion").asLong(),"displayName","Changed QA role","sensitive",true,"status","RETIRED","permissionIds",List.of()));
  request("PUT","/iam/roles/"+id,maker,Map.of("rowVersion",d.path("role").path("rowVersion").asLong(),"displayName","stale","sensitive",false,"status","ACTIVE","permissionIds",List.of()),409);
  for(String code:List.of("BANK_ADMIN","BANK_CHECKER","bad code"))request("POST","/iam/roles",maker,Map.of("roleCode",code,"displayName","invalid","sensitive",false,"permissionIds",List.of()),400);
  for(String code:List.of("IAM_QA_RESERVED","SYSTEM_QA_RESERVED","bad code"))request("POST","/iam/permissions",maker,Map.of("permissionCode",code,"resourceCode","QA","actionCode","READ","stepUpRequired",false),400);
  request("POST","/iam/roles",reader,Map.of("roleCode","QA_UNAUTHORIZED","displayName","invalid","permissionIds",List.of()),403);
 }
 @Test @DisplayName("M1-ACC-01 03 04 05 06 08: Oracle grant approval separation rejection replay revocation")
 void accessDecisions()throws Exception{
  String role=role(List.of("CIF_READ"));var target=identity("access","EMPLOYEE",null,null,List.of());
  var r=ok("POST","/iam/access-requests",maker,access(target.id(),role,"BRANCH","M12_B1"));String id=r.path("requestId").asText();
  assertFalse(ok("GET","/auth/me",target.token(),null).path("permissions").toString().contains("CIF_READ"));
  var decision=Map.of("rowVersion",r.path("rowVersion").asLong(),"decision","APPROVED");
  request("POST","/iam/access-requests/"+id+"/decision",maker,decision,403);request("POST","/iam/access-requests/"+id+"/decision",target.token(),decision,403);
  ok("POST","/iam/access-requests/"+id+"/decision",checker,decision);request("POST","/iam/access-requests/"+id+"/decision",checker,decision,409);
  assertTrue(ok("GET","/auth/me",target.token(),null).path("permissions").toString().contains("CIF_READ"));
  var overlap=ok("POST","/iam/access-requests",maker,access(target.id(),role,"BRANCH","M12_B1"));request("POST","/iam/access-requests/"+overlap.path("requestId").asText()+"/decision",checker,Map.of("rowVersion",1,"decision","APPROVED"),409);
  ok("POST","/iam/access-requests/"+overlap.path("requestId").asText()+"/decision",checker,Map.of("rowVersion",1,"decision","REJECTED"));
  var assignment=ok("GET","/iam/users/"+target.id(),maker,null).path("assignments").get(0);
  var revoke=new LinkedHashMap<String,Object>(access(target.id(),role,"BRANCH","M12_B1"));revoke.put("changeType","REVOKE");revoke.put("targetAssignmentId",assignment.path("assignmentId").asText());revoke.put("validFrom",assignment.path("validFrom").asText());
  var rev=ok("POST","/iam/access-requests",maker,revoke);ok("POST","/iam/access-requests/"+rev.path("requestId").asText()+"/decision",checker,Map.of("rowVersion",1,"decision","APPROVED"));
  assertFalse(ok("GET","/auth/me",target.token(),null).path("permissions").toString().contains("CIF_READ"));
 }
 @Test @DisplayName("M1-ACC-02 07: Oracle access scope dimensions dates and expired decisions")
 void accessValidation()throws Exception{
  String role=role(List.of("CIF_READ"));
  for(String scope:List.of("BRANCH","REGION","SELF"))request("POST","/iam/access-requests",maker,access(noneId,role,scope,null),400);
  request("POST","/iam/access-requests",maker,access(noneId,role,"GLOBAL","EXTRA"),400);
  var body=access(noneId,role,"GLOBAL",null);body.put("validTo",OffsetDateTime.now(clock).minusDays(1).toString());request("POST","/iam/access-requests",maker,body,400);
  body=access(noneId,role,"GLOBAL",null);body.put("validTo",OffsetDateTime.now(clock).plusSeconds(2).toString());
  var r=ok("POST","/iam/access-requests",maker,body);clock.now=clock.now.plusSeconds(3);
  request("POST","/iam/access-requests/"+r.path("requestId").asText()+"/decision",checker,Map.of("rowVersion",1,"decision","APPROVED"),409);
  assertEquals("EXPIRED",status("M01_IAM_ACCESS_REQUEST","REQUEST_ID",r.path("requestId").asText()));
 }
 @Test @DisplayName("M1-ROLE-06 07 and M1-SEC-09 10: Oracle authority exact limits scope and expiry")
 void authorityBoundaries()throws Exception{
  String rid=db.queryForObject("SELECT ROLE_ID FROM M01_IAM_USER_ROLE WHERE USER_ID=?",String.class,readerId);
  var authority=ok("POST","/iam/roles/"+rid+"/authorities",maker,Map.of("authorityCode","QA_APPROVE","currencyCode","INR","maxAmount","100.0000","maxRatePct","12.50000000","validFrom",OffsetDateTime.now(clock).minusMinutes(1).toString()));
  for(String amount:List.of("99.9999","100.0000","100.0001")){
   var result=ok("POST","/iam/authorization/check",reader,Map.of("permissionCode","CIF_READ","branchRef","M12_B1","currencyCode","INR","authorityCode","QA_APPROVE","amount",amount,"ratePct","12.5"));assertEquals(!amount.equals("100.0001"),result.path("allowed").asBoolean());
  }
  assertFalse(ok("POST","/iam/authorization/check",reader,Map.of("permissionCode","CIF_READ","branchRef","M12_B2")).path("allowed").asBoolean());
  assertFalse(ok("POST","/iam/authorization/check",reader,Map.of("permissionCode","CIF_READ","branchRef","M12_B1","amount","1")).path("allowed").asBoolean());
  ok("DELETE","/iam/roles/"+rid+"/authorities/"+authority.path("authorityId").asText(),maker,null);
  clock.now=clock.now.plusMillis(1);
  assertFalse(ok("POST","/iam/authorization/check",reader,Map.of("permissionCode","CIF_READ","branchRef","M12_B1","currencyCode","INR","authorityCode","QA_APPROVE","amount","1")).path("allowed").asBoolean());
 }
 @Test @DisplayName("M1-OVR-01 03 and M1-AUD-01 02 04: Oracle counters audit denial and outbox")
 void overviewAndAudit()throws Exception{
  var d=ok("GET","/iam/dashboard",maker,null);assertTrue(d.path("users").asInt()>=4);
  request("GET","/iam/dashboard",none,null,403);
  role(List.of("CIF_READ"));
  assertTrue(ok("GET","/iam/audit?search=ROLE_CREATED",maker,null).size()>0);
  assertTrue(ok("GET","/iam/outbox",maker,null).size()>0);
  request("GET","/iam/audit",none,null,403);request("GET","/iam/outbox",none,null,403);
  request("GET","/iam/audit?search="+"x".repeat(121),maker,null,400);
 }
 @Test @DisplayName("M1-MENU-01 02: Oracle menu visibility and hierarchy validation")
 void menus()throws Exception{
  String perm=db.queryForObject("SELECT PERMISSION_ID FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE='IAM_USER_READ'",String.class);
  var body=new LinkedHashMap<String,Object>(Map.of("clientId","MONEYBAGS_WEB","routePath","/qa/"+run,"labelKey",run+" Menu","sortOrder",999,"requiredPermissionId",perm,"status","ACTIVE"));
  var saved=ok("POST","/iam/menus",maker,body);String id=saved.path("menuId").asText();
  assertTrue(ok("GET","/iam/menus",maker,null).toString().contains(id));assertFalse(ok("GET","/iam/menus",none,null).toString().contains(id));
  body.put("status","HIDDEN");ok("PUT","/iam/menus/"+id,maker,body);assertFalse(ok("GET","/iam/menus",maker,null).toString().contains(id));
  body.put("parentMenuId",id);request("PUT","/iam/menus/"+id,maker,body,400);
  body.remove("parentMenuId");body.put("sortOrder",-1);request("POST","/iam/menus",maker,body,400);
  request("GET","/iam/menus?all=true",none,null,403);
 }
 @Test @DisplayName("M2-DIR-01 03 04 06 and M2-AUD-02: Oracle CIF search scope and atomic create")
 void customerDirectoryAndScope()throws Exception{
  Customer c=customer(run+" Customer");
  assertEquals("PROSPECT",status("M02_CIF_CUSTOMER","CIF_ID",c.id()));
  assertEquals("DRAFT",status("M02_KYC_CASE","CASE_ID",c.caseId()));
  assertTrue(ok("GET","/cif/customers?search="+run.toLowerCase(Locale.ROOT),reader,null).toString().contains(c.id()));
  var wrong=identity("wrongbranch","EMPLOYEE","BRANCH","M12_B2",List.of("CIF_READ","CIF_CREATE"));
  assertFalse(ok("GET","/cif/customers?search="+run,wrong.token(),null).toString().contains(c.id()));
  request("GET","/cif/customers/"+c.id(),wrong.token(),null,403);
  request("GET","/cif/customers/"+c.id()+"/eligibility-facts",wrong.token(),null,403);
  request("GET","/cif/customers/"+c.id()+"/audit",wrong.token(),null,403);
  request("POST","/cif/customers",maker,Map.of("partyType","INDIVIDUAL","legalName","x".repeat(301),"dateOfBirth","1990-01-01","homeBranchRef","M12_B1"),400);
  request("POST","/cif/customers",none,Map.of("partyType","INDIVIDUAL","legalName","unauthorized","dateOfBirth","1990-01-01","homeBranchRef","M12_B1"),403);
 }
 @Test @DisplayName("M2-DIR-05: Oracle organization date consistency and future dates")
 void organizations()throws Exception{
  var body=new LinkedHashMap<String,Object>(Map.of("partyType","ORGANIZATION","legalName",run+" Organisation","incorporatedOn","2000-01-01","homeBranchRef","M12_B1"));
  var d=ok("POST","/cif/customers",maker,body);assertEquals("ORGANIZATION",d.path("party").path("PARTY_TYPE").asText());
  body.put("dateOfBirth","1990-01-01");request("POST","/cif/customers",maker,body,409);
  body.remove("dateOfBirth");body.put("incorporatedOn",LocalDate.now(clock).plusDays(1).toString());request("POST","/cif/customers",maker,body,409);
 }
 @Test @DisplayName("M2-PRO-01 05 06 07 08: Oracle encrypted contacts identifiers syntax duplicates and dates")
 void contactsAndIdentifiers()throws Exception{
  Customer c=customer(run+" Private");facts(c);
  var d=ok("GET","/cif/customers/"+c.id(),maker,null);assertFalse(d.toString().contains(run+"@example.test"));
  assertFalse(d.toString().contains("VALUE_CIPHERTEXT"));assertFalse(d.toString().contains("LOOKUP_HMAC"));
  for(String email:List.of("invalid","has space@example.test","missing@domain"))request("POST","/cif/customers/"+c.id()+"/contacts",maker,Map.of("contactType","EMAIL","value",email),409);
  for(String mobile:List.of("abc1234","123","+12*123456"))request("POST","/cif/customers/"+c.id()+"/contacts",maker,Map.of("contactType","MOBILE","value",mobile),409);
  ok("POST","/cif/customers/"+c.id()+"/contacts",maker,Map.of("contactType","MOBILE","value","+919876543210"));
  String val=UUID.randomUUID().toString();ok("POST","/cif/customers/"+c.id()+"/identifiers",maker,Map.of("identifierType","qa_duplicate","value",val));
  assertEquals("DUPLICATE_IDENTIFIER",request("POST","/cif/customers/"+c.id()+"/identifiers",maker,Map.of("identifierType","QA_DUPLICATE","value",val),409).path("code").asText());
  for(LocalDate day:List.of(LocalDate.now(clock),LocalDate.now(clock).minusDays(1)))request("POST","/cif/customers/"+c.id()+"/identifiers",maker,Map.of("identifierType","QA_EXPIRED","value",UUID.randomUUID().toString(),"expiresOn",day.toString()),409);
 }
 @Test @DisplayName("M2-PRO-09 10: Oracle address evidence type customer and maker ownership")
 void addressEvidence()throws Exception{
  Customer a=customer(run+" A"),b=customer(run+" B");
  String wrong=upload(b.caseId(),"ADDRESS",maker,PDF,200).path("documentId").asText();
  String identity=upload(a.caseId(),"IDENTITY",maker,PDF,200).path("documentId").asText();
  var body=new LinkedHashMap<String,Object>(Map.of("addressType","RESIDENTIAL","line1","QA Road","city","Mumbai","countryCode","IN","evidenceDocumentId",wrong));
  request("POST","/cif/customers/"+a.id()+"/addresses",maker,body,409);body.put("evidenceDocumentId",identity);request("POST","/cif/customers/"+a.id()+"/addresses",maker,body,409);
  body.put("evidenceDocumentId",upload(a.caseId(),"ADDRESS",maker,PDF,200).path("documentId").asText());
  for(String type:List.of("RESIDENTIAL","MAILING","REGISTERED")){body.put("addressType",type);ok("POST","/cif/customers/"+a.id()+"/addresses",maker,body);}
  body.put("countryCode","in");request("POST","/cif/customers/"+a.id()+"/addresses",maker,body,400);
 }
 @Test @DisplayName("M2-KYC-02 03 04 and M2-PRO-04: Oracle completeness maker ownership and freeze")
 void completenessAndFreeze()throws Exception{
  Customer c=customer(run+" Complete");
  request("POST","/cif/customers/"+c.id()+"/cases",maker,Map.of("caseType","PERIODIC"),409);
  request("POST","/cif/cases/"+c.caseId()+"/submit",maker,Map.of("rowVersion",kv(c.caseId()),"reason","incomplete"),409);
  facts(c);upload(c.caseId(),"IDENTITY",maker,PDF,200);
  request("POST","/cif/cases/"+c.caseId()+"/submit",maker,Map.of("rowVersion",kv(c.caseId()),"reason","address missing"),409);
  upload(c.caseId(),"ADDRESS",maker,PDF,200);long old=kv(c.caseId());submit(c.caseId());
  assertEquals("PENDING_VERIFICATION",status("M02_CIF_CUSTOMER","CIF_ID",c.id()));
  request("POST","/cif/cases/"+c.caseId()+"/submit",maker,Map.of("rowVersion",old,"reason","stale"),409);
  upload(c.caseId(),"OTHER",maker,PDF,409);
  request("POST","/cif/customers/"+c.id()+"/contacts",maker,Map.of("contactType","EMAIL","value","other@example.test"),409);
 }
 @Test @DisplayName("M2-KYC-05 06 07 08 12: Oracle assignment approval and segregation of duties")
 void independentKycApproval()throws Exception{
  Customer c=customer(run+" Review");prepare(c);
  request("POST","/cif/cases/"+c.caseId()+"/assign",maker,Map.of("rowVersion",kv(c.caseId()),"officerUserId",makerId),409);
  ok("POST","/cif/cases/"+c.caseId()+"/assign",checker,Map.of("rowVersion",kv(c.caseId()),"officerUserId",checkerId));
  var body=new LinkedHashMap<String,Object>(Map.of("rowVersion",kv(c.caseId()),"outcome","APPROVE","riskLevel","LOW","reasonCode","QA","comments","Independent QA review"));
  request("POST","/cif/cases/"+c.caseId()+"/review",checker,body,409);body.put("reviewDueAt",OffsetDateTime.now(clock).minusSeconds(1).toString());request("POST","/cif/cases/"+c.caseId()+"/review",checker,body,409);
  body.put("reviewDueAt",OffsetDateTime.now(clock).plusDays(30).toString());request("POST","/cif/cases/"+c.caseId()+"/review",maker,body,403);
  ok("POST","/cif/cases/"+c.caseId()+"/review",checker,body);
  assertEquals("ACTIVE",status("M02_CIF_CUSTOMER","CIF_ID",c.id()));assertEquals("VERIFIED",ok("GET","/cif/customers/"+c.id()+"/eligibility-facts",maker,null).path("kycStatus").asText());
  request("POST","/cif/cases/"+c.caseId()+"/review",checker,body,409);
 }
 @Test @DisplayName("M2-PRO-02 03 and M2-KYC-01 10 11: Oracle proposed segment rework rejection and history")
 void proposalReworkAndRejection()throws Exception{
  Customer c=customer(run+" History");activate(c);
  String caseId=ok("POST","/cif/customers/"+c.id()+"/cases",maker,Map.of("caseType","PERIODIC")).path("CASE_ID").asText();
  assertEquals("RESTRICTED",status("M02_CIF_CUSTOMER","CIF_ID",c.id()));
  ok("PUT","/cif/customers/"+c.id()+"/profile",maker,Map.of("rowVersion",cv(c),"legalName",run+" Proposed","segmentCode","PREMIUM","reason","QA profile proposal"));
  assertEquals("RETAIL",db.queryForObject("SELECT SEGMENT_CODE FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",String.class,c.id()));
  upload(caseId,"IDENTITY",maker,PDF,200);upload(caseId,"ADDRESS",maker,PDF,200);submit(caseId);
  decide(caseId,"REQUEST_INFO",checker);assertEquals("MORE_INFO_REQUIRED",status("M02_KYC_CASE","CASE_ID",caseId));
  ok("PUT","/cif/customers/"+c.id()+"/profile",maker,Map.of("rowVersion",cv(c),"legalName",run+" Corrected","segmentCode","PRIVATE","reason","QA correction"));
  submit(caseId);attest(caseId);decide(caseId,"REJECT",checker);
  assertEquals("RETAIL",db.queryForObject("SELECT SEGMENT_CODE FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",String.class,c.id()));
  assertEquals("REJECTED",db.queryForObject("SELECT KYC_STATUS FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",String.class,c.id()));
  assertTrue(ok("GET","/cif/cases/"+caseId,maker,null).path("reviews").size()==2);
 }
 @Test @DisplayName("M2-DOC-01 02 03: Oracle upload signatures size boundary and version allocation")
 void documentBoundaries()throws Exception{
  Customer c=customer(run+" Files");
  upload(c.caseId(),"IDENTITY",maker,new byte[0],400);upload(c.caseId(),"IDENTITY",maker,"Not a PDF".getBytes(StandardCharsets.UTF_8),400);
  upload(c.caseId(),"bad type",maker,PDF,400);upload(c.caseId(),"IDENTITY",checker,PDF,403);
  byte[] max=new byte[5*1024*1024];System.arraycopy(PDF,0,max,0,PDF.length);assertEquals(1,upload(c.caseId(),"IDENTITY",maker,max,200).path("versionNo").asInt());
  assertEquals(2,upload(c.caseId(),"IDENTITY",maker,PDF,200).path("versionNo").asInt());
  byte[] large=Arrays.copyOf(max,max.length+1);upload(c.caseId(),"OTHER",maker,large,400);
  byte[] png={(byte)137,80,78,71,13,10,26,10,0};upload(c.caseId(),"ADDRESS",maker,png,200);
  byte[] jpg={(byte)255,(byte)216,(byte)255,0};upload(c.caseId(),"OTHER",maker,jpg,200);
 }
 @Test @DisplayName("M2-DOC-04 05 06 07 and M2-KYC-09: Oracle unsafe evidence latest version and integrity download")
 void evidenceReviewAndDownload()throws Exception{
  Customer c=customer(run+" Evidence");facts(c);
  String id=upload(c.caseId(),"IDENTITY",maker,PDF,200).path("documentId").asText();upload(c.caseId(),"ADDRESS",maker,PDF,200);
  assertEquals(403,mvc.perform(get("/api/v1/cif/documents/"+id+"/content").header("Authorization","Bearer "+checker)).andReturn().getResponse().getStatus());submit(c.caseId());
  assertEquals(403,mvc.perform(get("/api/v1/cif/documents/"+id+"/content").header("Authorization","Bearer "+maker)).andReturn().getResponse().getStatus());
  var download=mvc.perform(get("/api/v1/cif/documents/"+id+"/content").header("Authorization","Bearer "+checker)).andReturn().getResponse();
  assertEquals(200,download.getStatus());assertArrayEquals(PDF,download.getContentAsByteArray());assertEquals("no-store",download.getHeader("Cache-Control"));
  request("POST","/cif/documents/"+id+"/review",maker,Map.of("scanStatus","CLEAN","verificationStatus","VERIFIED","reason","self"),403);
  request("POST","/cif/documents/"+id+"/review",checker,Map.of("scanStatus","QUARANTINED","verificationStatus","VERIFIED","reason","unsafe"),400);
  ok("POST","/cif/documents/"+id+"/review",checker,Map.of("scanStatus","FAILED","verificationStatus","REJECTED","reason","QA rejection"));
  assertEquals(400,mvc.perform(get("/api/v1/cif/documents/"+id+"/content").header("Authorization","Bearer "+checker)).andReturn().getResponse().getStatus());
  request("POST","/cif/cases/"+c.caseId()+"/review",checker,Map.of("rowVersion",kv(c.caseId()),"outcome","APPROVE","riskLevel","LOW","reviewDueAt",OffsetDateTime.now(clock).plusDays(30).toString(),"reasonCode","QA","comments","unsafe evidence"),409);
 }
 @Test @DisplayName("M2-LIFE-01 02 03: Oracle effective expiry sweep and separate reactivation")
 void periodicExpiry()throws Exception{
  Customer c=customer(run+" Expiry");activate(c);
  db.update("UPDATE M02_CIF_CUSTOMER SET NEXT_REVIEW_DUE_AT=? WHERE CIF_ID=?",OffsetDateTime.now(clock).minusSeconds(1),c.id());
  assertEquals("EXPIRED",ok("GET","/cif/customers/"+c.id()+"/eligibility-facts",maker,null).path("kycStatus").asText());
  request("PATCH","/cif/customers/"+c.id()+"/status",maker,Map.of("rowVersion",cv(c),"status","ACTIVE","reason","invalid activation"),409);
  // Scope the sweep to a dedicated test branch so shared due customers are untouched.
  var reviewer=identity("expire","EMPLOYEE","BRANCH","M12_B1",List.of("CIF_READ","KYC_REVIEW"));
  ok("POST","/cif/reviews/expire-due",reviewer.token(),Map.of());assertEquals("RESTRICTED",status("M02_CIF_CUSTOMER","CIF_ID",c.id()));
  assertEquals(0,ok("POST","/cif/reviews/expire-due",reviewer.token(),Map.of()).asInt());
 }
 @Test @DisplayName("M2-REL-01 02 03 04 05: Oracle consent history relationships direction and dates")
 void relationshipsAndConsent()throws Exception{
  Customer a=customer(run+" Relations A"),b=customer(run+" Relations B");
  var consent=Map.of("purposeCode","QA_PURPOSE","captureChannel","BRANCH","evidenceRef",run+" evidence");
  ok("POST","/cif/customers/"+a.id()+"/consents",maker,consent);request("POST","/cif/customers/"+a.id()+"/consents",maker,consent,409);
  String cid=db.queryForObject("SELECT CONSENT_ID FROM M02_CIF_CONSENT WHERE CIF_ID=?",String.class,a.id());
  request("DELETE","/cif/customers/"+b.id()+"/consents/"+cid,maker,null,409);
  ok("DELETE","/cif/customers/"+a.id()+"/consents/"+cid,maker,null);request("DELETE","/cif/customers/"+a.id()+"/consents/"+cid,maker,null,409);
  ok("POST","/cif/customers/"+a.id()+"/consents",maker,consent);
  for(String authority:List.of("NONE","VIEW","TRANSACT","REPRESENT"))ok("POST","/cif/customers/"+a.id()+"/relationships",maker,Map.of("targetCifId",b.id(),"relationshipType","QA_"+authority,"operatingAuthority",authority));
  request("POST","/cif/customers/"+a.id()+"/relationships",maker,Map.of("targetCifId",a.id(),"relationshipType","SELF","operatingAuthority","NONE"),409);
  var relation=Map.of("targetCifId",b.id(),"relationshipType","QA_NONE","operatingAuthority","NONE");request("POST","/cif/customers/"+a.id()+"/relationships",maker,relation,409);
  String rid=db.queryForObject("SELECT RELATIONSHIP_ID FROM M02_CIF_RELATIONSHIP WHERE SOURCE_PARTY_ID=? AND RELATIONSHIP_TYPE='QA_NONE'",String.class,a.party());
  request("DELETE","/cif/customers/"+b.id()+"/relationships/"+rid,maker,null,409);ok("DELETE","/cif/customers/"+a.id()+"/relationships/"+rid,maker,null);
  clock.now=clock.now.plusMillis(1);request("DELETE","/cif/customers/"+a.id()+"/relationships/"+rid,maker,null,409);
 }
 @Test @DisplayName("M2-LIFE-05 06 and M1-USR-10 11: Oracle closure links and identity cascades")
 void closureCascades()throws Exception{
  Customer a=customer(run+" Closure A"),b=customer(run+" Closure B");activate(a);activate(b);
  var owner=identity("owner","CUSTOMER","SELF",null,List.of("CIF_READ"));
  ok("POST","/iam/users/"+owner.id()+"/customer-links",maker,Map.of("cifId",a.id(),"relationshipType","SELF"));
  ok("POST","/iam/users/"+owner.id()+"/customer-links",maker,Map.of("cifId",b.id(),"relationshipType","SELF"));
  request("POST","/iam/users/"+owner.id()+"/customer-links",maker,Map.of("cifId",a.id(),"relationshipType","SELF"),400);
  ok("POST","/cif/customers/"+a.id()+"/consents",maker,Map.of("purposeCode","QA","captureChannel","BRANCH","evidenceRef",run));
  ok("POST","/cif/customers/"+a.id()+"/relationships",maker,Map.of("targetCifId",b.id(),"relationshipType","QA_LINK","operatingAuthority","NONE"));
  ok("PATCH","/cif/customers/"+a.id()+"/status",maker,Map.of("rowVersion",cv(a),"status","CLOSED","reason","QA closure"));
  request("GET","/auth/me",owner.token(),null,401);assertEquals("ACTIVE",status("M01_IAM_USER","USER_ID",owner.id()));
  assertEquals("WITHDRAWN",db.queryForObject("SELECT STATUS FROM M02_CIF_CONSENT WHERE CIF_ID=?",String.class,a.id()));
  ok("PATCH","/cif/customers/"+b.id()+"/status",maker,Map.of("rowVersion",cv(b),"status","CLOSED","reason","QA last CIF closure"));
  assertEquals("DISABLED",status("M01_IAM_USER","USER_ID",owner.id()));
  request("POST","/cif/customers/"+a.id()+"/cases",maker,Map.of("caseType","REMEDIATION"),409);
  request("PATCH","/cif/customers/"+a.id()+"/status",maker,Map.of("rowVersion",cv(a),"status","ACTIVE","reason","terminal"),409);
 }
 @Test @DisplayName("M1-KEY-01 and M2-AUD-01: Oracle key rotation and customer audit privacy")
 void holderKeyAndAudit()throws Exception{
  var owner=identity("key","CUSTOMER",null,null,List.of());
  var first=ok("POST","/customer-access/rotate",owner.token(),Map.of());var second=ok("POST","/customer-access/rotate",owner.token(),Map.of());
  assertEquals(43,first.path("customerHash").asText().length());assertNotEquals(first.path("customerHash"),second.path("customerHash"));
  request("POST","/customer-access/rotate",maker,Map.of(),403);
  Customer c=customer(run+" Audit");facts(c);var events=ok("GET","/cif/customers/"+c.id()+"/audit",maker,null);
  assertTrue(events.size()>0);assertFalse(events.toString().contains(run+"@example.test"));assertFalse(events.toString().contains("VALUE_CIPHERTEXT"));
 }
 @Test @DisplayName("M2-PRO-02 03: Oracle accepted profile revision blank segment and stale version")
 void profileVersionAndApproval()throws Exception{
  Customer c=customer(run+" Profile");facts(c);
  long v=cv(c);var change=new LinkedHashMap<String,Object>(Map.of("rowVersion",v,"legalName",run+" Changed","segmentCode","PREMIUM","reason","QA proposed revision"));
  ok("PUT","/cif/customers/"+c.id()+"/profile",maker,change);request("PUT","/cif/customers/"+c.id()+"/profile",maker,change,409);
  change.put("rowVersion",cv(c));change.put("segmentCode",null);ok("PUT","/cif/customers/"+c.id()+"/profile",maker,change);
  upload(c.caseId(),"IDENTITY",maker,PDF,200);upload(c.caseId(),"ADDRESS",maker,PDF,200);submit(c.caseId());attest(c.caseId());decide(c.caseId(),"APPROVE",checker);
  assertNull(db.queryForObject("SELECT SEGMENT_CODE FROM M02_CIF_CUSTOMER WHERE CIF_ID=?",String.class,c.id()));
  assertEquals(run+" Changed",db.queryForObject("SELECT FULL_NAME FROM M02_CIF_NAME WHERE PARTY_ID=? AND VALID_TO IS NULL AND VERIFICATION_STATUS='VERIFIED'",String.class,c.party()));
 }
 @Test @DisplayName("M1-USR-09: Oracle lost-factor reset and admin session user binding")
 void lostFactorReset()throws Exception{
  var x=identity("lost","EMPLOYEE",null,null,List.of());ok("POST","/iam/factors/totp",x.token(),Map.of());
  request("POST","/iam/users/"+makerId+"/factor-reset",maker,Map.of("reason","self reset"),400);
  request("DELETE","/iam/users/"+x.id()+"/sessions/"+maker,maker,null,404);
  ok("POST","/iam/users/"+x.id()+"/factor-reset",maker,Map.of("reason","QA lost authenticator"));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M01_IAM_AUTH_FACTOR WHERE USER_ID=? AND STATUS<>'REVOKED'",Integer.class,x.id()));
  request("GET","/auth/me",x.token(),null,401);
 }
 @Test @DisplayName("M2-DOC-07: Oracle own evidence corruption and missing-file fail closed")
 void documentIntegrityAndMissingFile()throws Exception{
  Customer c=customer(run+" Integrity");prepare(c);
  String id=db.queryForObject("SELECT DOCUMENT_ID FROM M02_KYC_DOCUMENT WHERE CASE_ID=? AND DOCUMENT_TYPE='IDENTITY'",String.class,c.caseId());
  var path=java.nio.file.Path.of(System.getenv("M12_DOCUMENT_ROOT")).toAbsolutePath().normalize().resolve(id+".enc");
  assertTrue(java.nio.file.Files.exists(path));byte[] original=java.nio.file.Files.readAllBytes(path);
  try{
   db.update("UPDATE M02_KYC_DOCUMENT SET SHA256_HEX=? WHERE DOCUMENT_ID=?","0".repeat(64),id);
   var r=mvc.perform(get("/api/v1/cif/documents/"+id+"/content").header("Authorization","Bearer "+checker)).andReturn().getResponse();
   assertEquals(400,r.getStatus());assertTrue(r.getContentAsString().contains("DOCUMENT_INTEGRITY_FAILED"));
   java.nio.file.Files.delete(path);
   assertEquals(404,mvc.perform(get("/api/v1/cif/documents/"+id+"/content").header("Authorization","Bearer "+checker)).andReturn().getResponse().getStatus());
  }finally{java.nio.file.Files.write(path,original);}
 }
 @Test @DisplayName("M1-ROLE-04 and M1-SEC-05: Oracle fresh MFA boundary at 300 and 301 seconds")
 void freshMfaBoundary()throws Exception{
  var perm=ok("POST","/iam/permissions",maker,Map.of("permissionCode",run.toUpperCase(Locale.ROOT)+"_STEP","resourceCode","QA","actionCode","READ","stepUpRequired",true));
  String role=UUID.randomUUID().toString();
  db.update("INSERT INTO M01_IAM_ROLE(ROLE_ID,ROLE_CODE,DISPLAY_NAME,STATUS,SENSITIVE_FLAG) VALUES (?,?,?,'ACTIVE','N')",role,run.toUpperCase(Locale.ROOT)+"_FRESH","QA MFA boundary");
  db.update("INSERT INTO M01_IAM_ROLE_PERMISSION(ROLE_ID,PERMISSION_ID) VALUES (?,?)",role,perm.path("permissionId").asText());
  db.update("INSERT INTO M01_IAM_USER_ROLE(ASSIGNMENT_ID,USER_ID,ROLE_ID,SCOPE_TYPE,STATUS,VALID_FROM) VALUES (?,?,?,'GLOBAL','ACTIVE',?)",UUID.randomUUID().toString(),noneId,role,OffsetDateTime.now(clock).minusMinutes(1));
  db.update("UPDATE M01_IAM_SESSION SET AUTH_LEVEL=? WHERE SESSION_ID=?","MFA@"+clock.instant().getEpochSecond(),none);
  var query=Map.of("permissionCode",perm.path("permissionCode").asText());
  clock.now=clock.now.plusSeconds(300);assertTrue(ok("POST","/iam/authorization/check",none,query).path("allowed").asBoolean());
  clock.now=clock.now.plusSeconds(1);assertFalse(ok("POST","/iam/authorization/check",none,query).path("allowed").asBoolean());
 }
 @Test @DisplayName("Field appendix: Oracle exact and oversized contact identifier address and consent fields")
 void fieldBoundaryMatrix()throws Exception{
  Customer c=customer(run+" Boundaries");
  for(int length:List.of(239,240,241)){
   String value="a".repeat(length-13)+"@example.test";
   request("POST","/cif/customers/"+c.id()+"/contacts",maker,Map.of("contactType","EMAIL","value",value),length<=240?200:400);
  }
  for(int length:List.of(199,200,201))request("POST","/cif/customers/"+c.id()+"/identifiers",maker,Map.of("identifierType","QA_"+length,"value","x".repeat(length),"issuerCode","x".repeat(80)),length<=200?200:400);
  var addr=new LinkedHashMap<String,Object>(Map.of("addressType","RESIDENTIAL","line1","x".repeat(240),"line2","x".repeat(240),"city","x".repeat(120),"region","x".repeat(120),"postalCode","x".repeat(24),"countryCode","IN"));
  ok("POST","/cif/customers/"+c.id()+"/addresses",maker,addr);
  for(var f:Map.of("line1",241,"line2",241,"city",121,"region",121,"postalCode",25).entrySet()){
   var bad=new LinkedHashMap<String,Object>(addr);bad.put(f.getKey(),"x".repeat(f.getValue()));request("POST","/cif/customers/"+c.id()+"/addresses",maker,bad,400);
  }
  var consent=new LinkedHashMap<String,Object>(Map.of("purposeCode","x".repeat(80),"captureChannel","x".repeat(40),"evidenceRef","x".repeat(240)));
  ok("POST","/cif/customers/"+c.id()+"/consents",maker,consent);
  for(var f:Map.of("purposeCode",81,"captureChannel",41,"evidenceRef",241).entrySet()){
   var bad=new LinkedHashMap<String,Object>(consent);bad.put(f.getKey(),"x".repeat(f.getValue()));request("POST","/cif/customers/"+c.id()+"/consents",maker,bad,400);
  }
 }
 @Test @DisplayName("M1-ACC-09 and M1-USR-01: Oracle directory pagination beyond 100 identities")
 void userCataloguePagination()throws Exception{
  var rows=new ArrayList<Object[]>();
  for(int i=0;i<101;i++)rows.add(new Object[]{UUID.randomUUID().toString(),run+"_page_"+i,run});
  db.batchUpdate("INSERT INTO M01_IAM_USER(USER_ID,USERNAME,USER_TYPE,STATUS,EMPLOYEE_REF) VALUES (?,?,'EMPLOYEE','ACTIVE',?)",rows);
  var first=ok("GET","/iam/users?page=0&size=100",maker,null);
  var second=ok("GET","/iam/users?page=1&size=100",maker,null);
  assertEquals(100,first.path("items").size());assertFalse(first.path("items").toString().contains(noneId));
  assertTrue(second.path("items").toString().contains(noneId));
 }
 @Test @DisplayName("M1-USR-06: Oracle complete permitted identity transition matrix")
 void completeStatusMatrix()throws Exception{
  var target=identity("matrix","EMPLOYEE",null,null,List.of());
  var transitions=Map.of("PENDING_ACTIVATION",List.of("ACTIVE","DISABLED","CLOSED"),"ACTIVE",List.of("AUTH_LOCKED","SUSPENDED","DISABLED","CLOSED"),"AUTH_LOCKED",List.of("ACTIVE","SUSPENDED","DISABLED","CLOSED"),"SUSPENDED",List.of("ACTIVE","DISABLED","CLOSED"),"DISABLED",List.of("ACTIVE","CLOSED"));
  for(var entry:transitions.entrySet())for(String next:entry.getValue()){
   db.update("UPDATE M01_IAM_USER SET STATUS=? WHERE USER_ID=?",entry.getKey(),target.id());
   ok("PATCH","/iam/users/"+target.id()+"/status",maker,Map.of("rowVersion",uv(target.id()),"status",next,"reason","QA transition matrix"));
   assertEquals(next,status("M01_IAM_USER","USER_ID",target.id()));
  }
  db.update("UPDATE M01_IAM_USER SET STATUS='CLOSED' WHERE USER_ID=?",target.id());
  for(String next:List.of("ACTIVE","DISABLED","SUSPENDED","AUTH_LOCKED"))request("PATCH","/iam/users/"+target.id()+"/status",maker,Map.of("rowVersion",uv(target.id()),"status",next,"reason","terminal"),400);
 }
 @Test @DisplayName("M1-ACC-01 02 and M1-SEC-09: Oracle scope dimensions OR within AND across and live grant revocation")
 void scopeDimensionsAndLiveRoleRetirement()throws Exception{
  var target=identity("dimensions","EMPLOYEE",null,null,List.of());String role=role(List.of("CIF_READ"));
  var body=access(target.id(),role,"BRANCH","M12_B1");
  body.put("dimensions",List.of(Map.of("dimensionCode","PRODUCT_TYPE","scopeValue","SAVINGS"),Map.of("dimensionCode","PRODUCT_TYPE","scopeValue","CURRENT"),Map.of("dimensionCode","CURRENCY","scopeValue","INR")));
  var grant=ok("POST","/iam/access-requests",maker,body);ok("POST","/iam/access-requests/"+grant.path("requestId").asText()+"/decision",checker,Map.of("rowVersion",1,"decision","APPROVED"));
  for(String product:List.of("SAVINGS","CURRENT","LOAN"))for(String currency:List.of("INR","USD")){
   var decision=ok("POST","/iam/authorization/check",target.token(),Map.of("permissionCode","CIF_READ","branchRef","M12_B1","productType",product,"currencyCode",currency));
   assertEquals(!product.equals("LOAN")&&currency.equals("INR"),decision.path("allowed").asBoolean());
  }
  var detail=ok("GET","/iam/roles/"+role,maker,null);
  ok("PUT","/iam/roles/"+role,maker,Map.of("rowVersion",detail.path("role").path("rowVersion").asLong(),"displayName","Retired QA role","sensitive",false,"status","RETIRED","permissionIds",List.of()));
  assertFalse(ok("GET","/auth/me",target.token(),null).path("permissions").toString().contains("CIF_READ"));
 }
 @Test @DisplayName("M1-ROLE-03 05 07: Oracle reserved roles duplicate permission lists and authority validation")
 void governanceValidationMatrix()throws Exception{
  for(String code:List.of("BANK_ADMIN","BANK_CHECKER")){
   String id=db.queryForObject("SELECT ROLE_ID FROM M01_IAM_ROLE WHERE ROLE_CODE=?",String.class,code);
   var detail=ok("GET","/iam/roles/"+id,maker,null);
   request("PUT","/iam/roles/"+id,maker,Map.of("rowVersion",detail.path("role").path("rowVersion").asLong(),"displayName","Reserved","sensitive",true,"status","RETIRED","permissionIds",List.of()),400);
  }
  String permission=db.queryForObject("SELECT PERMISSION_ID FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE='CIF_READ'",String.class);
  request("POST","/iam/roles",maker,Map.of("roleCode",run.toUpperCase(Locale.ROOT)+"_DUP","displayName","duplicate","sensitive",false,"permissionIds",List.of(permission,permission)),400);
  request("POST","/iam/roles",maker,Map.of("roleCode",run.toUpperCase(Locale.ROOT)+"_LONG","displayName","oversized list","sensitive",false,"permissionIds",Collections.nCopies(101,permission)),400);
  String rid=role(List.of("CIF_READ"));
  var base=new LinkedHashMap<String,Object>(Map.of("authorityCode","QA_APPROVE","currencyCode","INR","validFrom",OffsetDateTime.now(clock).toString()));
  request("POST","/iam/roles/"+rid+"/authorities",maker,base,400);
  for(String amount:List.of("-1","0.00001","10000000000000000")){var bad=new LinkedHashMap<String,Object>(base);bad.put("maxAmount",amount);request("POST","/iam/roles/"+rid+"/authorities",maker,bad,400);}
  base.put("maxAmount","0");base.put("validTo",base.get("validFrom"));request("POST","/iam/roles/"+rid+"/authorities",maker,base,400);
 }
 @Test @DisplayName("M2-KYC-06 and M2-DOC-05: Oracle current assigned officer and wrong maker isolation")
 void assignedOfficerAndMakerIsolation()throws Exception{
  Customer c=customer(run+" Assigned");prepare(c);
  var other=identity("otherreviewer","EMPLOYEE","BRANCH","M12_B1",List.of("CIF_READ","CIF_UPDATE","KYC_UPLOAD","KYC_REVIEW"));
  ok("POST","/cif/cases/"+c.caseId()+"/assign",checker,Map.of("rowVersion",kv(c.caseId()),"officerUserId",checkerId));
  var review=Map.of("rowVersion",kv(c.caseId()),"outcome","REJECT","reasonCode","QA","comments","Wrong assigned officer");
  request("POST","/cif/cases/"+c.caseId()+"/review",other.token(),review,409);
  String doc=db.queryForObject("SELECT DOCUMENT_ID FROM M02_KYC_DOCUMENT WHERE CASE_ID=? AND DOCUMENT_TYPE='IDENTITY'",String.class,c.caseId());
  request("POST","/cif/documents/"+doc+"/review",other.token(),Map.of("scanStatus","CLEAN","verificationStatus","VERIFIED","reason","Wrong officer"),403);
  decide(c.caseId(),"REQUEST_INFO",checker);
  request("POST","/cif/customers/"+c.id()+"/contacts",other.token(),Map.of("contactType","EMAIL","value","wrongmaker@example.test"),409);
  upload(c.caseId(),"OTHER",other.token(),PDF,409);
 }
 @Test @DisplayName("M2-KYC-09: Oracle latest evidence and OTHER document approval gate")
 void latestEvidenceAndOtherGate()throws Exception{
  Customer c=customer(run+" Latest");prepare(c);decide(c.caseId(),"REQUEST_INFO",checker);
  upload(c.caseId(),"IDENTITY",maker,PDF,200);String other=upload(c.caseId(),"OTHER",maker,PDF,200).path("documentId").asText();submit(c.caseId());
  var approval=Map.of("rowVersion",kv(c.caseId()),"outcome","APPROVE","riskLevel","LOW","reviewDueAt",OffsetDateTime.now(clock).plusDays(90).toString(),"reasonCode","QA","comments","Latest version gate");
  request("POST","/cif/cases/"+c.caseId()+"/review",checker,approval,409);
  String latest=db.queryForObject("SELECT DOCUMENT_ID FROM M02_KYC_DOCUMENT WHERE CASE_ID=? AND DOCUMENT_TYPE='IDENTITY' AND VERSION_NO=2",String.class,c.caseId());
  ok("POST","/cif/documents/"+latest+"/review",checker,Map.of("scanStatus","CLEAN","verificationStatus","VERIFIED","reason","Latest identity verified"));
  request("POST","/cif/cases/"+c.caseId()+"/review",checker,approval,409);
  ok("POST","/cif/documents/"+other+"/review",checker,Map.of("scanStatus","CLEAN","verificationStatus","VERIFIED","reason","Other evidence verified"));
  decide(c.caseId(),"APPROVE",checker);
 }
 @Test @DisplayName("M1-USR-10 11 and X-FLOW-02: Oracle SELF links immediate revoke and no implicit party authority")
 void linkRevocationAndSelfScope()throws Exception{
  Customer c=customer(run+" Linked");activate(c);var owner=identity("selfscope","CUSTOMER","SELF",null,List.of("CIF_READ"));
  request("GET","/cif/customers/"+c.id(),owner.token(),null,403);
  var link=ok("POST","/iam/users/"+owner.id()+"/customer-links",maker,Map.of("cifId",c.id(),"relationshipType","SELF"));
  ok("GET","/cif/customers/"+c.id(),owner.token(),null);
  ok("DELETE","/iam/users/"+owner.id()+"/customer-links/"+link.path("linkId").asText(),maker,null);
  request("GET","/cif/customers/"+c.id(),owner.token(),null,403);
  assertEquals("ACTIVE",status("M02_CIF_CUSTOMER","CIF_ID",c.id()));
  assertTrue(ok("GET","/auth/me",owner.token(),null).path("cifIds").isEmpty());
 }
 @Test @DisplayName("M1-ACC-08: Oracle overlapping grants rejected and adjacent windows accepted")
 void overlappingAndAdjacentAccessWindows()throws Exception{
  var target=identity("windowtarget","EMPLOYEE",null,null,List.of());String rid=role(List.of("CIF_READ"));
  var start=OffsetDateTime.now(clock).minusMinutes(1);var middle=OffsetDateTime.now(clock).plusMinutes(1);var end=OffsetDateTime.now(clock).plusMinutes(2);
  var first=access(target.id(),rid,"BRANCH","M12_B1");first.put("validFrom",start.toString());first.put("validTo",middle.toString());
  var r1=ok("POST","/iam/access-requests",maker,first);
  ok("POST","/iam/access-requests/"+r1.path("requestId").asText()+"/decision",checker,Map.of("rowVersion",1,"decision","APPROVED"));
  var overlap=access(target.id(),rid,"BRANCH","M12_B1");overlap.put("validFrom",OffsetDateTime.now(clock).toString());overlap.put("validTo",end.toString());
  var r2=ok("POST","/iam/access-requests",maker,overlap);
  request("POST","/iam/access-requests/"+r2.path("requestId").asText()+"/decision",checker,Map.of("rowVersion",1,"decision","APPROVED"),409);
  assertEquals("SUBMITTED",status("M01_IAM_ACCESS_REQUEST","REQUEST_ID",r2.path("requestId").asText()));
  var adjacent=access(target.id(),rid,"BRANCH","M12_B1");adjacent.put("validFrom",middle.toString());adjacent.put("validTo",end.toString());
  var r3=ok("POST","/iam/access-requests",maker,adjacent);
  ok("POST","/iam/access-requests/"+r3.path("requestId").asText()+"/decision",checker,Map.of("rowVersion",1,"decision","APPROVED"));
  assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM M01_IAM_USER_ROLE WHERE USER_ID=? AND ROLE_ID=? AND STATUS='ACTIVE'",Integer.class,target.id(),rid));
 }
 @Test @DisplayName("X-FLOW-03: Oracle rechecks edit and review permissions after stale forms")
 void revokedPermissionsRejectStaleWork()throws Exception{
  var editor=identity("staleeditor","EMPLOYEE","GLOBAL",null,List.of("CIF_CREATE","CIF_READ","CIF_UPDATE"));
  var created=ok("POST","/cif/customers",editor.token(),Map.of("partyType","INDIVIDUAL","legalName",run+" Stale","dateOfBirth","1990-01-01","homeBranchRef","M12_B1","segmentCode","RETAIL"));
  String cif=created.path("customer").path("CIF_ID").asText();long customerVersion=version("M02_CIF_CUSTOMER","CIF_ID",cif);
  ok("GET","/cif/customers/"+cif,editor.token(),null);
  String editorRole=db.queryForObject("SELECT ROLE_ID FROM M01_IAM_USER_ROLE WHERE USER_ID=?",String.class,editor.id());
  var editorDetail=ok("GET","/iam/roles/"+editorRole,maker,null);
  ok("PUT","/iam/roles/"+editorRole,maker,Map.of("rowVersion",editorDetail.path("role").path("rowVersion").asLong(),"displayName","Retired QA editor","sensitive",false,"status","RETIRED","permissionIds",List.of()));
  request("PUT","/cif/customers/"+cif+"/profile",editor.token(),Map.of("rowVersion",customerVersion,"legalName",run+" Forbidden","segmentCode","PREMIUM","reason","Stale form"),403);
  assertEquals(customerVersion,version("M02_CIF_CUSTOMER","CIF_ID",cif));
  Customer review=customer(run+" Review");prepare(review);long caseVersion=kv(review.caseId());
  ok("GET","/cif/cases/"+review.caseId(),checker,null);
  String reviewerRole=db.queryForObject("SELECT ROLE_ID FROM M01_IAM_USER_ROLE WHERE USER_ID=?",String.class,checkerId);
  var reviewerDetail=ok("GET","/iam/roles/"+reviewerRole,maker,null);
  ok("PUT","/iam/roles/"+reviewerRole,maker,Map.of("rowVersion",reviewerDetail.path("role").path("rowVersion").asLong(),"displayName","Retired QA reviewer","sensitive",false,"status","RETIRED","permissionIds",List.of()));
  request("POST","/cif/cases/"+review.caseId()+"/review",checker,Map.of("rowVersion",caseVersion,"outcome","APPROVE","reasonCode","QA_CHECKED","comments","Stale reviewer","riskLevel","LOW","reviewDueAt",OffsetDateTime.now(clock).plusDays(90).toString()),403);
  assertEquals(caseVersion,kv(review.caseId()));
 }
 @org.springframework.test.context.transaction.AfterTransaction
 void verifyRollback(){
  if(run!=null)assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM M01_IAM_USER WHERE USERNAME LIKE ?",Integer.class,run+"%"),"Oracle fixture identities persisted after rollback");
 }
}
