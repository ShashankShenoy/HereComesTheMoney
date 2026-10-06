package com.moneybags.config;
import com.moneybags.iam.security.*;
import com.moneybags.iam.service.LoginService;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.crypto.password.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.cors.*;
import java.util.*;
@Configuration @EnableMethodSecurity
public class SecurityConfiguration {
    @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean public UserDetailsService userDetailsService() {
        // Disable Boot's generated in-memory user. Authentication is owned by IAM.
        return username->{throw new UsernameNotFoundException("Use the IAM login endpoint");};
    }
    @Bean public SecurityFilterChain security(HttpSecurity http,LoginService login,JsonSecurityErrors errors,@Qualifier("corsConfigurationSource") CorsConfigurationSource cors,
        com.moneybags.iam.service.DeniedAuditService audit,com.moneybags.common.security.DomainDeniedAudit domainAudit,@Value("${moneybags.auth.audit-denials:true}")boolean auditDenials) throws Exception {
        return http.cors(c->c.configurationSource(cors))
            // APIs accept bearer headers only; no ambient browser cookies or HTTP Basic.
            .csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable()).logout(c->c.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/actuator/health/**","/api/v1/auth/login","/api/v1/auth/refresh","/api/v1/auth/signup","/api/v1/auth/signup-availability","/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").permitAll().anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->errors.write(r,s,401,"LOGIN_REQUIRED","Please sign in"))
                .accessDeniedHandler((r,s,x)->errors.write(r,s,403,"FORBIDDEN","You do not have permission for this action")))
            .addFilterBefore(new BearerSessionFilter(login,errors),UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new IamDeniedAuditFilter(audit,auditDenials),BearerSessionFilter.class)
            .addFilterAfter(new com.moneybags.common.security.DomainDeniedAuditFilter(domainAudit,auditDenials),IamDeniedAuditFilter.class).build();
    }
    @Bean public CorsConfigurationSource corsConfigurationSource(@Value("${moneybags.auth.allowed-origins}")String origins) {
        Set<String> allowed=new LinkedHashSet<>();
        for(String value:origins.split(",")) {
            String origin=value.trim();if(origin.isBlank())continue;allowed.add(origin);
            String host=java.net.URI.create(origin).getHost();
            if("localhost".equalsIgnoreCase(host))allowed.add(origin.replace("://localhost","://127.0.0.1"));
            if("127.0.0.1".equals(host))allowed.add(origin.replace("://127.0.0.1","://localhost"));
        }
        var c=new CorsConfiguration();c.setAllowedOrigins(new ArrayList<>(allowed));
        c.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));c.setAllowedHeaders(List.of("Authorization","Content-Type","X-Correlation-ID","Idempotency-Key","X-Customer-Hash","If-Match","MCP-Protocol-Version","Mcp-Method","Mcp-Name"));c.setExposedHeaders(List.of("X-Correlation-ID","Content-Disposition"));c.setAllowCredentials(false);
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",c);return source;
    }
}
