package com.moneybags.iam.service;

import com.moneybags.common.api.BusinessException;
import com.moneybags.common.database.SchemaRepository;
import com.moneybags.common.database.SchemaTable;
import com.moneybags.iam.dto.CustomerSignupRequest;
import com.moneybags.iam.security.IamGuard;
import com.moneybags.iam.security.UserPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.moneybags.iam.repository.IamRepository.map;

/** Explicit demo signup: KYC is assumed complete only when this feature flag is enabled. */
@Service
public class CustomerSignupService {
    private static final List<String> CUSTOMER_PERMISSIONS=List.of(
        "ACCOUNT_READ","TXN_READ","TXN_POST","PAYMENT_READ","PAYMENT_CREATE",
        "STATEMENT_READ","PRIVACY_CONSENT_SELF","PRIVACY_CONSENT_VIEW",
        "FX_READ","LOAN_READ","LOAN_ACCEPT","CIF_READ","PRODUCT_READ");
    private final JdbcTemplate jdbc;
    private final SchemaRepository schema;
    private final PasswordEncoder passwords;
    private final IamGuard guard;
    private final Clock clock;
    private final boolean enabled;
    private final String branch;

    public CustomerSignupService(JdbcTemplate jdbc,SchemaRepository schema,PasswordEncoder passwords,IamGuard guard,Clock clock,
            @Value("${moneybags.customer-signup.enabled:false}") boolean enabled,
            @Value("${moneybags.customer-signup.branch-ref:MUM001}") String branch) {
        this.jdbc=jdbc;this.schema=schema;this.passwords=passwords;this.guard=guard;this.clock=clock;this.enabled=enabled;this.branch=branch;
    }
    public boolean enabled(){return enabled;}

