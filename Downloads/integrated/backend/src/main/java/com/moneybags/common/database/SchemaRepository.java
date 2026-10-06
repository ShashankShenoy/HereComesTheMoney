package com.moneybags.common.database;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.sql.*;
import java.time.*;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Internal persistence helper. Never exposed as an unrestricted CRUD API. */
@Repository
public class SchemaRepository {
    private final JdbcTemplate jdbc;
    public SchemaRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public <T extends Record> List<T> find(SchemaTable table, Class<T> type, Map<String,Object> equals) {
        if(type!=table.rowType()) throw new IllegalArgumentException("Row type does not match table");
        var columns=columns(table);
        equals.keySet().forEach(name->check(columns,name));
        String where=equals.isEmpty()?"":" WHERE "+String.join(" AND ",equals.keySet().stream().map(k->k+" = ?").toList());
        return jdbc.query("SELECT "+String.join(",",columns.keySet())+" FROM "+table.name()+where,
                new SchemaRowMapper<>(type),equals.values().toArray());
    }
    public int insert(SchemaTable table, Map<String,Object> values) {
        validateInsert(table,values);
        // Names and types are already known from the registry; do not perform Oracle catalogue/PLSQL lookups.
        return jdbc.update(connection->{var statement=connection.prepareStatement(insertSql(table,values));bind(statement,table,values);return statement;});
    }
    public Number insertWithKey(SchemaTable table, Map<String,Object> values,String identityColumn) {
        validateInsert(table,values);
        var col=columns(table).get(identityColumn);
        if(col==null||!col.identity()) throw new IllegalArgumentException("Expected an identity key column");
        var key=new GeneratedKeyHolder();
        jdbc.update(connection->{var statement=connection.prepareStatement(insertSql(table,values),new String[]{identityColumn});bind(statement,table,values);return statement;},key);
        Number number=key.getKey();if(number==null)throw new IllegalStateException("Database did not return the identity key");return number;
    }
    private static String insertSql(SchemaTable table,Map<String,Object> values){return "INSERT INTO "+table.name()+" ("+String.join(",",values.keySet())+") VALUES ("+String.join(",",Collections.nCopies(values.size(),"?"))+")";}
    private static void bind(PreparedStatement statement,SchemaTable table,Map<String,Object> values)throws SQLException {
        Map<String,Class<?>> types=new HashMap<>();for(var component:table.rowType().getRecordComponents())types.put(component.getAnnotation(DbColumn.class).name(),component.getType());
        int index=1;for(var entry:values.entrySet()){
            Object value=entry.getValue();Class<?> type=types.get(entry.getKey());
            if(value==null){int sqlType=type==OffsetDateTime.class?Types.TIMESTAMP_WITH_TIMEZONE:type==LocalDateTime.class?Types.TIMESTAMP:type==LocalDate.class?Types.DATE:type==byte[].class?Types.VARBINARY:type==String.class?Types.VARCHAR:Types.NUMERIC;statement.setNull(index,sqlType);}
            else if(value instanceof OffsetDateTime time)statement.setObject(index,time,Types.TIMESTAMP_WITH_TIMEZONE);
            else if(value instanceof LocalDateTime time)statement.setObject(index,time,Types.TIMESTAMP);
            else if(value instanceof LocalDate day)statement.setObject(index,day,Types.DATE);
            else if(value instanceof byte[] bytes)statement.setBytes(index,bytes);
            else statement.setObject(index,value);
            index++;
        }
    }
    public static Map<String,DbColumn> columns(SchemaTable table) {
        Map<String,DbColumn> result=new LinkedHashMap<>();
        for(var c:table.rowType().getRecordComponents()) { var mapping=c.getAnnotation(DbColumn.class); result.put(mapping.name(),mapping); }
        return result;
    }
    private static void check(Map<String,DbColumn> columns,String name) {
        if(!columns.containsKey(name)) throw new IllegalArgumentException("Unknown mapped column: "+name);
    }
    private static void validateInsert(SchemaTable table,Map<String,Object> values) {
        if(values.isEmpty()) throw new IllegalArgumentException("Insert requires values");
        var columns=columns(table);
        for(String name:values.keySet()) { check(columns,name); if(columns.get(name).generated()) throw new IllegalArgumentException("Database owns generated column: "+name); }
    }
}
