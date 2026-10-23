package com.moneybags.integration;

import com.moneybags.common.api.BusinessException;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.iam.security.UserPrincipal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminFinancialAccessTest {
    JdbcTemplate db;
    CustomerHashService hashes;
    BankingAccess access;

    @BeforeEach void setup() {
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=Oracle"));
        // Keep the database alive between the independent repository calls.
        db.execute("SET DB_CLOSE_DELAY -1");
        db.execute("CREATE TABLE M01_IAM_USER(USER_ID VARCHAR PRIMARY KEY, STATUS VARCHAR)");
        db.execute("CREATE TABLE M01_IAM_ROLE(ROLE_ID VARCHAR PRIMARY KEY, ROLE_CODE VARCHAR, STATUS VARCHAR)");
        db.execute("CREATE TABLE M01_IAM_USER_ROLE(ASSIGNMENT_ID VARCHAR PRIMARY KEY, USER_ID VARCHAR, ROLE_ID VARCHAR, STATUS VARCHAR, SCOPE_TYPE VARCHAR, SCOPE_REF VARCHAR, VALID_FROM TIMESTAMP WITH TIME ZONE, VALID_TO TIMESTAMP WITH TIME ZONE)");
        db.execute("CREATE TABLE M01_IAM_USER_ROLE_SCOPE(ASSIGNMENT_ID VARCHAR)");
        db.execute("CREATE TABLE M01_IAM_SESSION(SESSION_ID VARCHAR PRIMARY KEY, USER_ID VARCHAR, STATUS VARCHAR, IDLE_EXPIRES_AT TIMESTAMP WITH TIME ZONE, ABSOLUTE_EXPIRES_AT TIMESTAMP WITH TIME ZONE)");
        db.update("INSERT INTO M01_IAM_USER VALUES ('admin','ACTIVE')");
        db.update("INSERT INTO M01_IAM_ROLE VALUES ('role','BANK_ADMIN','ACTIVE')");
        db.update("INSERT INTO M01_IAM_USER_ROLE VALUES ('grant','admin','role','ACTIVE','GLOBAL',NULL,SYSTIMESTAMP-1,NULL)");
        db.update("INSERT INTO M01_IAM_SESSION VALUES ('session','admin','ACTIVE',SYSTIMESTAMP+1,SYSTIMESTAMP+1)");
        access=new BankingAccess(new BusinessRepository(db,null),null);
        hashes=new CustomerHashService(db,access);
        signIn("EMPLOYEE",List.of("TXN_READ"));
    }
    void signIn(String type,List<String> permissions) {
        var actor=new UserPrincipal("admin","admin",type,"session",1L,List.of("BANK_ADMIN"),permissions,List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of()));
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();db.execute("SHUTDOWN");}

    @Test void globalAdminCanReadFinancialDetailsWithoutHolderProof() {
        assertDoesNotThrow(()->hashes.require(2030,null));
        assertTrue(hashes.permits(2030,"invalid-key"));
        assertEquals("BANK_ADMIN",hashes.proofLabel());
        assertTrue(access.bankAdminTransactionRead());
    }
    @ParameterizedTest @ValueSource(strings={
        "UPDATE M01_IAM_USER SET STATUS='SUSPENDED'",
        "UPDATE M01_IAM_ROLE SET STATUS='INACTIVE'",
        "UPDATE M01_IAM_ROLE SET ROLE_CODE='BANK_CHECKER'",
        "UPDATE M01_IAM_USER_ROLE SET STATUS='REVOKED'",
        "UPDATE M01_IAM_USER_ROLE SET SCOPE_TYPE='BRANCH',SCOPE_REF='B1'",
        "UPDATE M01_IAM_USER_ROLE SET SCOPE_REF='B1'",
        "UPDATE M01_IAM_USER_ROLE SET VALID_FROM=SYSTIMESTAMP+1",
        "UPDATE M01_IAM_USER_ROLE SET VALID_TO=SYSTIMESTAMP-1",
        "INSERT INTO M01_IAM_USER_ROLE_SCOPE VALUES ('grant')",
        "UPDATE M01_IAM_SESSION SET STATUS='REVOKED'",
        "UPDATE M01_IAM_SESSION SET IDLE_EXPIRES_AT=SYSTIMESTAMP-1",
        "UPDATE M01_IAM_SESSION SET ABSOLUTE_EXPIRES_AT=SYSTIMESTAMP-1",
        "UPDATE M01_IAM_SESSION SET USER_ID='someone-else'"
    })
    void staleOrRestrictedAdminDoesNotInheritFinancialAccess(String change) {
        db.update(change);
        assertFalse(access.bankAdminFinancialAccess());
        assertThrows(BusinessException.class,()->hashes.require(2030,null));
        assertEquals("CUSTOMER_HASH",hashes.proofLabel());
    }
    @Test void adminRoleNameAloneDoesNotGrantFinancialAccess() {
        signIn("EMPLOYEE",List.of());
        assertFalse(access.bankAdminFinancialAccess());
        assertThrows(BusinessException.class,()->hashes.require(2030,null));
        signIn("CUSTOMER",List.of("TXN_READ"));
        assertFalse(access.bankAdminFinancialAccess());
    }
}
