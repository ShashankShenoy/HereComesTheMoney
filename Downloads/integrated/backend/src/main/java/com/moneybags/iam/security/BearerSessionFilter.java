package com.moneybags.iam.security;
import com.moneybags.iam.service.LoginService;
import com.moneybags.common.api.BusinessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.ArrayList;
public class BearerSessionFilter extends OncePerRequestFilter {
    private final LoginService login;
    private final JsonSecurityErrors errors;
    public BearerSessionFilter(LoginService login,JsonSecurityErrors errors) { this.login=login;this.errors=errors; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { String p=request.getRequestURI();return p.equals("/api/v1/auth/login")||p.equals("/api/v1/auth/refresh")||p.equals("/api/v1/auth/signup")||p.equals("/api/v1/auth/signup-availability")||p.startsWith("/actuator/health")||request.getMethod().equals("OPTIONS"); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String header=request.getHeader("Authorization");
        if(header!=null) {
            if(!header.startsWith("Bearer ")||header.length()>128) { errors.write(request,response,401,"INVALID_TOKEN","Supply an Authorization: Bearer token");return; }
            try {
                var user=login.authenticate(header.substring(7));
                var authorities=new ArrayList<SimpleGrantedAuthority>();user.permissions().forEach(p->authorities.add(new SimpleGrantedAuthority(p)));user.roles().forEach(r->authorities.add(new SimpleGrantedAuthority("ROLE_"+r)));
                authorities.addAll(com.moneybags.integration.PermissionBridge.authorities(user));
                var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(user,null,authorities));SecurityContextHolder.setContext(context);
            } catch(BusinessException e) { errors.write(request,response,e.status().value(),e.code(),e.getMessage());return; }
              catch(DataAccessException e) { errors.write(request,response,503,"DATABASE_UNAVAILABLE","Authentication database is unavailable");return; }
        }
        chain.doFilter(request,response);
    }
}
