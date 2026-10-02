package com.mojie.support;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** Each integration test owns an isolated schema in the explicitly configured test database. */
public final class TestDatabase implements AutoCloseable {
  private final PGSimpleDataSource root = new PGSimpleDataSource();
  private final PGSimpleDataSource source = new PGSimpleDataSource();
  private final String schema = "mojie_tx_" + UUID.randomUUID().toString().replace("-", "");

  public TestDatabase() throws SQLException {
    String url = System.getenv("MOJIE_TEST_JDBC_URL");
    assumeTrue(url != null && !url.isBlank(), "Dedicated PostgreSQL test database not configured");
    String user = System.getenv().getOrDefault("MOJIE_TEST_DB_USER", "postgres");
    String password = System.getenv().getOrDefault("MOJIE_TEST_DB_PASSWORD", "");
    for (PGSimpleDataSource ds : new PGSimpleDataSource[] {root, source}) {
      ds.setURL(url);
      ds.setUser(user);
      ds.setPassword(password);
    }
    try (Connection connection = root.getConnection();
        Statement sql = connection.createStatement()) {
      sql.execute("CREATE SCHEMA " + schema);
    }
    source.setCurrentSchema(schema);
  }

  public DataSource source() {
    return source;
  }

  public void close() throws SQLException {
    try (Connection connection = root.getConnection();
        Statement sql = connection.createStatement()) {
      sql.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }
}
