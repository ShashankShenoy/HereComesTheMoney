import java.sql.*;
import java.nio.file.*;
public class ProductOracleQuery {
 public static void main(String[] args) throws Exception {
  try(Connection db=DriverManager.getConnection(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))) {
   db.setAutoCommit(false);
   for(String sql:Files.readString(Path.of(args[0])).split(";")) {
    if(sql.isBlank())continue;
    if(!sql.stripLeading().toUpperCase().startsWith("SELECT"))throw new IllegalArgumentException("Read-only SELECT required");
    System.out.println("QUERY "+sql.strip().replaceAll("\\s+"," "));
    try(Statement st=db.createStatement()){ st.setQueryTimeout(30); try(ResultSet rs=st.executeQuery(sql)){var meta=rs.getMetaData(); while(rs.next()){StringBuilder line=new StringBuilder();for(int i=1;i<=meta.getColumnCount();i++){if(i>1)line.append(" | ");line.append(meta.getColumnLabel(i)).append("=").append(rs.getString(i));}System.out.println(line);}} }
   } db.rollback();
  }
 }
}
