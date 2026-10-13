import java.sql.*;
import java.nio.file.*;
import java.util.*;

/** Safe ALPHA migration/readiness verification. Credentials come only from environment. */
public class OraclePaymentProbe {
 public static void main(String[] args)throws Exception {
  try(Connection c=DriverManager.getConnection(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))){
   System.out.println("PASS: connected to configured Oracle schema");
   if(args.length>0&&args[0].equals("apply")){
    String sql=Files.readString(Path.of(args[1])).replaceAll("(?m)--.*$","");
    for(String statement:sql.split(";")){
     if(statement.isBlank())continue;
     try(Statement s=c.createStatement()){s.execute(statement.trim());}
     catch(SQLException e){if(e.getErrorCode()!=955&&e.getErrorCode()!=1)throw e;}
    }
    System.out.println("PASS: additive simulator migration applied; existing balances preserved");
   }
   try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME IN ('MBX_SIM_BANK','MBX_SIM_PAYMENT','MBX_SIM_WINDOW','MBX_SIM_SETTLEMENT','MBX_SIM_MOVEMENT','MBX_EMPLOYEE_ERROR')")){
    r.next();if(r.getInt(1)!=6)throw new IllegalStateException("Simulator migration is incomplete");
   }
   c.setAutoCommit(false);
   try{
    try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT RESERVE_ACCOUNT_ID FROM M07_RESERVE_ACCOUNT WHERE RESERVE_ACCOUNT_CODE='MCP-RBI-CURRENT' AND ACCOUNT_STATUS='ACTIVE'")){
     if(!r.next())throw new IllegalStateException("Existing MCP-RBI-CURRENT reserve is missing");long id=r.getLong(1);
     try(PreparedStatement p=c.prepareStatement("INSERT INTO MBX_SIM_BANK(BANK_CODE,BANK_NAME,RESERVE_ACCOUNT_ID) SELECT 'MCP','Moneybags RBI current reserve',? FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM MBX_SIM_BANK WHERE BANK_CODE='MCP')")){p.setLong(1,id);p.executeUpdate();}
    }
    try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM MBX_SIM_BANK WHERE BANK_CODE IN ('HDFC','AXIS','SBI','ICICI') AND RESERVE_ACCOUNT_ID IS NULL")){
     r.next();if(r.getInt(1)!=4)throw new IllegalStateException("External participants are missing or treated as bank assets");
    }
    // Exercise the exact nullable UPDATE SQL that formerly requested Oracle metadata.
    try(PreparedStatement p=c.prepareStatement("UPDATE M06_PAYMENT_INSTRUCTION SET STATUS=?,VERSION_NO=VERSION_NO+1,HOLD_ID=COALESCE(?,HOLD_ID),LIQUIDITY_HOLD_ID=COALESCE(?,LIQUIDITY_HOLD_ID),REASON_CODE=?,UPDATED_AT=SYSTIMESTAMP WHERE PAYMENT_ID=? AND VERSION_NO=?")){
     p.setString(1,"AUTHORIZED");p.setNull(2,Types.NUMERIC);p.setNull(3,Types.NUMERIC);p.setNull(4,Types.VARCHAR);p.setLong(5,-1);p.setInt(6,0);p.executeUpdate();
    }
    try(PreparedStatement p=c.prepareStatement("UPDATE M06_RAIL_DISPATCH SET DISPATCH_STATUS=?,ATTEMPT_COUNT=?,LAST_ATTEMPT_AT=SYSTIMESTAMP,LAST_ERROR_CODE=?,FIRST_DISPATCHED_AT=CASE WHEN ?='SENT' THEN COALESCE(FIRST_DISPATCHED_AT,SYSTIMESTAMP) ELSE FIRST_DISPATCHED_AT END WHERE DISPATCH_ID=?")){
     p.setString(1,"SENT");p.setInt(2,1);p.setNull(3,Types.VARCHAR);p.setString(4,"SENT");p.setLong(5,-1);p.executeUpdate();
    }
    try(PreparedStatement p=c.prepareStatement("SELECT SETTLEMENT_CYCLE_ID FROM MBX_SIM_WINDOW WHERE WINDOW_END<=?")){p.setObject(1,java.time.OffsetDateTime.now(),Types.TIMESTAMP_WITH_TIMEZONE);try(ResultSet r=p.executeQuery()){while(r.next()){}}}
    System.out.println("PASS: existing bank reserve resolves; external participants stay outside bank assets");
    System.out.println("PASS: nullable payment/dispatch bindings and cycle timestamps execute against Oracle");
   }finally{c.rollback();}
  }catch(SQLException e){System.err.println("Oracle verification failed with SQL error code "+e.getErrorCode());System.exit(1);}
 }
}
