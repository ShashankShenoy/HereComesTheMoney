package com.moneybags.iam.service;
import com.moneybags.common.database.*;
import com.moneybags.iam.repository.IamRepository;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static com.moneybags.iam.repository.IamRepository.map;
@Service
public class BootstrapService {
    private final JdbcTemplate jdbc;private final SchemaRepository schema;private final PasswordEncoder passwords;private final Clock clock;
    public BootstrapService(JdbcTemplate jdbc,SchemaRepository schema,PasswordEncoder passwords,Clock clock) { this.jdbc=jdbc;this.schema=schema;this.passwords=passwords;this.clock=clock; }
    @Transactional public boolean createFirstAdmin(String username,String password) {
        if(username==null||username.isBlank()||username.length()>120)throw new IllegalArgumentException("BOOTSTRAP_USERNAME must contain 1-120 characters");
        if(password==null||password.length()<10||password.getBytes(StandardCharsets.UTF_8).length>72)throw new IllegalArgumentException("Set BOOTSTRAP_PASSWORD to at least 10 characters and at most 72 UTF-8 bytes");
        Long existing=jdbc.queryForObject("SELECT COUNT(*) FROM M01_IAM_USER WHERE UPPER(USERNAME)=UPPER(?)",Long.class,username);
        if(existing!=null&&existing>0)return false; // Never reset an existing password.
        Long allUsers=jdbc.queryForObject("SELECT COUNT(*) FROM M01_IAM_USER",Long.class);
        if(allUsers!=null&&allUsers>0)throw new IllegalStateException("First-admin bootstrap is only allowed when IAM_USER is empty; use existing access administration");
        String roleId=UUID.randomUUID().toString();
        var existingRole=jdbc.queryForList("SELECT ROLE_ID FROM M01_IAM_ROLE WHERE ROLE_CODE='BANK_ADMIN'",String.class);
        if(existingRole.isEmpty())schema.insert(SchemaTable.M01_IAM_ROLE,map("ROLE_ID",roleId,"ROLE_CODE","BANK_ADMIN","DISPLAY_NAME","Bank Administrator","STATUS","ACTIVE","SENSITIVE_FLAG","Y"));else roleId=existingRole.get(0);
        List<String> permissions=new ArrayList<>(List.of("SYSTEM_SCHEMA_READ","IAM_USER_READ","IAM_USER_CREATE","IAM_USER_MANAGE","IAM_ROLE_MANAGE","IAM_ACCESS_MANAGE","IAM_ACCESS_APPROVE","IAM_AUDIT_READ","CIF_READ","CIF_CREATE","CIF_UPDATE","KYC_UPLOAD","KYC_REVIEW","PRODUCT_READ","PRODUCT_CREATE","PRODUCT_APPROVE","ACCOUNT_READ","ACCOUNT_OPEN","ACCOUNT_CONTROL","TXN_READ","TXN_POST","TXN_REVERSE","PAYMENT_READ","PAYMENT_CREATE","PAYMENT_OPERATE"));
        permissions.addAll(com.moneybags.integration.IntegratedPermissions.PERMISSIONS);
        for(String permission:permissions) {
            var ids=jdbc.queryForList("SELECT PERMISSION_ID FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE=?",String.class,permission);
            String pid=ids.isEmpty()?UUID.randomUUID().toString():ids.get(0);
            if(ids.isEmpty())schema.insert(SchemaTable.M01_IAM_PERMISSION,map("PERMISSION_ID",pid,"PERMISSION_CODE",permission,"RESOURCE_CODE",permission.substring(0,permission.indexOf('_')),"ACTION_CODE",permission));
            Long linked=jdbc.queryForObject("SELECT COUNT(*) FROM M01_IAM_ROLE_PERMISSION WHERE ROLE_ID=? AND PERMISSION_ID=?",Long.class,roleId,pid);
            if(linked==null||linked==0)schema.insert(SchemaTable.M01_IAM_ROLE_PERMISSION,map("ROLE_ID",roleId,"PERMISSION_ID",pid));
        }
        String user=UUID.randomUUID().toString();OffsetDateTime now=OffsetDateTime.now(clock);
        schema.insert(SchemaTable.M01_IAM_USER,map("USER_ID",user,"USERNAME",username.trim(),"USER_TYPE","EMPLOYEE","STATUS","ACTIVE","EMPLOYEE_REF","LOCAL-BOOTSTRAP-ADMIN"));
        schema.insert(SchemaTable.M01_IAM_CREDENTIAL,map("CREDENTIAL_ID",UUID.randomUUID().toString(),"USER_ID",user,"PASSWORD_HASH",passwords.encode(password),"HASH_SCHEME","BCRYPT","STATUS","ACTIVE"));
        // Initial administrative access is a documented bootstrap exception.
        // Subsequent role grants will use the maker/checker access-request workflow.
        schema.insert(SchemaTable.M01_IAM_USER_ROLE,map("ASSIGNMENT_ID",UUID.randomUUID().toString(),"USER_ID",user,"ROLE_ID",roleId,"SCOPE_TYPE","GLOBAL","STATUS","ACTIVE","VALID_FROM",now));
        String[] routes={"/dashboard","/users","/roles","/access","/audit","/security"};
        String[] labels={"Overview","Users","Roles & permissions","Access requests","Audit trail","My security"};
        String[] required={"IAM_USER_READ","IAM_USER_READ","IAM_USER_READ","IAM_USER_READ","IAM_AUDIT_READ",null};
        for(int i=0;i<routes.length;i++) {
            if(jdbc.queryForObject("SELECT COUNT(*) FROM M01_IAM_MENU_ITEM WHERE CLIENT_ID='MONEYBAGS_WEB' AND ROUTE_PATH=?",Long.class,routes[i])>0)continue;
            String pid=required[i]==null?null:jdbc.queryForObject("SELECT PERMISSION_ID FROM M01_IAM_PERMISSION WHERE PERMISSION_CODE=?",String.class,required[i]);
            schema.insert(SchemaTable.M01_IAM_MENU_ITEM,map("MENU_ID",UUID.randomUUID().toString(),"CLIENT_ID","MONEYBAGS_WEB","ROUTE_PATH",routes[i],"LABEL_KEY",labels[i],"SORT_ORDER",i,"REQUIRED_PERMISSION_ID",pid));
        }
        schema.insert(SchemaTable.M01_IAM_AUDIT_EVENT,map("AUDIT_ID",UUID.randomUUID().toString(),"EVENT_TYPE","FIRST_ADMIN_BOOTSTRAPPED","ACTOR_USER_ID",user,"RESOURCE_TYPE","IAM_USER","RESOURCE_ID",user,"RESULT","SUCCESS","REASON_CODE","LOCAL_INITIAL_SETUP","CORRELATION_ID",UUID.randomUUID().toString()));
        return true;
    }
}
