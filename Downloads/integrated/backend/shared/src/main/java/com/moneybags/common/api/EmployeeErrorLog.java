package com.moneybags.common.api;
import com.moneybags.integration.CurrentActor;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Safe cross-service failure metadata; never stores request bodies, credentials or stack traces. */
@RestController
public class EmployeeErrorLog {
 private final JdbcTemplate db;
 public EmployeeErrorLog(JdbcTemplate db){this.db=db;}
 @GetMapping("/api/v1/employee-errors") public List<Map<String,Object>> list(){
  if(!"EMPLOYEE".equals(CurrentActor.get().userType()))throw new BusinessException(HttpStatus.FORBIDDEN,"EMPLOYEE_ONLY","Employee access required");
  return db.queryForList("SELECT ERROR_ID,OCCURRED_AT,SERVICE_NAME,ERROR_CODE,SAFE_MESSAGE,CORRELATION_ID FROM MBX_EMPLOYEE_ERROR ORDER BY OCCURRED_AT DESC FETCH FIRST 200 ROWS ONLY");
 }
}
@Component
class EmployeeFailureFilter extends OncePerRequestFilter {
 private final JdbcTemplate db;private final String service;
 EmployeeFailureFilter(JdbcTemplate db,@Value("${spring.application.name:banking}")String service){this.db=db;this.service=service;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  try{chain.doFilter(request,response);}finally{
   if(request.getRequestURI().startsWith("/api/v1/")&&response.getStatus()>=400){
    String ref=response.getHeader("X-Correlation-Id");if(ref==null||ref.length()>100)ref=UUID.randomUUID().toString();
    try{db.update("INSERT INTO MBX_EMPLOYEE_ERROR(ERROR_ID,SERVICE_NAME,ERROR_CODE,SAFE_MESSAGE,CORRELATION_ID) VALUES (?,?,?,?,?)",UUID.randomUUID().toString(),service,"HTTP_"+response.getStatus(),response.getStatus()>=500?"Application request failed; inspect service logs using the reference":"Request rejected by validation or access controls",ref);}
    catch(org.springframework.dao.DataAccessException ignored){logger.warn("Employee error metadata could not be persisted");}
   }
  }
 }
}