    @Transactional public Map<String,String> register(CustomerSignupRequest request){return registerChecked(request,null);}
    @Transactional public Map<String,String> registerAsAdmin(UserPrincipal actor,CustomerSignupRequest request){
        guard.require(actor,"IAM_USER_CREATE");return registerChecked(request,actor.userId());
    }
    private Map<String,String> registerChecked(CustomerSignupRequest request,String adminUserId){
        if(!enabled)throw new BusinessException(HttpStatus.FORBIDDEN,"SIGNUP_DISABLED","Customer signup is unavailable");
        IamAdminService.password(request.password());
        if(request.legalName().trim().isEmpty())throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_NAME","Enter a legal name");
        if(request.dateOfBirth().plusYears(18).isAfter(LocalDate.now(clock)))
            throw new BusinessException(HttpStatus.BAD_REQUEST,"GUARDIAN_REQUIRED","Customers under 18 need guardian-assisted onboarding");
        if(branch.isBlank()||branch.length()>80)throw new IllegalStateException("Configure a valid customer signup branch");
        // The bootstrap role is stable and serializes public signup, including username and role creation.
        var adminRoles=jdbc.queryForList("SELECT ROLE_ID FROM M01_IAM_ROLE WHERE ROLE_CODE='BANK_ADMIN' FOR UPDATE",String.class);
        if(adminRoles.isEmpty())throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"SETUP_REQUIRED","Administrator setup is required before signup");
        String username=request.username().trim();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM M01_IAM_USER WHERE UPPER(USERNAME)=UPPER(?)",Long.class,username)>0)
            throw new BusinessException(HttpStatus.CONFLICT,"USERNAME_EXISTS","Username already exists");
        String role=customerRole();
        String user=UUID.randomUUID().toString(),party=UUID.randomUUID().toString(),cif=UUID.randomUUID().toString();
        OffsetDateTime now=OffsetDateTime.now(clock);
        schema.insert(SchemaTable.M01_IAM_USER,map("USER_ID",user,"USERNAME",username,"USER_TYPE","CUSTOMER","STATUS","ACTIVE"));
        schema.insert(SchemaTable.M01_IAM_CREDENTIAL,map("CREDENTIAL_ID",UUID.randomUUID().toString(),"USER_ID",user,
            "PASSWORD_HASH",passwords.encode(request.password()),"HASH_SCHEME","BCRYPT","STATUS","ACTIVE"));
        schema.insert(SchemaTable.M02_CIF_PARTY,map("PARTY_ID",party,"PARTY_TYPE","INDIVIDUAL","DATE_OF_BIRTH",request.dateOfBirth()));
        schema.insert(SchemaTable.M02_CIF_CUSTOMER,map("CIF_ID",cif,"CIF_NUMBER","CIF-"+cif.replace("-","").toUpperCase(Locale.ROOT),
            "PARTY_ID",party,"STATUS","ACTIVE","HOME_BRANCH_REF",branch.trim(),"KYC_STATUS","VERIFIED","RISK_LEVEL","LOW"));
        schema.insert(SchemaTable.M02_CIF_NAME,map("NAME_ID",UUID.randomUUID().toString(),"PARTY_ID",party,"NAME_TYPE","LEGAL",
            "FULL_NAME",request.legalName().trim(),"VERIFICATION_STATUS","VERIFIED","VALID_FROM",now));
        schema.insert(SchemaTable.M02_KYC_CASE,map("CASE_ID",UUID.randomUUID().toString(),"CIF_ID",cif,
            "CASE_TYPE","ONBOARDING","STATUS","APPROVED","MAKER_USER_ID",user,"RISK_LEVEL","LOW",
            "SUBMITTED_AT",now,"DECIDED_AT",now));
        schema.insert(SchemaTable.M01_IAM_CUSTOMER_LINK,map("LINK_ID",UUID.randomUUID().toString(),"USER_ID",user,
            "CIF_ID",cif,"RELATIONSHIP_TYPE","SELF","STATUS","ACTIVE","VALID_FROM",now));
        schema.insert(SchemaTable.M01_IAM_USER_ROLE,map("ASSIGNMENT_ID",UUID.randomUUID().toString(),"USER_ID",user,
            "ROLE_ID",role,"SCOPE_TYPE","SELF","STATUS","ACTIVE","VALID_FROM",now));
        schema.insert(SchemaTable.M01_IAM_AUDIT_EVENT,map("AUDIT_ID",UUID.randomUUID().toString(),"EVENT_TYPE",adminUserId==null?"DEMO_CUSTOMER_SIGNUP":"DEMO_CUSTOMER_ADMIN_CREATED",
            "ACTOR_USER_ID",adminUserId==null?user:adminUserId,"RESOURCE_TYPE","IAM_USER","RESOURCE_ID",user,"RESULT","SUCCESS",
            "REASON_CODE","DEMO_KYC_ASSUMED","CORRELATION_ID",UUID.randomUUID().toString()));
        return Map.of("username",username,"cifId",cif);
    }

    private String customerRole(){
        var roles=jdbc.queryForList("SELECT ROLE_ID,STATUS FROM M01_IAM_ROLE WHERE ROLE_CODE='RETAIL_CUSTOMER'");
        if(!roles.isEmpty()){
            if(!"ACTIVE".equals(roles.get(0).get("STATUS")))
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"SETUP_REQUIRED","Customer role is unavailable");
            String role=(String)roles.get(0).get("ROLE_ID");
            var granted=jdbc.queryForList("SELECT P.PERMISSION_CODE FROM M01_IAM_ROLE_PERMISSION RP JOIN M01_IAM_PERMISSION P ON P.PERMISSION_ID=RP.PERMISSION_ID WHERE RP.ROLE_ID=?",String.class,role);
            if(!CUSTOMER_PERMISSIONS.containsAll(granted))
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"SETUP_REQUIRED","Customer role needs review before public signup");
            for(String permission:CUSTOMER_PERMISSIONS){
                if(granted.contains(permission))continue;
                var ids=jdbc.queryForList("SELECT PERMISSION_ID FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE=?",String.class,permission);
                if(ids.isEmpty())throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"SETUP_REQUIRED","Customer permissions are not installed");
                schema.insert(SchemaTable.M01_IAM_ROLE_PERMISSION,map("ROLE_ID",role,"PERMISSION_ID",ids.get(0)));
            }
            return role;
        }
        String role=UUID.randomUUID().toString();
        schema.insert(SchemaTable.M01_IAM_ROLE,map("ROLE_ID",role,"ROLE_CODE","RETAIL_CUSTOMER",
            "DISPLAY_NAME","Retail customer","STATUS","ACTIVE","SENSITIVE_FLAG","N"));
        for(String permission:CUSTOMER_PERMISSIONS){
            var ids=jdbc.queryForList("SELECT PERMISSION_ID FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE=?",String.class,permission);
            if(ids.isEmpty())throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"SETUP_REQUIRED","Customer permissions are not installed");
            schema.insert(SchemaTable.M01_IAM_ROLE_PERMISSION,map("ROLE_ID",role,"PERMISSION_ID",ids.get(0)));
        }
        return role;
    }
}
