import java.sql.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Applies the reviewed demo IAM migration, first proving that banking identities and money are preserved. */
public class OracleDemoAlignment {
  public static void main(String[] args) throws Exception {
    String sql=Files.readString(Path.of(args[0]));
    String block=sql.substring(sql.indexOf("DECLARE"),sql.indexOf("\n/",sql.indexOf("DECLARE"))).trim();
    boolean apply=args.length>1&&"--apply".equals(args[1]);
    try(var db=DriverManager.getConnection(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))) {
      db.setAutoCommit(false);
      try {
        var snapshots=new LinkedHashMap<String,String>();
        snapshots.put("identities","SELECT USER_ID,USERNAME,USER_TYPE,STATUS FROM M01_IAM_USER ORDER BY USER_ID");
        snapshots.put("credentials","SELECT * FROM M01_IAM_CREDENTIAL ORDER BY CREDENTIAL_ID");
        snapshots.put("customer links","SELECT * FROM M01_IAM_CUSTOMER_LINK ORDER BY LINK_ID");
        snapshots.put("bank accounts","SELECT * FROM M04_BANK_ACCOUNT ORDER BY ACCOUNT_ID");
        snapshots.put("positions","SELECT * FROM M05_ACCOUNT_POSITION ORDER BY BANK_ACCOUNT_ID,CURRENCY_CODE");
        var before=new LinkedHashMap<String,String>();
        for(var entry:snapshots.entrySet())before.put(entry.getKey(),digest(db,entry.getValue()));
        try(var statement=db.createStatement()){statement.setQueryTimeout(60);statement.execute(block);}
        for(var entry:snapshots.entrySet())if(!before.get(entry.getKey()).equals(digest(db,entry.getValue())))throw new IllegalStateException("Migration changed "+entry.getKey());
        String invalid="SELECT COUNT(*) FROM M01_IAM_USER U WHERE U.USER_TYPE='CUSTOMER' AND U.STATUS<>'CLOSED' AND (SELECT COUNT(*) FROM M01_IAM_USER_ROLE A JOIN M01_IAM_ROLE R ON R.ROLE_ID=A.ROLE_ID WHERE A.USER_ID=U.USER_ID AND A.STATUS='ACTIVE' AND A.VALID_FROM<=SYSTIMESTAMP AND (A.VALID_TO IS NULL OR A.VALID_TO>SYSTIMESTAMP) AND R.ROLE_CODE='BANK_CUSTOMER' AND A.SCOPE_TYPE='SELF')<>1";
        try(var s=db.createStatement();var r=s.executeQuery(invalid)){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("Canonical customer assignment check failed");}
        String grants="SELECT COUNT(*) FROM M01_IAM_ROLE_PERMISSION RP JOIN M01_IAM_ROLE R ON R.ROLE_ID=RP.ROLE_ID WHERE R.ROLE_CODE='BANK_CUSTOMER'";
        try(var s=db.createStatement();var r=s.executeQuery(grants)){r.next();if(r.getInt(1)!=19)throw new IllegalStateException("Customer permission count failed");}
        if(apply){db.commit();System.out.println("PASS: demo roles aligned; identities, credentials, customer links, accounts and positions preserved.");}
        else{db.rollback();System.out.println("PASS: alignment rollback rehearsal; identity and balance preservation checks passed.");}
      }catch(Exception error){db.rollback();throw error;}
    }
  }
  private static String digest(Connection db,String sql) throws Exception {
    var hash=MessageDigest.getInstance("SHA-256");
    try(var s=db.createStatement();var r=s.executeQuery(sql)){
      int columns=r.getMetaData().getColumnCount();
      while(r.next())for(int i=1;i<=columns;i++){
        String value=Objects.toString(r.getString(i),"<null>");
        hash.update((value.length()+":"+value+";").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
    }
    return HexFormat.of().formatHex(hash.digest());
  }
}
