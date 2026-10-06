package com.moneybags.account.security;
import com.moneybags.integration.CurrentActor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
@Component
public class Actor {
 public String id() { return CurrentActor.get().userId(); }
 public String cifId() { return CurrentActor.get().cifIds().stream().findFirst().orElse(null); }
 public boolean has(String role) { return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_"+role)); }
 public void require(String... roles) {
 for(String role:roles) if(has(role)) return;
 throw new com.moneybags.account.api.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"FORBIDDEN","Insufficient account authority");
 }
}
