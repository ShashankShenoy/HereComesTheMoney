package com.moneybags.iam.security;
import com.moneybags.iam.service.DeniedAuditService;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.slf4j.*;
public class IamDeniedAuditFilter extends OncePerRequestFilter {
    private static final Logger LOG=LoggerFactory.getLogger(IamDeniedAuditFilter.class);
    private final DeniedAuditService audit;private final boolean enabled;
    public IamDeniedAuditFilter(DeniedAuditService audit,boolean enabled){this.audit=audit;this.enabled=enabled;}
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        chain.doFilter(request,response);
        if(!enabled||!request.getRequestURI().startsWith("/api/v1/iam/")||response.getStatus()<400)return;
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication!=null&&authentication.getPrincipal() instanceof UserPrincipal user){
            try{audit.record(user,request.getRequestURI(),response.getStatus());}
            catch(Exception e){LOG.warn("Rejected IAM request audit could not be stored; exception={}",e.getClass().getSimpleName());}
        }
    }
}
