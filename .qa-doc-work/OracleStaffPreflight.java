import java.sql.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class OracleStaffPreflight {
 public static void main(String[] args)throws Exception {
  var mapper=new ObjectMapper();var rows=mapper.readTree(System.in);var results=new ArrayList<Map<String,Object>>();
  if(!System.getenv("DB_URL").startsWith("jdbc:oracle:"))throw new IllegalStateException("Oracle required");
  try(var db=DriverManager.getConnection(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))){
   db.setAutoCommit(false);try(var q=db.createStatement()){q.execute("SET TRANSACTION READ ONLY");}
   try{for(var row:rows){
    try(var q=db.prepareStatement("SELECT U.STATUS,C.PASSWORD_HASH,C.HASH_SCHEME,CASE WHEN C.LOCKED_UNTIL>SYSTIMESTAMP THEN 1 ELSE 0 END LOCKED,(SELECT COUNT(*) FROM M01_IAM_AUTH_FACTOR F WHERE F.USER_ID=U.USER_ID AND F.STATUS='ACTIVE') MFA FROM M01_IAM_USER U JOIN M01_IAM_CREDENTIAL C ON C.USER_ID=U.USER_ID WHERE U.USERNAME=?")){
     q.setString(1,row.path("username").asText());q.setQueryTimeout(30);
     try(var r=q.executeQuery()){
      boolean found=r.next();boolean matches=found&&"BCRYPT".equals(r.getString("HASH_SCHEME"))&&new BCryptPasswordEncoder().matches(row.path("password").asText(),r.getString("PASSWORD_HASH"));
      results.add(Map.of("username",row.path("username").asText(),"found",found,"passwordMatches",matches,"canLogin",matches&&"ACTIVE".equals(r.getString("STATUS"))&&r.getInt("LOCKED")==0&&r.getInt("MFA")==0));
     }
    }
   }}finally{db.rollback();}
  }
  Files.writeString(Path.of(args[0]),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("database","Oracle","results",results)));
 }
}
