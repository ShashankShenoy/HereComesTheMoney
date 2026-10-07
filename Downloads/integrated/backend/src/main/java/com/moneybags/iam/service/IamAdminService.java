package com.moneybags.iam.service;

import com.moneybags.common.api.*;
import com.moneybags.common.database.*;
import com.moneybags.iam.dto.IamDtos.*;
import com.moneybags.iam.dto.SessionResponse;
import com.moneybags.iam.model.*;
import com.moneybags.iam.repository.*;
import com.moneybags.iam.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static com.moneybags.iam.repository.IamRepository.map;

@Service
public class IamAdminService {
    private final IamAdminRepository db;private final IamRepository auth;private final IamGuard guard;
    private final PasswordEncoder passwords;private final Clock clock;private final JsonMapper json;
    public IamAdminService(IamAdminRepository db,IamRepository auth,IamGuard guard,PasswordEncoder passwords,Clock clock,JsonMapper json) {
        this.db=db;this.auth=auth;this.guard=guard;this.passwords=passwords;this.clock=clock;this.json=json;
    }
    private OffsetDateTime now(){return OffsetDateTime.now(clock);}
    private static String id(){return UUID.randomUUID().toString();}
    private static String blank(String s){return s==null||s.isBlank()?null:s.trim();}
    private static void bad(String message){throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message);}
    private static void conflict(String message){throw new BusinessException(HttpStatus.CONFLICT,"VERSION_CONFLICT",message);}
    private static BusinessException missing(String type){return new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND",type+" not found");}
    private static void dates(OffsetDateTime from,OffsetDateTime to){if(from==null||(to!=null&&!to.isAfter(from)))bad("Valid-to must be after valid-from");}
    public static void password(String value){if(value==null||value.length()<10||value.getBytes(StandardCharsets.UTF_8).length>72)bad("Password must contain at least 10 characters and at most 72 UTF-8 bytes");}
    public Map<String,Object> dashboard(UserPrincipal actor){
        guard.require(actor,"IAM_USER_READ");
        return map("users",db.count("SELECT COUNT(*) FROM M01_IAM_USER"),"activeUsers",db.count("SELECT COUNT(*) FROM M01_IAM_USER WHERE STATUS='ACTIVE'"),
            "roles",db.count("SELECT COUNT(*) FROM M01_IAM_ROLE WHERE STATUS='ACTIVE'"),"pendingRequests",db.count("SELECT COUNT(*) FROM M01_IAM_ACCESS_REQUEST WHERE STATUS='SUBMITTED'"),
            "activeSessions",db.count("SELECT COUNT(*) FROM M01_IAM_SESSION WHERE STATUS='ACTIVE' AND IDLE_EXPIRES_AT>? AND ABSOLUTE_EXPIRES_AT>?",now(),now()),
            "checkerSetupAvailable",checkerSetupAvailable());
    }
    public Map<String,Object> users(UserPrincipal actor,String search,String status,int page,int size){
        guard.require(actor,"IAM_USER_READ");paging(page,size);if(search.length()>120)bad("Search is too long");
        if(!status.isBlank()&&!Set.of("PENDING_ACTIVATION","ACTIVE","AUTH_LOCKED","SUSPENDED","DISABLED","CLOSED").contains(status))bad("Invalid user status");
        String where=" WHERE (? IS NULL OR UPPER(USERNAME) LIKE ?) AND (? IS NULL OR STATUS=?)";
        String term=blank(search),match=term==null?null:"%"+term.toUpperCase(Locale.ROOT)+"%",st=blank(status);
        return map("items",db.rows("SELECT * FROM M01_IAM_USER"+where+" ORDER BY CREATED_AT DESC,USER_ID OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",M01IamUserRow.class,term,match,st,st,page*size,size),
            "total",db.count("SELECT COUNT(*) FROM M01_IAM_USER"+where,term,match,st,st),"page",page,"size",size);
    }
    private static void paging(int page,int size){if(page<0||page>100000||size<1||size>100)bad("Page must be non-negative and size between 1 and 100");}
    public Map<String,Object> userDetail(UserPrincipal actor,String userId){
        guard.require(actor,"IAM_USER_READ");var u=db.user(userId,false);
        return map("user",u,"assignments",db.rows("SELECT * FROM M01_IAM_USER_ROLE WHERE USER_ID=? ORDER BY CREATED_AT DESC",M01IamUserRoleRow.class,userId),
            "dimensions",db.rows("SELECT S.* FROM M01_IAM_USER_ROLE_SCOPE S JOIN M01_IAM_USER_ROLE A ON A.ASSIGNMENT_ID=S.ASSIGNMENT_ID WHERE A.USER_ID=?",M01IamUserRoleScopeRow.class,userId),
            "links",db.rows("SELECT * FROM M01_IAM_CUSTOMER_LINK WHERE USER_ID=? ORDER BY VALID_FROM DESC",M01IamCustomerLinkRow.class,userId),
            "sessions",auth.sessions(userId).stream().map(s->new SessionResponse(s.sessionId(),s.clientId(),s.deviceRef(),s.status(),s.createdAt(),s.lastActivityAt(),s.idleExpiresAt(),s.absoluteExpiresAt(),s.sessionId().equals(actor.sessionId()))).toList());
    }
    @Transactional public M01IamUserRow createUser(UserPrincipal actor,CreateUser request){
        guard.require(actor,"IAM_USER_CREATE");return create(actor,request,"USER_CREATED");
    }
    private M01IamUserRow create(UserPrincipal actor,CreateUser request,String event){
        password(request.password());String employee=blank(request.employeeRef());
        if("EMPLOYEE".equals(request.userType())?(employee==null):(employee!=null))bad("Only employee users must have an employee reference");
        if(db.count("SELECT COUNT(*) FROM M01_IAM_USER WHERE UPPER(USERNAME)=UPPER(?)",request.username().trim())>0)
            throw new BusinessException(HttpStatus.CONFLICT,"USERNAME_EXISTS","Username already exists");
        String user=id();db.insert(SchemaTable.M01_IAM_USER,map("USER_ID",user,"USERNAME",request.username().trim(),"USER_TYPE",request.userType(),"EMPLOYEE_REF",employee,"STATUS","PENDING_ACTIVATION"));
        db.insert(SchemaTable.M01_IAM_CREDENTIAL,map("CREDENTIAL_ID",id(),"USER_ID",user,"PASSWORD_HASH",passwords.encode(request.password()),"HASH_SCHEME","BCRYPT","STATUS","ACTIVE"));
        event(actor,event,"IAM_USER",user,null,request.username());return db.user(user,false);
    }
    private boolean checkerSetupAvailable(){
        return db.count("SELECT COUNT(*) FROM M01_IAM_ACCESS_REQUEST WHERE STATUS='APPROVED'")==0&&
            db.count("SELECT COUNT(DISTINCT A.USER_ID) FROM M01_IAM_USER_ROLE A JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID JOIN M01_IAM_USER U ON U.USER_ID=A.USER_ID WHERE R.ROLE_CODE=? AND A.STATUS='ACTIVE' AND A.SCOPE_TYPE='GLOBAL' AND U.STATUS='ACTIVE' AND A.VALID_FROM<=? AND (A.VALID_TO IS NULL OR A.VALID_TO>?)",BankRolePolicy.CHECKER,now(),now())==0;
    }
    @Transactional public M01IamUserRow setupChecker(UserPrincipal actor,CreateUser request){
        guard.require(actor,"IAM_ACCESS_APPROVE");
        // Serialize setup on the one reserved administrator role, not on a racy count.
        var role=db.one("SELECT * FROM M01_IAM_ROLE WHERE ROLE_CODE=? FOR UPDATE",M01IamRoleRow.class,BankRolePolicy.CHECKER).orElseThrow(()->missing("Bank Checker role; run the role migration first"));
        if(!checkerSetupAvailable())throw new BusinessException(HttpStatus.CONFLICT,"SETUP_COMPLETE","One-time checker setup is already complete");
        if(!"EMPLOYEE".equals(request.userType()))bad("The initial checker must be an employee");
        var user=create(actor,request,"INITIAL_CHECKER_CREATED");
        db.jdbc().update("UPDATE M01_IAM_USER SET STATUS='ACTIVE',ROW_VERSION=ROW_VERSION+1,UPDATED_AT=? WHERE USER_ID=?",now(),user.userId());
        db.insert(SchemaTable.M01_IAM_USER_ROLE,map("ASSIGNMENT_ID",id(),"USER_ID",user.userId(),"ROLE_ID",role.roleId(),"SCOPE_TYPE","GLOBAL","STATUS","ACTIVE","VALID_FROM",now()));
        event(actor,"INITIAL_CHECKER_BOOTSTRAPPED","IAM_USER",user.userId(),null,"ONE_TIME_INITIAL_SETUP");return db.user(user.userId(),false);
    }
    @Transactional public M01IamUserRow changeStatus(UserPrincipal actor,String userId,StatusChange request){
        guard.require(actor,"IAM_USER_MANAGE");var user=db.user(userId,true);
        if(!Objects.equals(user.rowVersion(),request.rowVersion()))conflict("User changed; reload before saving");
        if(userId.equals(actor.userId())&&!"ACTIVE".equals(request.status()))bad("You cannot disable or lock your own administrator account");
        Map<String,Set<String>> allowed=Map.of("PENDING_ACTIVATION",Set.of("ACTIVE","DISABLED","CLOSED"),"ACTIVE",Set.of("AUTH_LOCKED","SUSPENDED","DISABLED","CLOSED"),
            "AUTH_LOCKED",Set.of("ACTIVE","SUSPENDED","DISABLED","CLOSED"),"SUSPENDED",Set.of("ACTIVE","DISABLED","CLOSED"),"DISABLED",Set.of("ACTIVE","CLOSED"),"CLOSED",Set.of());
        if(!allowed.get(user.status()).contains(request.status()))bad("This user status transition is not allowed");
        db.jdbc().update("UPDATE M01_IAM_USER SET STATUS=?,ROW_VERSION=ROW_VERSION+1,UPDATED_AT=? WHERE USER_ID=?",request.status(),now(),userId);
        if(!"ACTIVE".equals(request.status()))endSessions(userId,null);
        if("ACTIVE".equals(request.status()))auth.failedAttempt(userId,0,null);
        event(actor,"USER_STATUS_CHANGED","IAM_USER",userId,user.status(),request.status()+":"+request.reason());return db.user(userId,false);
    }
    @Transactional public void resetPassword(UserPrincipal actor,String userId,PasswordReset request){
        guard.require(actor,"IAM_USER_MANAGE");db.user(userId,true);password(request.password());
        updatePassword(userId,request.password());endSessions(userId,null);event(actor,"PASSWORD_RESET","IAM_USER",userId,null,"ADMIN_RESET");
    }
    @Transactional public void changePassword(UserPrincipal actor,PasswordChange request){
        var credential=auth.credential(actor.userId(),true).orElseThrow(()->missing("Credential"));password(request.newPassword());
        if(request.currentPassword().getBytes(StandardCharsets.UTF_8).length>72||!passwords.matches(request.currentPassword(),credential.passwordHash()))
            throw new BusinessException(HttpStatus.BAD_REQUEST,"PASSWORD_INCORRECT","Current password is incorrect");
        if(passwords.matches(request.newPassword(),credential.passwordHash()))bad("Choose a different new password");
        updatePassword(actor.userId(),request.newPassword());endSessions(actor.userId(),null);event(actor,"PASSWORD_CHANGED","IAM_USER",actor.userId(),null,"SELF_SERVICE");
    }
    @Transactional public void resetFactors(UserPrincipal actor,String userId,ReasonInput request){
        guard.require(actor,"IAM_USER_MANAGE");db.user(userId,true);
        if(actor.userId().equals(userId))bad("Another administrator must reset your lost authenticator");
        db.jdbc().update("UPDATE M01_IAM_AUTH_FACTOR SET STATUS='REVOKED',REVOKED_AT=? WHERE USER_ID=? AND STATUS<>'REVOKED'",now(),userId);
        endSessions(userId,null);event(actor,"MFA_ADMIN_RESET","IAM_USER",userId,null,request.reason());
    }
    private void updatePassword(String user,String value){db.jdbc().update("UPDATE M01_IAM_CREDENTIAL SET PASSWORD_HASH=?,HASH_SCHEME='BCRYPT',STATUS='ACTIVE',FAILED_ATTEMPTS=0,LOCKED_UNTIL=NULL,CHANGED_AT=? WHERE USER_ID=?",passwords.encode(value),now(),user);}
    private void endSessions(String user,String except){
        db.jdbc().update("UPDATE M01_IAM_REFRESH_TOKEN SET STATUS='REVOKED' WHERE STATUS='ACTIVE' AND SESSION_ID IN (SELECT SESSION_ID FROM M01_IAM_SESSION WHERE USER_ID=? AND (? IS NULL OR SESSION_ID<>?))",user,except,except);
        db.jdbc().update("UPDATE M01_IAM_SESSION SET STATUS='POLICY_TERMINATED',ENDED_AT=? WHERE USER_ID=? AND STATUS='ACTIVE' AND (? IS NULL OR SESSION_ID<>?)",now(),user,except,except);
    }
    @Transactional public void revokeSession(UserPrincipal actor,String userId,String sessionId){
        guard.require(actor,"IAM_USER_MANAGE");var session=auth.session(sessionId,true).orElseThrow(()->missing("Session"));
        if(!session.userId().equals(userId))throw missing("Session");
        auth.endSession(sessionId,"REVOKED",now());auth.revokeRefresh(sessionId);event(actor,"ADMIN_SESSION_REVOKED","IAM_SESSION",sessionId,null,"REVOKED");
    }
    public List<M01IamPermissionRow> permissions(UserPrincipal actor){guard.require(actor,"IAM_USER_READ");return db.rows("SELECT * FROM M01_IAM_PERMISSION ORDER BY PERMISSION_CODE",M01IamPermissionRow.class);}
    @Transactional public M01IamPermissionRow createPermission(UserPrincipal actor,PermissionInput r){
        guard.require(actor,"IAM_ROLE_MANAGE");if(r.permissionCode().startsWith("IAM_")||r.permissionCode().startsWith("SYSTEM_"))bad("Core administration permissions are reserved");
        String pid=id();db.insert(SchemaTable.M01_IAM_PERMISSION,map("PERMISSION_ID",pid,"PERMISSION_CODE",r.permissionCode(),"RESOURCE_CODE",r.resourceCode(),"ACTION_CODE",r.actionCode(),"STEP_UP_REQUIRED",r.stepUpRequired()?"Y":"N"));
        db.jdbc().update("INSERT INTO M01_IAM_ROLE_PERMISSION(ROLE_ID,PERMISSION_ID) SELECT ROLE_ID,? FROM M01_IAM_ROLE WHERE ROLE_CODE='BANK_ADMIN'",pid);
        db.jdbc().update("UPDATE M01_IAM_USER SET ENTITLEMENT_VERSION=ENTITLEMENT_VERSION+1,ROW_VERSION=ROW_VERSION+1,UPDATED_AT=? WHERE USER_ID IN (SELECT A.USER_ID FROM M01_IAM_USER_ROLE A JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID WHERE R.ROLE_CODE='BANK_ADMIN' AND A.STATUS='ACTIVE')",now());
        event(actor,"PERMISSION_CREATED","IAM_PERMISSION",pid,null,r.permissionCode());return db.one("SELECT * FROM M01_IAM_PERMISSION WHERE PERMISSION_ID=?",M01IamPermissionRow.class,pid).orElseThrow();
    }
    public List<M01IamRoleRow> roles(UserPrincipal actor){guard.require(actor,"IAM_USER_READ");return db.rows("SELECT * FROM M01_IAM_ROLE ORDER BY ROLE_CODE",M01IamRoleRow.class);}
    private M01IamRoleRow role(String id,boolean lock){return db.one("SELECT * FROM M01_IAM_ROLE WHERE ROLE_ID=?"+(lock?" FOR UPDATE":""),M01IamRoleRow.class,id).orElseThrow(()->missing("Role"));}
    public Map<String,Object> roleDetail(UserPrincipal actor,String id){guard.require(actor,"IAM_USER_READ");return map("role",role(id,false),"permissions",db.rows("SELECT P.* FROM M01_IAM_PERMISSION P JOIN M01_IAM_ROLE_PERMISSION RP ON RP.PERMISSION_ID=P.PERMISSION_ID WHERE RP.ROLE_ID=? ORDER BY P.PERMISSION_CODE",M01IamPermissionRow.class,id),"authorities",db.rows("SELECT * FROM M01_IAM_ROLE_AUTHORITY WHERE ROLE_ID=? ORDER BY VALID_FROM DESC",M01IamRoleAuthorityRow.class,id));}
    private void permissionIds(List<String> ids){if(new HashSet<>(ids).size()!=ids.size())bad("Duplicate permissions");for(String pid:ids)if(db.count("SELECT COUNT(*) FROM M01_IAM_PERMISSION WHERE PERMISSION_ID=?",pid)!=1)bad("Unknown permission");}
    private void setPermissions(String role,List<String> ids){db.jdbc().update("DELETE FROM M01_IAM_ROLE_PERMISSION WHERE ROLE_ID=?",role);for(String pid:ids)db.insert(SchemaTable.M01_IAM_ROLE_PERMISSION,map("ROLE_ID",role,"PERMISSION_ID",pid));}
    @Transactional public M01IamRoleRow createRole(UserPrincipal actor,RoleInput r){
        guard.require(actor,"IAM_ROLE_MANAGE");if(Set.of("BANK_ADMIN",BankRolePolicy.CHECKER).contains(r.roleCode()))bad("The bank administration roles are reserved");permissionIds(r.permissionIds());
        String rid=id();db.insert(SchemaTable.M01_IAM_ROLE,map("ROLE_ID",rid,"ROLE_CODE",r.roleCode(),"DISPLAY_NAME",r.displayName(),"SENSITIVE_FLAG",r.sensitive()?"Y":"N"));setPermissions(rid,r.permissionIds());
        event(actor,"ROLE_CREATED","IAM_ROLE",rid,null,r.roleCode());return role(rid,false);
    }
    @Transactional public M01IamRoleRow updateRole(UserPrincipal actor,String rid,RoleUpdate r){
        guard.require(actor,"IAM_ROLE_MANAGE");var old=role(rid,true);if(Set.of("BANK_ADMIN",BankRolePolicy.CHECKER).contains(old.roleCode()))bad("The bank administration roles cannot be edited or retired");
        if(!old.rowVersion().equals(r.rowVersion()))conflict("Role changed; reload before saving");permissionIds(r.permissionIds());
        db.jdbc().update("UPDATE M01_IAM_ROLE SET DISPLAY_NAME=?,SENSITIVE_FLAG=?,STATUS=?,ROW_VERSION=ROW_VERSION+1 WHERE ROLE_ID=?",r.displayName(),r.sensitive()?"Y":"N",r.status(),rid);
        setPermissions(rid,r.permissionIds());bumpRoleUsers(rid);event(actor,"ROLE_UPDATED","IAM_ROLE",rid,old.status(),r.status());return role(rid,false);
    }
    private void bumpRoleUsers(String rid){db.jdbc().update("UPDATE M01_IAM_USER SET ENTITLEMENT_VERSION=ENTITLEMENT_VERSION+1,ROW_VERSION=ROW_VERSION+1,UPDATED_AT=? WHERE USER_ID IN (SELECT USER_ID FROM M01_IAM_USER_ROLE WHERE ROLE_ID=? AND STATUS='ACTIVE')",now(),rid);}
    @Transactional public M01IamRoleAuthorityRow authority(UserPrincipal actor,String rid,AuthorityInput r){
        guard.require(actor,"IAM_ROLE_MANAGE");role(rid,true);dates(r.validFrom(),r.validTo());if(r.maxAmount()==null&&r.maxRatePct()==null)bad("Enter an amount or rate limit");
        String aid=id();db.insert(SchemaTable.M01_IAM_ROLE_AUTHORITY,map("AUTHORITY_ID",aid,"ROLE_ID",rid,"AUTHORITY_CODE",r.authorityCode(),"CURRENCY_CODE",r.currencyCode(),"MAX_AMOUNT",r.maxAmount(),"MAX_RATE_PCT",r.maxRatePct(),"VALID_FROM",r.validFrom(),"VALID_TO",r.validTo()));
        bumpRoleUsers(rid);event(actor,"ROLE_AUTHORITY_CREATED","IAM_ROLE",rid,null,r.authorityCode());return db.one("SELECT * FROM M01_IAM_ROLE_AUTHORITY WHERE AUTHORITY_ID=?",M01IamRoleAuthorityRow.class,aid).orElseThrow();
    }
    @Transactional public void expireAuthority(UserPrincipal actor,String rid,String aid){
        guard.require(actor,"IAM_ROLE_MANAGE");role(rid,true);var a=db.one("SELECT * FROM M01_IAM_ROLE_AUTHORITY WHERE ROLE_ID=? AND AUTHORITY_ID=? FOR UPDATE",M01IamRoleAuthorityRow.class,rid,aid).orElseThrow(()->missing("Authority"));
        if(!now().isAfter(a.validFrom())){db.jdbc().update("DELETE FROM M01_IAM_ROLE_AUTHORITY WHERE AUTHORITY_ID=?",aid);}else db.jdbc().update("UPDATE M01_IAM_ROLE_AUTHORITY SET VALID_TO=? WHERE AUTHORITY_ID=?",now(),aid);
        bumpRoleUsers(rid);event(actor,"ROLE_AUTHORITY_ENDED","IAM_ROLE",rid,null,aid);
    }
    public List<M01IamAccessRequestRow> requests(UserPrincipal actor){guard.require(actor,"IAM_USER_READ");return db.rows("SELECT * FROM M01_IAM_ACCESS_REQUEST ORDER BY CREATED_AT DESC FETCH FIRST 200 ROWS ONLY",M01IamAccessRequestRow.class);}
    public Map<String,Object> requestDetail(UserPrincipal actor,String requestId){guard.require(actor,"IAM_USER_READ");return map("request",request(requestId,false),"dimensions",db.rows("SELECT * FROM M01_IAM_ACCESS_REQUEST_SCOPE WHERE REQUEST_ID=?",M01IamAccessRequestScopeRow.class,requestId));}
    private M01IamAccessRequestRow request(String requestId,boolean lock){return db.one("SELECT * FROM M01_IAM_ACCESS_REQUEST WHERE REQUEST_ID=?"+(lock?" FOR UPDATE":""),M01IamAccessRequestRow.class,requestId).orElseThrow(()->missing("Access request"));}
    @Transactional public M01IamAccessRequestRow submitAccess(UserPrincipal actor,AccessInput r){
        guard.require(actor,"IAM_ACCESS_MANAGE");var target=db.user(r.targetUserId(),true);var role=role(r.roleId(),false);dates(r.validFrom(),r.validTo());
        if(r.validTo()!=null&&!r.validTo().isAfter(now()))bad("Access validity already ended");
        if(!"ACTIVE".equals(role.status())||"CLOSED".equals(target.status()))bad("Role must be active and user must not be closed");
        String scopeRef=blank(r.scopeRef());if(Set.of("BRANCH","REGION").contains(r.scopeType())?(scopeRef==null):(scopeRef!=null))bad("Branch and region scopes require a reference; self and global scopes must have none");
        if(new HashSet<>(r.dimensions()).size()!=r.dimensions().size())bad("Duplicate scope dimensions");
        if("SELF".equals(r.scopeType())&&!"CUSTOMER".equals(target.userType()))bad("SELF scope is reserved for customer users");
        String assignment=blank(r.targetAssignmentId());
        if("GRANT".equals(r.changeType())){if(assignment!=null)bad("Grant must not reference an assignment");}
        else {
            if(assignment==null)bad("Revocation requires an assignment");
            var a=db.one("SELECT * FROM M01_IAM_USER_ROLE WHERE ASSIGNMENT_ID=?",M01IamUserRoleRow.class,assignment).orElseThrow(()->missing("Assignment"));
            if(!a.userId().equals(r.targetUserId())||!a.roleId().equals(r.roleId())||!a.scopeType().equals(r.scopeType())||!Objects.equals(a.scopeRef(),scopeRef)||!a.validFrom().isEqual(r.validFrom())||!Objects.equals(a.validTo(),r.validTo())||!"ACTIVE".equals(a.status()))bad("Revocation must match the active assignment exactly");
            var dims=db.rows("SELECT * FROM M01_IAM_USER_ROLE_SCOPE WHERE ASSIGNMENT_ID=?",M01IamUserRoleScopeRow.class,assignment).stream().map(d->new Dimension(d.dimensionCode(),d.scopeValue())).toList();
            if(!new HashSet<>(dims).equals(new HashSet<>(r.dimensions())))bad("Revocation dimensions must match the assignment");
        }
        String req=id();db.insert(SchemaTable.M01_IAM_ACCESS_REQUEST,map("REQUEST_ID",req,"TARGET_USER_ID",r.targetUserId(),"ROLE_ID",r.roleId(),"CHANGE_TYPE",r.changeType(),"TARGET_ASSIGNMENT_ID",assignment,"SCOPE_TYPE",r.scopeType(),"SCOPE_REF",scopeRef,"VALID_FROM",r.validFrom(),"VALID_TO",r.validTo(),"REASON",r.reason(),"STATUS","SUBMITTED","MAKER_USER_ID",actor.userId(),"MAKER_SESSION_ID",actor.sessionId(),"CORRELATION_ID",Correlation.current()));
        for(var d:r.dimensions())db.insert(SchemaTable.M01_IAM_ACCESS_REQUEST_SCOPE,map("REQUEST_ID",req,"DIMENSION_CODE",d.dimensionCode(),"SCOPE_VALUE",d.scopeValue()));
        event(actor,"ACCESS_REQUEST_SUBMITTED","IAM_ACCESS_REQUEST",req,null,r.changeType());return request(req,false);
    }
    @Transactional(noRollbackFor=BusinessException.class) public M01IamAccessRequestRow decide(UserPrincipal actor,String reqId,Decision r){
        guard.require(actor,"IAM_ACCESS_APPROVE");var req=request(reqId,true);
        if(req.makerUserId().equals(actor.userId()))throw new BusinessException(HttpStatus.FORBIDDEN,"MAKER_CHECKER_SEPARATION","A different user must review this request");
        if(req.targetUserId().equals(actor.userId()))throw new BusinessException(HttpStatus.FORBIDDEN,"SELF_APPROVAL","You cannot approve or reject your own access change");
        if(!"SUBMITTED".equals(req.status())||!req.rowVersion().equals(r.rowVersion()))conflict("Request was already decided or changed");
        if(req.validTo()!=null&&!req.validTo().isAfter(now())) {
            db.jdbc().update("UPDATE M01_IAM_ACCESS_REQUEST SET STATUS='EXPIRED',DECIDED_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE REQUEST_ID=?",now(),reqId);
            event(actor,"ACCESS_REQUEST_EXPIRED","IAM_ACCESS_REQUEST",reqId,req.status(),"EXPIRED");
            throw new BusinessException(HttpStatus.CONFLICT,"REQUEST_EXPIRED","The request validity ended; submit a new request");
        }
        var target=db.user(req.targetUserId(),true);var role=role(req.roleId(),false);
        if("APPROVED".equals(r.decision())){
            if(req.validTo()!=null&&!req.validTo().isAfter(now()))bad("The requested validity already ended");
            if(!"ACTIVE".equals(role.status())||!"ACTIVE".equals(target.status()))bad("Activate the user and role before approving access");
            if("GRANT".equals(req.changeType())){
                // User row lock serializes overlapping grants for this identity.
                long overlaps=db.count("SELECT COUNT(*) FROM M01_IAM_USER_ROLE WHERE USER_ID=? AND ROLE_ID=? AND STATUS='ACTIVE' AND SCOPE_TYPE=? AND ((SCOPE_REF IS NULL AND ? IS NULL) OR SCOPE_REF=?) AND (? IS NULL OR VALID_FROM<?) AND (VALID_TO IS NULL OR VALID_TO>?)",req.targetUserId(),req.roleId(),req.scopeType(),req.scopeRef(),req.scopeRef(),req.validTo(),req.validTo(),req.validFrom());
                if(overlaps>0)throw new BusinessException(HttpStatus.CONFLICT,"OVERLAPPING_ACCESS","An overlapping active assignment already exists");
                String assignment=id();db.insert(SchemaTable.M01_IAM_USER_ROLE,map("ASSIGNMENT_ID",assignment,"USER_ID",req.targetUserId(),"ROLE_ID",req.roleId(),"SCOPE_TYPE",req.scopeType(),"SCOPE_REF",req.scopeRef(),"STATUS","ACTIVE","VALID_FROM",req.validFrom(),"VALID_TO",req.validTo(),"APPROVED_REQUEST_ID",reqId));
                for(var d:db.rows("SELECT * FROM M01_IAM_ACCESS_REQUEST_SCOPE WHERE REQUEST_ID=?",M01IamAccessRequestScopeRow.class,reqId))db.insert(SchemaTable.M01_IAM_USER_ROLE_SCOPE,map("ASSIGNMENT_ID",assignment,"DIMENSION_CODE",d.dimensionCode(),"SCOPE_VALUE",d.scopeValue()));
            }else {
                if(req.targetUserId().equals(actor.userId())||req.targetUserId().equals(req.makerUserId())&&"BANK_ADMIN".equals(role.roleCode()))bad("Another administrator must request removal of administrator access");
                int changed=db.jdbc().update("UPDATE M01_IAM_USER_ROLE SET STATUS='REVOKED' WHERE ASSIGNMENT_ID=? AND USER_ID=? AND ROLE_ID=? AND STATUS='ACTIVE'",req.targetAssignmentId(),req.targetUserId(),req.roleId());
                if(changed!=1)conflict("Assignment already ended");
            }
            db.jdbc().update("UPDATE M01_IAM_USER SET ENTITLEMENT_VERSION=ENTITLEMENT_VERSION+1,ROW_VERSION=ROW_VERSION+1,UPDATED_AT=? WHERE USER_ID=?",now(),req.targetUserId());
        }
        db.jdbc().update("UPDATE M01_IAM_ACCESS_REQUEST SET STATUS=?,CHECKER_USER_ID=?,CHECKER_SESSION_ID=?,DECIDED_AT=?,ROW_VERSION=ROW_VERSION+1 WHERE REQUEST_ID=?",r.decision(),actor.userId(),actor.sessionId(),now(),reqId);
        event(actor,"ACCESS_REQUEST_"+r.decision(),"IAM_ACCESS_REQUEST",reqId,req.status(),r.decision(),req.makerUserId(),actor.userId());return request(reqId,false);
    }
    @Transactional public M01IamCustomerLinkRow link(UserPrincipal actor,String userId,LinkInput r){
        guard.require(actor,"IAM_USER_MANAGE");var user=db.user(userId,true);if(!"CUSTOMER".equals(user.userType()))bad("Only customer users can be linked to a CIF");
        if(db.count("SELECT COUNT(*) FROM M02_CIF_CUSTOMER WHERE CIF_ID=? AND STATUS='ACTIVE'",r.cifId())!=1)bad("Select an existing active CIF from module 2");
        if(db.count("SELECT COUNT(*) FROM M01_IAM_CUSTOMER_LINK WHERE USER_ID=? AND CIF_ID=? AND RELATIONSHIP_TYPE=? AND STATUS='ACTIVE'",userId,r.cifId(),r.relationshipType())>0)bad("This active customer link already exists");
        String lid=id();db.insert(SchemaTable.M01_IAM_CUSTOMER_LINK,map("LINK_ID",lid,"USER_ID",userId,"CIF_ID",r.cifId(),"RELATIONSHIP_TYPE",r.relationshipType(),"VALID_FROM",now()));
        bumpUser(userId);event(actor,"CUSTOMER_LINK_CREATED","IAM_USER",userId,null,r.cifId());return db.one("SELECT * FROM M01_IAM_CUSTOMER_LINK WHERE LINK_ID=?",M01IamCustomerLinkRow.class,lid).orElseThrow();
    }
    @Transactional public void revokeLink(UserPrincipal actor,String userId,String lid){guard.require(actor,"IAM_USER_MANAGE");db.user(userId,true);int n=db.jdbc().update("UPDATE M01_IAM_CUSTOMER_LINK SET STATUS='REVOKED' WHERE LINK_ID=? AND USER_ID=? AND STATUS='ACTIVE'",lid,userId);if(n!=1)throw missing("Active link");bumpUser(userId);event(actor,"CUSTOMER_LINK_REVOKED","IAM_USER",userId,null,lid);}
    private void bumpUser(String user){db.jdbc().update("UPDATE M01_IAM_USER SET ENTITLEMENT_VERSION=ENTITLEMENT_VERSION+1,ROW_VERSION=ROW_VERSION+1,UPDATED_AT=? WHERE USER_ID=?",now(),user);}
    public List<M01IamAuditEventRow> audits(UserPrincipal actor,String search){guard.require(actor,"IAM_AUDIT_READ");if(search.length()>120)bad("Search is too long");String term=blank(search);return db.rows("SELECT * FROM M01_IAM_AUDIT_EVENT WHERE (? IS NULL OR UPPER(EVENT_TYPE) LIKE ? OR RESOURCE_ID=?) ORDER BY OCCURRED_AT DESC FETCH FIRST 200 ROWS ONLY",M01IamAuditEventRow.class,term,term==null?null:"%"+term.toUpperCase(Locale.ROOT)+"%",term);}
    public List<Map<String,Object>> outbox(UserPrincipal actor){guard.require(actor,"IAM_AUDIT_READ");return db.jdbc().query("SELECT EVENT_ID,EVENT_TYPE,AGGREGATE_TYPE,AGGREGATE_ID,OCCURRED_AT,PUBLISHED_AT,ATTEMPT_COUNT FROM M01_IAM_OUTBOX_EVENT ORDER BY OCCURRED_AT DESC FETCH FIRST 100 ROWS ONLY",(rs,n)->map("eventId",rs.getString(1),"eventType",rs.getString(2),"aggregateType",rs.getString(3),"aggregateId",rs.getString(4),"occurredAt",rs.getObject(5,OffsetDateTime.class),"publishedAt",rs.getObject(6,OffsetDateTime.class),"attemptCount",rs.getInt(7)));}
    public List<M01IamMenuItemRow> menus(UserPrincipal actor,boolean all){
        if(all)guard.require(actor,"IAM_ROLE_MANAGE");
        var list=db.rows("SELECT * FROM M01_IAM_MENU_ITEM WHERE CLIENT_ID='MONEYBAGS_WEB' ORDER BY SORT_ORDER,MENU_ID",M01IamMenuItemRow.class);
        if(all)return list;
        Set<String> permitted=new HashSet<>();for(var p:db.rows("SELECT * FROM M01_IAM_PERMISSION",M01IamPermissionRow.class))if(actor.permissions().contains(p.permissionCode()))permitted.add(p.permissionId());
        return list.stream().filter(m->"ACTIVE".equals(m.status())&&(m.requiredPermissionId()==null||permitted.contains(m.requiredPermissionId()))).toList();
    }
    @Transactional public M01IamMenuItemRow menu(UserPrincipal actor,String mid,MenuInput r){
        guard.require(actor,"IAM_ROLE_MANAGE");String parent=blank(r.parentMenuId()),permission=blank(r.requiredPermissionId());
        if(permission!=null)permissionIds(List.of(permission));
        if(parent!=null){var p=db.one("SELECT * FROM M01_IAM_MENU_ITEM WHERE MENU_ID=?",M01IamMenuItemRow.class,parent).orElseThrow(()->missing("Parent menu"));if(!p.clientId().equals(r.clientId()))bad("Parent must belong to the same client");Set<String> visited=new HashSet<>();String cursor=parent;while(cursor!=null){if(cursor.equals(mid)||!visited.add(cursor))bad("Menu hierarchy cannot contain a cycle");cursor=db.one("SELECT * FROM M01_IAM_MENU_ITEM WHERE MENU_ID=?",M01IamMenuItemRow.class,cursor).orElseThrow().parentMenuId();}}
        if(mid==null){mid=id();db.insert(SchemaTable.M01_IAM_MENU_ITEM,map("MENU_ID",mid,"CLIENT_ID",r.clientId(),"PARENT_MENU_ID",parent,"ROUTE_PATH",r.routePath(),"LABEL_KEY",r.labelKey(),"SORT_ORDER",r.sortOrder(),"REQUIRED_PERMISSION_ID",permission,"STATUS",r.status()));}
        else{if(db.jdbc().update("UPDATE M01_IAM_MENU_ITEM SET CLIENT_ID=?,PARENT_MENU_ID=?,ROUTE_PATH=?,LABEL_KEY=?,SORT_ORDER=?,REQUIRED_PERMISSION_ID=?,STATUS=? WHERE MENU_ID=?",r.clientId(),parent,r.routePath(),r.labelKey(),r.sortOrder(),permission,r.status(),mid)!=1)throw missing("Menu");}
        event(actor,"MENU_SAVED","IAM_MENU",mid,null,r.routePath());return db.one("SELECT * FROM M01_IAM_MENU_ITEM WHERE MENU_ID=?",M01IamMenuItemRow.class,mid).orElseThrow();
    }
    private void event(UserPrincipal actor,String type,String resource,String resourceId,Object before,Object after){event(actor,type,resource,resourceId,before,after,null,null);}
    private void event(UserPrincipal actor,String type,String resource,String resourceId,Object before,Object after,String maker,String checker){
        db.insert(SchemaTable.M01_IAM_AUDIT_EVENT,map("AUDIT_ID",id(),"EVENT_TYPE",type,"ACTOR_USER_ID",actor.userId(),"SESSION_ID",actor.sessionId(),"RESOURCE_TYPE",resource,"RESOURCE_ID",resourceId,"RESULT","SUCCESS","MAKER_USER_ID",maker,"CHECKER_USER_ID",checker,"BEFORE_STATE_HASH",before==null?null:LoginService.hash(before.toString()),"AFTER_STATE_HASH",after==null?null:LoginService.hash(after.toString()),"CORRELATION_ID",Correlation.current(),"OCCURRED_AT",now()));
        db.insert(SchemaTable.M01_IAM_OUTBOX_EVENT,map("EVENT_ID",id(),"EVENT_TYPE",type,"SCHEMA_VERSION",1,"AGGREGATE_TYPE",resource,"AGGREGATE_ID",resourceId,"CORRELATION_ID",Correlation.current(),"PAYLOAD",json.writeValueAsString(map("type",type,"resourceId",resourceId,"actorUserId",actor.userId())),"OCCURRED_AT",now()));
    }
}
