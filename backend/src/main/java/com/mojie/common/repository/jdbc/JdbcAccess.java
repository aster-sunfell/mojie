package com.mojie.common.repository.jdbc;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.common.error.StorageException;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

public final class JdbcAccess {
  private final DataSource source;
  private final JsonMapper json = JsonMapper.builder().build();

  public JdbcAccess(DataSource source) {
    this.source = Objects.requireNonNull(source);
  }

  @FunctionalInterface
  public interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  public <T> T execute(Work<T> work) {
    Connection connection = null;
    try {
      connection = DataSourceUtils.getConnection(source);
      return work.run(connection);
    } catch (SQLException | org.springframework.dao.DataAccessException e) {
      throw new StorageException("Database operation failed", e);
    } finally {
      if (connection != null) DataSourceUtils.releaseConnection(connection, source);
    }
  }

  public List<Map<String, Object>> query(String sql, Object... args) {
    return execute(
        c -> {
          try (PreparedStatement s = statement(c, sql, args);
              ResultSet rs = s.executeQuery()) {
            List<Map<String, Object>> result = new ArrayList<>();
            while (rs.next()) result.add(row(rs));
            return result;
          }
        });
  }

  public Map<String, Object> first(String sql, Object... args) {
    List<Map<String, Object>> rows = query(sql, args);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public int update(String sql, Object... args) {
    return execute(
        c -> {
          try (PreparedStatement s = statement(c, sql, args)) {
            return s.executeUpdate();
          }
        });
  }

  public <T> T firstRecord(Class<T> type, String sql, Object... args) {
    Map<String, Object> row = first(sql, args);
    return row == null ? null : convert(type, row);
  }

  public <T> List<T> records(Class<T> type, String sql, Object... args) {
    return query(sql, args).stream().map(row -> convert(type, row)).toList();
  }

  private <T> T convert(Class<T> type, Map<String, Object> row) {
    try {
      return json.convertValue(row, type);
    } catch (Exception e) {
      throw new StorageException("Stored row is invalid", e);
    }
  }

  public static PreparedStatement statement(Connection c, String sql, Object... args)
      throws SQLException {
    PreparedStatement statement = c.prepareStatement(sql);
    for (int i = 0; i < args.length; i++) {
      Object value = args[i];
      if (value instanceof Long[] numbers)
        statement.setArray(i + 1, c.createArrayOf("bigint", numbers));
      else statement.setObject(i + 1, value);
    }
    return statement;
  }

  private Map<String, Object> row(ResultSet rs) throws SQLException {
    Map<String, Object> value = new LinkedHashMap<>();
    ResultSetMetaData metadata = rs.getMetaData();
    for (int i = 1; i <= metadata.getColumnCount(); i++) {
      String name = metadata.getColumnLabel(i);
      String[] parts = name.split("_");
      StringBuilder key = new StringBuilder(parts[0]);
      for (int j = 1; j < parts.length; j++)
        key.append(Character.toUpperCase(parts[j].charAt(0))).append(parts[j].substring(1));
      Object item = rs.getObject(i);
      if (item instanceof Timestamp timestamp) item = timestamp.toInstant().toString();
      else if (item instanceof UUID || item instanceof OffsetDateTime) item = item.toString();
      else if (item instanceof org.postgresql.util.PGobject object) item = object.getValue();
      else if (item instanceof java.sql.Array array) {
        item = Arrays.asList((Object[]) array.getArray());
        array.free();
      }
      if (item != null && Set.of("json", "jsonb").contains(metadata.getColumnTypeName(i))) {
        try {
          item = json.readTree(item.toString());
        } catch (Exception e) {
          throw new SQLException("Stored JSON is invalid", e);
        }
      }
      value.put(key.toString(), item);
    }
    return value;
  }
}
