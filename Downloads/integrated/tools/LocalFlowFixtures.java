import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicit fixture preparation only for the disposable loopback H2 test database. */
public class LocalFlowFixtures {
    public static void main(String[] args) throws Exception {
        String url="jdbc:h2:tcp://127.0.0.1:19095/mem:moneybags;MODE=Oracle;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH";
        try(Connection db=DriverManager.getConnection(url,"sa","")){
            if(!db.getMetaData().getDatabaseProductName().equals("H2"))throw new IllegalStateException("H2 fixtures only");
            db.setAutoCommit(false);
            try{
                try(Statement query=db.createStatement();ResultSet result=query.executeQuery("SELECT COUNT(*) FROM M04_BANK_ACCOUNT WHERE ACCOUNT_ID IN (1,2) AND PRIMARY_CIF_ID IN ('demo-cif-1','demo-cif-2')")){
                    result.next();if(result.getInt(1)!=2)throw new IllegalStateException("Disposable synthetic account pair required");
                }
                if(args[0].equals("purpose")){
                    String code=args[1];if(!code.startsWith("FLOW_"))throw new IllegalArgumentException("Test purpose prefix required");
                    try(PreparedStatement command=db.prepareStatement("INSERT INTO M09_PRIVACY_PROCESSING_PURPOSE(PROCESSING_PURPOSE_ID,PURPOSE_CODE,PURPOSE_VERSION,PURPOSE_NAME,OWNER_SERVICE,LEGAL_BASIS_CODE,NOTICE_URI,NOTICE_SHA256,STATUS,EFFECTIVE_FROM,APPROVED_AT,APPROVED_BY,CREATED_BY,UPDATED_BY) VALUES (?,?,1,'Synthetic flow notice','CIF','CONSENT','https://bank.example/privacy',?,'ACTIVE',?,SYSTIMESTAMP,'checker','admin','checker')")){
                        command.setString(1,UUID.randomUUID().toString());command.setString(2,code);command.setString(3,"a".repeat(64));command.setObject(4,OffsetDateTime.now().minusMinutes(1));command.executeUpdate();
                    }
                }else if(args[0].equals("due-installment")){
                    long facility=Long.parseLong(args[1]);
                    try(PreparedStatement query=db.prepareStatement("SELECT COUNT(*) FROM M08_LOAN_FACILITY F JOIN M08_LOAN_APPLICATION A ON A.APPLICATION_ID=F.APPLICATION_ID WHERE F.FACILITY_ID=? AND A.PRIMARY_CIF_ID='demo-cif-1' AND A.CORRELATION_ID LIKE 'flow-loan-%'")){
                        query.setLong(1,facility);try(ResultSet result=query.executeQuery()){result.next();if(result.getInt(1)!=1)throw new IllegalArgumentException("Only this suite's synthetic loan can be aged");}
                    }
                    try(PreparedStatement command=db.prepareStatement("UPDATE M08_LOAN_SCHEDULE_ITEM SET DUE_DATE=? WHERE FACILITY_ID=? AND INSTALLMENT_NO=1")){
                        command.setObject(1,LocalDate.now(ZoneId.of("Asia/Kolkata")));command.setLong(2,facility);if(command.executeUpdate()!=1)throw new IllegalStateException("Missing test installment");
                    }
                }else throw new IllegalArgumentException("Unknown test fixture action");
                db.commit();System.out.println("PASS: isolated H2 fixture prepared");
            }catch(Throwable failure){db.rollback();throw failure;}
        }
    }
}
