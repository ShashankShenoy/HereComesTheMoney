import java.sql.*;
import java.nio.file.*;
import java.util.*;

/** Read-only diagnostics for the web demo; credentials stay in process environment. */
public class OracleTabAudit {
    public static void main(String[] args) throws Exception {
        var queries=new LinkedHashMap<String,String>();
        queries.put("demoRoles","SELECT U.USERNAME,U.USER_TYPE,U.STATUS,R.ROLE_CODE,A.SCOPE_TYPE FROM M01_IAM_USER U LEFT JOIN M01_IAM_USER_ROLE A ON A.USER_ID=U.USER_ID AND A.STATUS='ACTIVE' AND A.VALID_FROM<=SYSTIMESTAMP AND (A.VALID_TO IS NULL OR A.VALID_TO>SYSTIMESTAMP) LEFT JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID WHERE LOWER(U.USERNAME) IN ('adipillai','adipilla','admin','checker1','mcp_demo_customer_a','mcp_demo_customer_b','mcp_demo_checker','mcp_demo_officer','demo.officer','checker') ORDER BY U.USERNAME,R.ROLE_CODE");
        queries.put("customerDimensions","SELECT U.USERNAME,S.DIMENSION_CODE,S.SCOPE_VALUE FROM M01_IAM_USER U JOIN M01_IAM_USER_ROLE A ON A.USER_ID=U.USER_ID AND A.STATUS='ACTIVE' JOIN M01_IAM_USER_ROLE_SCOPE S ON S.ASSIGNMENT_ID=A.ASSIGNMENT_ID WHERE LOWER(U.USERNAME) IN ('adipillai','adipilla','mcp_demo_customer_a','mcp_demo_customer_b') ORDER BY U.USERNAME,S.DIMENSION_CODE,S.SCOPE_VALUE");
        queries.put("rolePermissions","SELECT R.ROLE_CODE,P.PERMISSION_CODE FROM M01_IAM_ROLE R JOIN M01_IAM_ROLE_PERMISSION RP ON RP.ROLE_ID=R.ROLE_ID JOIN M01_IAM_PERMISSION P ON P.PERMISSION_ID=RP.PERMISSION_ID WHERE R.ROLE_CODE IN ('BANK_ADMIN','BANK_CHECKER','BANK_CUSTOMER','RETAIL_CUSTOMER','MCP_DEMO_CUSTOMER') ORDER BY R.ROLE_CODE,P.PERMISSION_CODE");
        queries.put("approvalAuthorities","SELECT R.ROLE_CODE,A.AUTHORITY_CODE,A.CURRENCY_CODE,A.MAX_AMOUNT,A.MAX_RATE_PCT FROM M01_IAM_ROLE R JOIN M01_IAM_ROLE_AUTHORITY A ON A.ROLE_ID=R.ROLE_ID WHERE R.ROLE_CODE IN ('BANK_ADMIN','BANK_CHECKER') AND A.VALID_FROM<=SYSTIMESTAMP AND (A.VALID_TO IS NULL OR A.VALID_TO>SYSTIMESTAMP) ORDER BY R.ROLE_CODE,A.AUTHORITY_CODE");
        queries.put("statementPolicies","SELECT CHANNEL_CODE,AUDIENCE_CODE,STATUS,COUNT(*) AS ROW_COUNT FROM M10_MASKING_PROFILE GROUP BY CHANNEL_CODE,AUDIENCE_CODE,STATUS");
        queries.put("statementPolicyTemplate","SELECT RULES_JSON FROM M10_MASKING_PROFILE WHERE CHANNEL_CODE='WEB' AND AUDIENCE_CODE='CUSTOMER' AND STATUS='APPROVED' ORDER BY VERSION_NO DESC FETCH FIRST 1 ROW ONLY");
        queries.put("statementNarrationTemplates","SELECT NARRATION_CODE,LOCALE_CODE,RENDERED_TEXT FROM M10_NARRATION_CATALOG WHERE AUDIENCE_CODE='CUSTOMER' AND APPROVAL_STATUS='APPROVED' AND EFFECTIVE_FROM<=SYSTIMESTAMP AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>SYSTIMESTAMP) ORDER BY VERSION_NO DESC");
        queries.put("statementNarrations","SELECT NARRATION_CODE,LOCALE_CODE,AUDIENCE_CODE,APPROVAL_STATUS,COUNT(*) AS ROW_COUNT FROM M10_NARRATION_CATALOG GROUP BY NARRATION_CODE,LOCALE_CODE,AUDIENCE_CODE,APPROVAL_STATUS");
        queries.put("accountMismatch","SELECT A.ACCOUNT_ID,SUBSTR(A.ACCOUNT_NUMBER,-4) AS ACCOUNT_ENDING,A.ACCOUNT_STATUS,P.POSTED_BALANCE,P.GL_DERIVED_BALANCE,P.DIFFERENCE FROM M05_V_ACCOUNT_POSITION_RECON P JOIN M04_BANK_ACCOUNT A ON A.ACCOUNT_ID=P.BANK_ACCOUNT_ID WHERE P.IS_MATCHED='N'");
        queries.put("requiredTables","SELECT TABLE_NAME FROM USER_TABLES WHERE TABLE_NAME IN ('MBX_PAYMENT_PIN','MBX_BRANCH_VAULT','MBX_TELLER_TILL','MBX_RBI_CASH_DELIVERY','M10_STATEMENT_REQUEST','M10_STATEMENT_REVISION_INTENT','M11_CC_CARD','M11_CC_PRODUCT') ORDER BY TABLE_NAME");
        queries.put("activeProducts","SELECT P.PRODUCT_TYPE,P.PRODUCT_CODE,V.PRODUCT_VERSION_ID,V.VERSION_STATE FROM M03_PM_PRODUCT P JOIN M03_PM_PRODUCT_VERSION V ON V.PRODUCT_ID=P.PRODUCT_ID WHERE V.VERSION_STATE='ACTIVE' ORDER BY P.PRODUCT_TYPE,P.PRODUCT_CODE");
        queries.put("customerReferenceMatches","SELECT U.USERNAME,U.USER_TYPE,U.STATUS,R.ROLE_CODE,A.SCOPE_TYPE FROM M01_IAM_USER U LEFT JOIN M01_IAM_USER_ROLE A ON A.USER_ID=U.USER_ID AND A.STATUS='ACTIVE' AND A.VALID_FROM<=SYSTIMESTAMP AND (A.VALID_TO IS NULL OR A.VALID_TO>SYSTIMESTAMP) LEFT JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID WHERE LOWER(U.USERNAME) LIKE '%pillai%' OR LOWER(U.USERNAME) LIKE 'adi%' ORDER BY U.USERNAME,R.ROLE_CODE");
        var report=new LinkedHashMap<String,Object>();
        try(var db=DriverManager.getConnection(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))){
            db.setAutoCommit(false);try(var s=db.createStatement()){s.execute("SET TRANSACTION READ ONLY");}
            try{for(var query:queries.entrySet()){
                var rows=new ArrayList<Map<String,Object>>();
                try(var s=db.createStatement()){s.setQueryTimeout(30);try(var rs=s.executeQuery(query.getValue())){var meta=rs.getMetaData();while(rs.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=meta.getColumnCount();i++){var value=rs.getObject(i);row.put(meta.getColumnLabel(i),value instanceof Clob?rs.getString(i):value);}rows.add(row);}}report.put(query.getKey(),rows);System.out.println(query.getKey()+": "+rows.size()+" rows");}
                catch(SQLException e){report.put(query.getKey(),Map.of("oracleErrorCode",e.getErrorCode()));System.out.println(query.getKey()+": Oracle error "+e.getErrorCode());}
            }}finally{db.rollback();}
        }
        Files.writeString(Path.of(args[0]),json(report));
    }
    private static String json(Object value){
        if(value==null)return "null";
        if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map){var entries=new ArrayList<String>();map.forEach((k,v)->entries.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",entries)+"}";}
        if(value instanceof Collection<?> list)return "["+String.join(",",list.stream().map(OracleTabAudit::json).toList())+"]";
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
}
