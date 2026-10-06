package com.moneybags.common.database;

import org.springframework.jdbc.core.RowMapper;
import java.lang.reflect.*;
import java.sql.*;
import java.math.BigDecimal;
import java.time.*;

/** Maps exact Oracle column names to typed record components. */
public final class SchemaRowMapper<T extends Record> implements RowMapper<T> {
    private final RecordComponent[] components;
    private final Constructor<T> constructor;
    public SchemaRowMapper(Class<T> type) {
        components=type.getRecordComponents();
        try { constructor=type.getDeclaredConstructor(java.util.Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new)); }
        catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Invalid row model",e); }
    }
    @Override public T mapRow(ResultSet rs,int rowNum) throws SQLException {
        Object[] values=new Object[components.length];
        for(int i=0;i<components.length;i++) {
            var component=components[i];
            var column=component.getAnnotation(DbColumn.class);
            Class<?> type=component.getType();
            String name=column.name();
            if(type==String.class) values[i]=rs.getString(name);
            else if(type==byte[].class) values[i]=rs.getBytes(name);
            else if(type==BigDecimal.class) values[i]=rs.getBigDecimal(name);
            else if(type==Long.class) { long value=rs.getLong(name); values[i]=rs.wasNull()?null:value; }
            else if(type==LocalDate.class) { Date value=rs.getDate(name); values[i]=value==null?null:value.toLocalDate(); }
            else if(type==LocalDateTime.class) { Timestamp value=rs.getTimestamp(name); values[i]=value==null?null:value.toLocalDateTime(); }
            else if(type==OffsetDateTime.class) values[i]=rs.getObject(name,OffsetDateTime.class);
            else throw new SQLException("Unsupported mapped type: "+type.getName());
        }
        try { return constructor.newInstance(values); }
        catch(ReflectiveOperationException e) { throw new SQLException("Cannot construct row record",e); }
    }
}
