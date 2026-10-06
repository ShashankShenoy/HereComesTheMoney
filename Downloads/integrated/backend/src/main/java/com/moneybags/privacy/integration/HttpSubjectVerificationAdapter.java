package com.moneybags.privacy.integration;
import com.moneybags.integration.CurrentActor;
import com.moneybags.privacy.consent.SubjectVerificationPort;
import org.springframework.stereotype.Component;
@Component
public class HttpSubjectVerificationAdapter implements SubjectVerificationPort {
 @Override public VerificationResult verify(String service,String subject,String actor,String channel,String correlation) {
 var user=CurrentActor.get();
 boolean allowed=user.userId().equals(actor) && java.util.Set.of("MB_CIF","CIF","M02").contains(service) && user.cifIds().contains(subject);
 return new VerificationResult(allowed,allowed?"SESSION":null,allowed?user.sessionId():null);
 }
}
