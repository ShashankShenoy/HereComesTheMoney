package com.moneybags.iam.security;

import com.moneybags.common.api.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;

/** IAM administration spans the organisation: a branch assignment cannot administer all users. */
@Component
public class IamGuard {
    private final JdbcTemplate jdbc;private final Clock clock;
    public IamGuard(JdbcTemplate jdbc,Clock clock) {this.jdbc=jdbc;this.clock=clock;}
    public void require(UserPrincipal user,String permission) {
        OffsetDateTime now=OffsetDateTime.now(clock);
        Long n=jdbc.queryForObject("""
            SELECT COUNT(*) FROM M01_IAM_USER_ROLE A
            JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID
            JOIN M01_IAM_ROLE_PERMISSION RP ON RP.ROLE_ID=R.ROLE_ID
            JOIN M01_IAM_PERMISSION P ON P.PERMISSION_ID=RP.PERMISSION_ID
            JOIN M01_IAM_USER U ON U.USER_ID=A.USER_ID
            WHERE A.USER_ID=? AND A.STATUS='ACTIVE' AND R.STATUS='ACTIVE' AND U.STATUS='ACTIVE'
            AND A.SCOPE_TYPE='GLOBAL' AND A.VALID_FROM<=? AND (A.VALID_TO IS NULL OR A.VALID_TO>?)
            AND NOT EXISTS (SELECT 1 FROM M01_IAM_USER_ROLE_SCOPE S WHERE S.ASSIGNMENT_ID=A.ASSIGNMENT_ID)
            AND P.PERMISSION_CODE=?
            """,Long.class,user.userId(),now,now,permission);
        if(n==null||n==0)throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","Global "+permission+" permission is required");
        Long stepUp=jdbc.queryForObject("SELECT COUNT(*) FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE=? AND STEP_UP_REQUIRED='Y'",Long.class,permission);
        if(stepUp!=null&&stepUp>0) {
            String level=jdbc.queryForObject("SELECT AUTH_LEVEL FROM M01_IAM_SESSION WHERE SESSION_ID=?",String.class,user.sessionId());
            if(!com.moneybags.iam.service.MfaService.fresh(level,clock.instant()))throw new BusinessException(HttpStatus.FORBIDDEN,"STEP_UP_REQUIRED","Verify a fresh authenticator code in My security");
        }
    }
}
