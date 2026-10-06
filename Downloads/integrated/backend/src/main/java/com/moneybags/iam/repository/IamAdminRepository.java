package com.moneybags.iam.repository;

import com.moneybags.common.database.*;
import com.moneybags.iam.model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class IamAdminRepository {
    private final JdbcTemplate jdbc;
    private final SchemaRepository schema;
    public IamAdminRepository(JdbcTemplate jdbc,SchemaRepository schema) {this.jdbc=jdbc;this.schema=schema;}
    public JdbcTemplate jdbc(){return jdbc;}
    public <T extends Record> List<T> rows(String sql,Class<T> type,Object... values) {
        return jdbc.query(sql,new SchemaRowMapper<>(type),values);
    }
    public <T extends Record> Optional<T> one(String sql,Class<T> type,Object... values) {
        return rows(sql,type,values).stream().findFirst();
    }
    public void insert(SchemaTable table,Map<String,Object> values) {schema.insert(table,values);}
    public long count(String sql,Object... values) {Long n=jdbc.queryForObject(sql,Long.class,values);return n==null?0:n;}
    public M01IamUserRow user(String id,boolean lock) {
        return one("SELECT * FROM M01_IAM_USER WHERE USER_ID=?"+(lock?" FOR UPDATE":""),M01IamUserRow.class,id)
            .orElseThrow(()->new com.moneybags.common.api.BusinessException(org.springframework.http.HttpStatus.NOT_FOUND,"USER_NOT_FOUND","User not found"));
    }
}
