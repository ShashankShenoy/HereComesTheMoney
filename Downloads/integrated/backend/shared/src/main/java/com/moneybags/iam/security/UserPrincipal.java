package com.moneybags.iam.security;
import java.util.List;
/** Safe authenticated identity; contains no credential, token, or personal-data fields. */
public record UserPrincipal(String userId,String username,String userType,String sessionId,Long entitlementVersion,List<String> roles,List<String> permissions,List<String> cifIds) implements java.security.Principal {
    @Override public String getName() { return userId; }
}
