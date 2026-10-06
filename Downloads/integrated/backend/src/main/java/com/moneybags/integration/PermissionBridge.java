package com.moneybags.integration;
import com.moneybags.iam.security.UserPrincipal;
import java.util.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
/** Explicit compatibility aliases; authentication alone grants no privileges. */
public final class PermissionBridge {
 private PermissionBridge() {}
 public static final Map<String,List<String>> ALIASES=Map.ofEntries(
 Map.entry("TXN_READ",List.of("SCOPE_m05.read")),
 Map.entry("TXN_POST",List.of("SCOPE_m05.transfer")),
 Map.entry("TXN_REVERSE",List.of("SCOPE_m05.reversal.request")),
 Map.entry("TXN_REVERSE_APPROVE",List.of("SCOPE_m05.reversal.approve")),
 Map.entry("GL_ADMIN",List.of("SCOPE_m05.admin")),
 Map.entry("GL_POST",List.of("SCOPE_m05.journal")),
 Map.entry("GL_RECONCILE",List.of("SCOPE_m05.reconcile")),
 Map.entry("GL_CLOSE",List.of("SCOPE_m05.close.request")),
 Map.entry("GL_CLOSE_APPROVE",List.of("SCOPE_m05.close.approve")),
 Map.entry("FEE_ASSESS",List.of("SCOPE_m05.fee")),
 Map.entry("ACCOUNT_OPEN",List.of("ROLE_ACCOUNT_OFFICER")),
 Map.entry("ACCOUNT_CONTROL",List.of("ROLE_ACCOUNT_OFFICER")),
 Map.entry("ACCOUNT_READ",List.of("ROLE_ACCOUNT_OFFICER")),
 Map.entry("ACCOUNT_APPROVE",List.of("ROLE_ACCOUNT_APPROVER")),
 Map.entry("PAYMENT_READ",List.of("SCOPE_M06_READ")),
 Map.entry("PAYMENT_CREATE",List.of("SCOPE_M06_PAYMENT_WRITE","SCOPE_M06_MAKER")),
 Map.entry("PAYMENT_APPROVE",List.of("SCOPE_M06_CHECKER")),
 Map.entry("PAYMENT_OPERATE",List.of("SCOPE_M06_OPERATIONS")),
 Map.entry("PAYMENT_RECONCILE",List.of("SCOPE_M06_RECONCILE")));
 public static List<SimpleGrantedAuthority> authorities(UserPrincipal user) {
 var result=new ArrayList<SimpleGrantedAuthority>();
 user.permissions().forEach(p->ALIASES.getOrDefault(p,List.of()).stream().filter(a->!a.startsWith("ROLE_ACCOUNT_")||!"CUSTOMER".equals(user.userType())).forEach(a->result.add(new SimpleGrantedAuthority(a))));
 if("SERVICE".equals(user.userType())) {
  if(user.permissions().contains("PAYMENT_INTERNAL"))for(String a:List.of("SCOPE_M06_INTERNAL","SCOPE_M06_RAIL","SCOPE_m05.payment"))result.add(new SimpleGrantedAuthority(a));
  if(user.permissions().contains("ACCOUNT_EVENTS"))result.add(new SimpleGrantedAuthority("SCOPE_m05.control"));
 }
 if("SERVICE".equals(user.userType())) user.permissions().stream().filter(p->p.endsWith("_INTERNAL")||p.startsWith("M06_")||p.equals("ACCOUNT_EVENTS")).forEach(p->result.add(new SimpleGrantedAuthority(p.equals("ACCOUNT_EVENTS")?"ROLE_"+p:"SCOPE_"+p)));
 return result;
 }
}
