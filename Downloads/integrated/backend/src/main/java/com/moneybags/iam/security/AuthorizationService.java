package com.moneybags.iam.security;
import com.moneybags.common.api.BusinessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.*;
/** Shared checks for later domain services. Permissions alone do not establish ownership. */
@Service
public class AuthorizationService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public AuthorizationService(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc;this.clock=clock; }
    public UserPrincipal current() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!(authentication.getPrincipal() instanceof UserPrincipal principal)) throw new BusinessException(HttpStatus.UNAUTHORIZED,"LOGIN_REQUIRED","Please sign in");
        return principal;
    }
    public void require(String permission) { if(!current().permissions().contains(permission))denied(); }
    public void requireOwnCif(String cifId) {
        var user=current();
        if(!"CUSTOMER".equals(user.userType())||!user.cifIds().contains(cifId))denied();
    }
    public void requireBranch(String permission,String branch) {
        require(permission);var now=OffsetDateTime.now(clock);
        Long count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM M01_IAM_USER_ROLE A JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID
            JOIN M01_IAM_ROLE_PERMISSION RP ON RP.ROLE_ID=R.ROLE_ID JOIN M01_IAM_PERMISSION P ON P.PERMISSION_ID=RP.PERMISSION_ID
            WHERE A.USER_ID=? AND P.PERMISSION_CODE=? AND A.STATUS='ACTIVE' AND R.STATUS='ACTIVE'
            AND A.VALID_FROM<=? AND (A.VALID_TO IS NULL OR A.VALID_TO>?)
            AND (A.SCOPE_TYPE='GLOBAL' OR (A.SCOPE_TYPE='BRANCH' AND A.SCOPE_REF=?))
            """,Long.class,current().userId(),permission,now,now,branch);
        if(count==null||count==0)denied();
    }
    public void requireDifferentMaker(String makerUserId) { if(current().userId().equals(makerUserId))throw new BusinessException(HttpStatus.FORBIDDEN,"SELF_APPROVAL","The maker cannot approve their own request"); }
    public void requireAmountAuthority(String authorityCode,String currency,BigDecimal amount) {
        var now=OffsetDateTime.now(clock);
        Long count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM M01_IAM_USER_ROLE A JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID
            JOIN M01_IAM_ROLE_AUTHORITY V ON V.ROLE_ID=R.ROLE_ID
            WHERE A.USER_ID=? AND A.STATUS='ACTIVE' AND R.STATUS='ACTIVE' AND A.VALID_FROM<=?
            AND (A.VALID_TO IS NULL OR A.VALID_TO>?) AND V.AUTHORITY_CODE=?
            AND (V.CURRENCY_CODE=? OR V.CURRENCY_CODE='*') AND V.MAX_AMOUNT>=?
            AND V.VALID_FROM<=? AND (V.VALID_TO IS NULL OR V.VALID_TO>?)
            """,Long.class,current().userId(),now,now,authorityCode,currency,amount,now,now);
        if(count==null||count==0)denied();
    }
    private static void denied() { throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","You do not have permission for this resource or action"); }
}
