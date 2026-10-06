package com.moneybags.integration;
import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.common.api.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
public final class CurrentActor {
 private CurrentActor() {}
 public static UserPrincipal get() {
 var auth=SecurityContextHolder.getContext().getAuthentication();
 if(auth!=null && auth.getPrincipal() instanceof UserPrincipal u) return u;
 throw new BusinessException(HttpStatus.UNAUTHORIZED,"LOGIN_REQUIRED","Please sign in");
 }
 public static void require(String permission) {
 if(!get().permissions().contains(permission)) throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","Permission required: "+permission);
 }
}
