package com.moneybags.iam.service;

import com.moneybags.common.api.*;
import com.moneybags.iam.dto.IamDtos.AuthorizationInput;
import com.moneybags.iam.model.*;
import com.moneybags.iam.repository.*;
import com.moneybags.iam.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;

@Service
public class AccessDecisionService {
    public record Result(boolean allowed,String reason,String assignmentId) {}
    /** Directory filtering uses the same policy as writes, without per-row audit noise. */
    @Transactional(readOnly=true) public boolean allowed(UserPrincipal user,AuthorizationInput request){return check(user,request).allowed();}
    private final IamAdminRepository db;private final IamRepository auth;private final Clock clock;
    public AccessDecisionService(IamAdminRepository db,IamRepository auth,Clock clock){this.db=db;this.auth=auth;this.clock=clock;}
    @Transactional public Result evaluate(UserPrincipal user,AuthorizationInput request){
        Result result=check(user,request);auth.audit("AUTHORIZATION_CHECK",user.userId(),user.sessionId(),request.permissionCode(),result.allowed()?"SUCCESS":"DENIED",Correlation.current(),OffsetDateTime.now(clock));return result;
    }
    @Transactional(noRollbackFor=BusinessException.class) public void require(UserPrincipal user,AuthorizationInput context){Result r=evaluate(user,context);if(!r.allowed())throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN",r.reason());}
    private Result check(UserPrincipal user,AuthorizationInput context){
        if(context.makerUserId()!=null&&context.makerUserId().equals(user.userId()))return deny("Maker and checker must be different users");
        if((context.amount()!=null||context.ratePct()!=null)&&(context.authorityCode()==null||context.authorityCode().isBlank()))return deny("An authority code is required for an amount or rate decision");
        var permission=db.one("SELECT * FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE=?",M01IamPermissionRow.class,context.permissionCode());
        if(permission.isEmpty())return deny("Permission is not configured");
        var now=OffsetDateTime.now(clock);var session=auth.session(user.sessionId(),false);
        if(session.isEmpty()||!session.get().userId().equals(user.userId())||!"ACTIVE".equals(session.get().status())||!session.get().idleExpiresAt().isAfter(now)||!session.get().absoluteExpiresAt().isAfter(now)||!"ACTIVE".equals(db.user(user.userId(),false).status()))return deny("A valid active user session is required");
        if("Y".equals(permission.get().stepUpRequired())&&!MfaService.fresh(session.get().authLevel(),clock.instant()))return deny("Fresh MFA step-up is required");
        var assignments=db.rows("""
            SELECT A.* FROM M01_IAM_USER_ROLE A JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID
            JOIN M01_IAM_ROLE_PERMISSION RP ON RP.ROLE_ID=R.ROLE_ID
            WHERE A.USER_ID=? AND A.STATUS='ACTIVE' AND R.STATUS='ACTIVE' AND RP.PERMISSION_ID=?
            AND A.VALID_FROM<=? AND (A.VALID_TO IS NULL OR A.VALID_TO>?)
            """,M01IamUserRoleRow.class,user.userId(),permission.get().permissionId(),now,now);
        for(var a:assignments){
            boolean scope=switch(a.scopeType()){
                case "GLOBAL"->true;case "BRANCH"->a.scopeRef().equals(context.branchRef());case "REGION"->a.scopeRef().equals(context.regionRef());
                case "SELF"->"CUSTOMER".equals(user.userType())&&context.cifId()!=null&&auth.cifIds(user.userId(),now).contains(context.cifId());default->false;};
            if(!scope)continue;
            var dims=db.rows("SELECT * FROM M01_IAM_USER_ROLE_SCOPE WHERE ASSIGNMENT_ID=?",M01IamUserRoleScopeRow.class,a.assignmentId());
            boolean product=dims.stream().noneMatch(d->"PRODUCT_TYPE".equals(d.dimensionCode()))||dims.stream().anyMatch(d->"PRODUCT_TYPE".equals(d.dimensionCode())&&d.scopeValue().equals(context.productType()));
            boolean currency=dims.stream().noneMatch(d->"CURRENCY".equals(d.dimensionCode()))||dims.stream().anyMatch(d->"CURRENCY".equals(d.dimensionCode())&&d.scopeValue().equals(context.currencyCode()));
            if(!product||!currency)continue;
            if(context.authorityCode()!=null){
                boolean within=db.rows("SELECT * FROM M01_IAM_ROLE_AUTHORITY WHERE ROLE_ID=? AND AUTHORITY_CODE=? AND VALID_FROM<=? AND (VALID_TO IS NULL OR VALID_TO>?) AND (CURRENCY_CODE='*' OR CURRENCY_CODE=?)",M01IamRoleAuthorityRow.class,a.roleId(),context.authorityCode(),now,now,context.currencyCode()).stream().anyMatch(limit->
                    (context.amount()==null||(limit.maxAmount()!=null&&limit.maxAmount().compareTo(context.amount())>=0))&&
                    (context.ratePct()==null||(limit.maxRatePct()!=null&&limit.maxRatePct().compareTo(context.ratePct())>=0)));
                if(!within)continue;
            }
            return new Result(true,"Permission, scope, and authority match",a.assignmentId());
        }
        return deny("No active assignment covers the requested permission, scope, and authority");
    }
    private static Result deny(String reason){return new Result(false,reason,null);}
}
