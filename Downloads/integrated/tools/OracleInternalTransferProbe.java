import java.sql.*;

/** Read-only verification of the synthetic sender's entitlement and beneficiary routing SQL. */
public class OracleInternalTransferProbe {
 public static void main(String[] args)throws Exception {
  try(Connection c=DriverManager.getConnection(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))){
   String user;
   try(PreparedStatement p=c.prepareStatement("SELECT USER_ID FROM M01_IAM_USER WHERE USERNAME='mcp_demo_customer_b' AND STATUS='ACTIVE'");ResultSet r=p.executeQuery()){
    if(!r.next())throw new IllegalStateException("Synthetic customer is missing");user=r.getString(1);
   }
   try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM M01_IAM_USER_ROLE A JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID JOIN M01_IAM_ROLE_PERMISSION G ON G.ROLE_ID=R.ROLE_ID JOIN M01_IAM_PERMISSION P ON P.PERMISSION_ID=G.PERMISSION_ID WHERE A.USER_ID=? AND A.STATUS='ACTIVE' AND A.SCOPE_TYPE='SELF' AND A.VALID_FROM<=SYSTIMESTAMP AND (A.VALID_TO IS NULL OR A.VALID_TO>SYSTIMESTAMP) AND R.STATUS='ACTIVE' AND P.PERMISSION_CODE='TXN_POST'")){
    p.setString(1,user);try(ResultSet r=p.executeQuery()){r.next();if(r.getLong(1)<1)throw new IllegalStateException("Own-account transfer grant is missing");}
   }
   try(PreparedStatement p=c.prepareStatement("SELECT BENEFICIARY_ID,DISPLAY_NAME,BANK_CODE,STATUS,CREATED_AT FROM MBX_BENEFICIARY WHERE OWNER_USER_ID=? ORDER BY CREATED_AT DESC")){
    p.setString(1,user);try(ResultSet r=p.executeQuery()){while(r.next()){if(r.getString("BANK_CODE")==null)throw new IllegalStateException("Missing bank routing");}}
   }
   try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM MBX_SIM_ROUTE WHERE IFSC_PREFIX=? AND BANK_CODE='MCP'")){
    p.setString(1,"MCPB");try(ResultSet r=p.executeQuery()){r.next();if(r.getLong(1)!=1)throw new IllegalStateException("Own bank routing is ambiguous");}
   }
   System.out.println("PASS: Oracle beneficiary classification and MCP routing; synthetic customer B has SELF transfer permission. No balances changed.");
  }
 }
}
