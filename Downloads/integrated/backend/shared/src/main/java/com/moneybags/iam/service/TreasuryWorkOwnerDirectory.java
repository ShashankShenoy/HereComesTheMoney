package com.moneybags.iam.service;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Current employees who can operate or review bank-wide treasury work. */
@Service
public class TreasuryWorkOwnerDirectory {
    public record Owner(String userId, String username) {}
    private final JdbcTemplate jdbc;
    private static final String ELIGIBLE = """
        FROM M01_IAM_USER U
        WHERE U.USER_TYPE='EMPLOYEE' AND U.STATUS='ACTIVE'
          AND EXISTS (
            SELECT 1 FROM M01_IAM_USER_ROLE A
            JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID
            JOIN M01_IAM_ROLE_PERMISSION RP ON RP.ROLE_ID=R.ROLE_ID
            JOIN M01_IAM_PERMISSION P ON P.PERMISSION_ID=RP.PERMISSION_ID
            WHERE A.USER_ID=U.USER_ID AND A.STATUS='ACTIVE' AND R.STATUS='ACTIVE'
              AND A.SCOPE_TYPE='GLOBAL' AND A.VALID_FROM<=SYSTIMESTAMP
              AND (A.VALID_TO IS NULL OR A.VALID_TO>SYSTIMESTAMP)
              AND P.PERMISSION_CODE IN ('TREASURY_RECONCILE','TREASURY_WORK_APPROVE'))
        """;

    public TreasuryWorkOwnerDirectory(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Owner> owners() {
        return jdbc.query("SELECT U.USER_ID,U.USERNAME " + ELIGIBLE + " ORDER BY U.USERNAME,U.USER_ID",
            (row, n) -> new Owner(row.getString("USER_ID"), row.getString("USERNAME")));
    }

    public boolean eligible(String userId) {
        return jdbc.queryForObject("SELECT COUNT(*) " + ELIGIBLE + " AND U.USER_ID=?", Integer.class, userId) == 1;
    }
}
