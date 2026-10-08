import java.sql.*;

public class LoanReadinessAudit {
    public static void main(String[] args) throws Exception {
        try (Connection db = DriverManager.getConnection(
                 System.getenv("DB_URL"), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
             Statement statement = db.createStatement()) {
            try (ResultSet rows = statement.executeQuery("""
                SELECT p.PRODUCT_CODE, v.PRODUCT_VERSION_ID,
                       (SELECT COUNT(DISTINCT m.GL_ROLE_CODE)
                          FROM M05_GL_PRODUCT_MAPPING m
                         WHERE m.PRODUCT_VERSION_ID = v.PRODUCT_VERSION_ID
                           AND m.POSTING_TYPE = 'LOAN' AND m.STATUS = 'ACTIVE'
                           AND m.GL_ROLE_CODE IN ('LOAN_PRINCIPAL','LOAN_INTEREST','INTEREST_INCOME')) GL_ROLES
                  FROM M03_PM_PRODUCT p
                  JOIN M03_PM_PRODUCT_VERSION v ON v.PRODUCT_ID = p.PRODUCT_ID
                 WHERE p.PRODUCT_TYPE = 'LOAN' AND v.VERSION_STATE = 'ACTIVE'
                 ORDER BY p.PRODUCT_CODE
                """)) {
                while (rows.next())
                    System.out.printf("%s versionId=%d activeLoanGlRoles=%d/3%n",
                        rows.getString(1), rows.getLong(2), rows.getInt(3));
            }
            try (ResultSet rows = statement.executeQuery("""
                SELECT COUNT(*) FROM M01_IAM_ROLE_AUTHORITY
                 WHERE AUTHORITY_CODE = 'LOAN_SANCTION'
                """)) {
                rows.next();
                System.out.println("loanSanctionAuthorities=" + rows.getLong(1));
            }
            try (ResultSet rows = statement.executeQuery("""
                SELECT GL_ACCOUNT_ID, GL_CODE, GL_NAME, ACCOUNT_CLASS, NORMAL_SIDE, ACTIVE_FLAG
                  FROM M05_GL_ACCOUNT ORDER BY GL_ACCOUNT_ID
                """)) {
                while (rows.next()) System.out.printf("GL %d %s %s %s %s active=%s%n",
                    rows.getLong(1), rows.getString(2), rows.getString(3),
                    rows.getString(4), rows.getString(5), rows.getString(6));
            }
            try (ResultSet rows = statement.executeQuery("""
                SELECT POSTING_TYPE, GL_ROLE_CODE, COUNT(*)
                  FROM M05_GL_PRODUCT_MAPPING WHERE STATUS='ACTIVE'
                 GROUP BY POSTING_TYPE, GL_ROLE_CODE
                 ORDER BY POSTING_TYPE, GL_ROLE_CODE
                """)) {
                while (rows.next()) System.out.printf("mapping %s %s count=%d%n",
                    rows.getString(1), rows.getString(2), rows.getLong(3));
            }
            try (ResultSet rows = statement.executeQuery("""
                SELECT PRODUCT_VERSION_ID, POSTING_TYPE, GL_ROLE_CODE, GL_ACCOUNT_ID
                  FROM M05_GL_PRODUCT_MAPPING WHERE STATUS='ACTIVE'
                 ORDER BY PRODUCT_VERSION_ID,POSTING_TYPE,GL_ROLE_CODE
                """)) {
                while (rows.next()) System.out.printf("mappingDetail version=%d %s %s gl=%d%n",
                    rows.getLong(1),rows.getString(2),rows.getString(3),rows.getLong(4));
            }
            try (ResultSet rows = statement.executeQuery("""
                SELECT P.PRODUCT_CODE, R.PREPAYMENT_ALLOWED, R.PARTIAL_PREPAYMENT_ALLOWED,
                       R.FORECLOSURE_ALLOWED, R.DISBURSEMENT_MODE
                  FROM M03_PM_PRODUCT P
                  JOIN M03_PM_PRODUCT_VERSION V ON V.PRODUCT_ID=P.PRODUCT_ID
                  JOIN M03_PM_LOAN_RULE R ON R.PRODUCT_VERSION_ID=V.PRODUCT_VERSION_ID
                 WHERE P.PRODUCT_TYPE='LOAN' AND V.VERSION_STATE='ACTIVE'
                 ORDER BY P.PRODUCT_CODE
                """)) {
                while (rows.next()) System.out.printf("terms %s prepay=%s partial=%s foreclose=%s disburse=%s%n",
                    rows.getString(1), rows.getString(2), rows.getString(3),
                    rows.getString(4), rows.getString(5));
            }
            try (ResultSet rows = statement.executeQuery("""
                SELECT R.ROLE_CODE, P.PERMISSION_CODE
                  FROM M01_IAM_ROLE R
                  JOIN M01_IAM_ROLE_PERMISSION RP ON RP.ROLE_ID=R.ROLE_ID
                  JOIN M01_IAM_PERMISSION P ON P.PERMISSION_ID=RP.PERMISSION_ID
                 WHERE R.ROLE_CODE IN ('BANK_CHECKER','BANK_ADMIN') AND P.PERMISSION_CODE LIKE 'LOAN_%'
                 ORDER BY R.ROLE_CODE,P.PERMISSION_CODE
                """)) {
                while (rows.next()) System.out.printf("role %s %s%n",rows.getString(1),rows.getString(2));
            }
            try (ResultSet rows = statement.executeQuery("""
                SELECT U.USERNAME, A.SCOPE_TYPE, A.SCOPE_REF
                  FROM M01_IAM_USER_ROLE A
                  JOIN M01_IAM_USER U ON U.USER_ID=A.USER_ID
                  JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID
                 WHERE R.ROLE_CODE='BANK_CHECKER' AND A.STATUS='ACTIVE'
                   AND A.VALID_FROM<=SYSTIMESTAMP AND (A.VALID_TO IS NULL OR A.VALID_TO>SYSTIMESTAMP)
                """)) {
                while (rows.next()) System.out.printf("checkerAssignment %s scope=%s/%s%n",
                    rows.getString(1), rows.getString(2), rows.getString(3));
            }
            try (ResultSet rows = statement.executeQuery("""
                SELECT A.PRODUCT_VERSION_ID, COUNT(DISTINCT A.ACCOUNT_ID) ACCOUNTS,
                       (SELECT COUNT(*) FROM M05_GL_PRODUCT_MAPPING M
                         WHERE M.PRODUCT_VERSION_ID=A.PRODUCT_VERSION_ID
                           AND M.POSTING_TYPE='LOAN' AND M.GL_ROLE_CODE='CUSTOMER_LIABILITY'
                           AND M.STATUS='ACTIVE') LOAN_MAPS
                  FROM M04_BANK_ACCOUNT A
                 WHERE A.LIFECYCLE_STATUS='ACTIVE' AND A.CURRENCY_CODE='INR'
                 GROUP BY A.PRODUCT_VERSION_ID ORDER BY A.PRODUCT_VERSION_ID
                """)) {
                while (rows.next()) System.out.printf("depositVersion %d activeAccounts=%d loanLiabilityMaps=%d%n",
                    rows.getLong(1), rows.getLong(2), rows.getLong(3));
            }
        }
    }
}
