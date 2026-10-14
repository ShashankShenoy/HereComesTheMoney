package com.moneybags.integration;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
/** Financial domain entry points require the customer's PIN even on alternate API/assistant routes. */
@Component
public class PaymentPinGuard {
 private final PaymentPinService pins;private static final String VERIFIED=PaymentPinGuard.class.getName()+".verified";
 public PaymentPinGuard(PaymentPinService pins){this.pins=pins;}
 public void requireForCustomer(){
  // Trusted non-request ledger orchestration has no customer session. HTTP
  // financial endpoints and assistant execution already require authentication.
  if(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()==null)return;
  var actor=CurrentActor.get();if(!"CUSTOMER".equals(actor.userType()))return;
  var attributes=RequestContextHolder.getRequestAttributes();
  if(attributes instanceof ServletRequestAttributes servlet){var request=servlet.getRequest();
   if(actor.sessionId().equals(request.getAttribute(VERIFIED)))return;
   pins.verify(request.getHeader("X-Payment-Pin"));request.setAttribute(VERIFIED,actor.sessionId());
  }else pins.verify(null);
 }
}
