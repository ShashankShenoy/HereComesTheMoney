package com.moneybags.statements;
import com.moneybags.iam.security.UserPrincipal;
public record Actor(String id,String type,String sessionId) {
 public static Actor from(UserPrincipal user) {
 return new Actor(user.userId(),switch(user.userType()){case "EMPLOYEE"->"STAFF";case "SERVICE"->"SYSTEM";default->"CUSTOMER";},user.sessionId());
 }
}
