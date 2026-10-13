package com.moneybags.common.database;

import com.moneybags.common.api.BusinessException;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Domain-owned SQL queries. This helper is never exposed as a generic CRUD API. */
@Repository
public class BusinessRepository {

  private final JdbcTemplate jdbc;
  private final SchemaRepository schema;

  public BusinessRepository(JdbcTemplate jdbc, SchemaRepository schema) {
    this.jdbc = jdbc;
    this.schema = schema;
  }

  /** Gives domain repositories access to parameterized SQL and transactional connections. */
  public JdbcTemplate jdbc() {
    return jdbc;
  }

  /** Maps explicit projections without serializing Oracle locator objects. */
  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
      sql,
      (rs, n) -> {
        Map<String, Object> row = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          Object value;
          int type = meta.getColumnType(i);
          String sqlType = meta.getColumnTypeName(i);
          // Oracle advertises TIMESTAMPTZ as vendor type -101; never hash driver object identities.
          if (
            type == Types.TIMESTAMP_WITH_TIMEZONE ||
            type == -101 ||
            type == -102
          ) value = rs.getObject(i, OffsetDateTime.class);
          else if ("DATE".equals(sqlType)) value = rs.getObject(
            i,
            LocalDate.class
          );
          else if (type == Types.TIMESTAMP) value = rs.getObject(
            i,
            LocalDateTime.class
          );
          else if (type == Types.DATE) value = rs.getObject(i, LocalDate.class);
          else if (type == Types.CLOB) value = rs.getString(i);
          else if (type == Types.BLOB) value = rs.getBytes(i);
          else value = rs.getObject(i);
          row.put(meta.getColumnLabel(i).toUpperCase(Locale.ROOT), value);
        }
        return row;
      },
      args
    );
  }

  /** Resolves a resource or returns the shared 404 response. */
  public Map<String, Object> one(String sql, Object... args) {
    return rows(sql, args)
      .stream()
      .findFirst()
      .orElseThrow(() ->
        new BusinessException(
          HttpStatus.NOT_FOUND,
          "NOT_FOUND",
          "Record not found"
        )
      );
  }

  public long count(String sql, Object... args) {
    Long n = jdbc.queryForObject(sql, Long.class, args);
    return n == null ? 0 : n;
  }

  public void insert(SchemaTable table, Map<String, Object> values) {
    schema.insert(table, values);
  }

  public Number key(
    SchemaTable table,
    Map<String, Object> values,
    String name
  ) {
    return schema.insertWithKey(table, values, name);
  }

  public static String str(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), null);
  }

  public static LocalDate localDate(Object value) {
    if (value == null) return null;
    if (value instanceof LocalDate d) return d;
    if (value instanceof LocalDateTime d) return d.toLocalDate();
    if (value instanceof OffsetDateTime d) return d.toLocalDate();
    if (value instanceof java.sql.Date d) return d.toLocalDate();
    if (value instanceof Timestamp d) return d.toLocalDateTime().toLocalDate();
    throw new IllegalArgumentException("Unsupported database date type");
  }

  public static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  /** Optimistic version checks occur after obtaining the resource lock. */
  public static void version(Map<String, Object> row, long expected) {
    if (number(row, "ROW_VERSION") != expected) throw new BusinessException(
      HttpStatus.CONFLICT,
      "VERSION_CONFLICT",
      "Reload the record before changing it"
    );
  }
}
