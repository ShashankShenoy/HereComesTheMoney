package com.moneybags.common.api;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import java.io.IOException;
import java.util.UUID;
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String id=request.getHeader(Correlation.HEADER);
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,80}")) id=UUID.randomUUID().toString();
        MDC.put("correlationId",id);response.setHeader(Correlation.HEADER,id);
        try { chain.doFilter(request,response); } finally { MDC.remove("correlationId"); }
    }
}
