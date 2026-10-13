package com.moneybags;
import com.moneybags.payments.core.*;
import com.moneybags.payments.api.Contracts.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;

/** Real Spring binding against a driver that must never infer nullable parameter types. */
class PaymentJdbcTypesTest {
 record Fixture(JdbcTemplate jdbc,PreparedStatement statement){}
 Fixture fixture()throws Exception{
  DataSource source=mock(DataSource.class);Connection connection=mock(Connection.class);PreparedStatement ps=mock(PreparedStatement.class);
  when(source.getConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(ps);when(ps.executeUpdate()).thenReturn(1);
  when(ps.getParameterMetaData()).thenThrow(new AssertionError("Oracle parameter metadata must not be requested"));
  return new Fixture(spy(new JdbcTemplate(source)),ps);
 }
 Payment payment(){return new Payment(42,"TEST","OUTBOUND","PAYMENT","IMPS",new BigDecimal("10"),"RECEIVED",0,null,null,null,null,null,null,null,null);}
 @Test void nullablePaymentUpdateAndHistoryUseExplicitTypes()throws Exception{
  var f=fixture();new PaymentRepository(f.jdbc()).advance(payment(),new Transition("AUTHORIZED",0,null,null,null,null,null,null));
  verify(f.statement(),never()).getParameterMetaData();
  verify(f.statement()).setNull(2,Types.NUMERIC);verify(f.statement()).setNull(3,Types.NUMERIC);
  verify(f.statement()).setNull(4,Types.VARCHAR);verify(f.statement()).setNull(7,Types.NUMERIC);
  verify(f.statement()).setNull(9,Types.NUMERIC);verify(f.statement()).setNull(10,Types.NUMERIC);
 }
 @Test void nullableDispatchInsertAndUpdateUseExplicitTypes()throws Exception{
  var f=fixture();PaymentRepository repo=mock(PaymentRepository.class);when(repo.get(42)).thenReturn(payment());
  doReturn(Map.of("DISPATCH_ID",7L,"PAYMENT_ID",42L,"DISPATCH_STATUS","READY","ATTEMPT_COUNT",0)).when(f.jdbc()).queryForMap(anyString(),eq(7L));
  new DispatchService(f.jdbc(),repo,mock(EventEmitter.class)).attempt(7,new DispatchAttempt("SEND","SENT",null,null));
  verify(f.statement(),never()).getParameterMetaData();
  verify(f.statement()).setNull(5,Types.VARCHAR);verify(f.statement()).setNull(6,Types.VARCHAR);verify(f.statement()).setNull(3,Types.VARCHAR);
 }
}
