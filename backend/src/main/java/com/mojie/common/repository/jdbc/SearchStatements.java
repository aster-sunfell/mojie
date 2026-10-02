package com.mojie.common.repository.jdbc;

import com.mojie.common.cancellation.QueryCancellation;
import java.sql.*;

public final class SearchStatements {
  private SearchStatements() {}

  public static void timeout(Connection connection, long timeoutMillis, boolean lock)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config('statement_timeout', ?, true)")) {
      statement.setString(1, Math.max(1, Math.min(timeoutMillis, 5_000)) + "ms");
      statement.execute();
    }
    if (lock)
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT set_config('lock_timeout', ?, true)")) {
        statement.setString(1, Math.max(1, Math.min(timeoutMillis, 1_000)) + "ms");
        statement.execute();
      }
  }

  public static QueryCancellation.Registration cancel(
      QueryCancellation context, Statement statement) {
    if (context == null) return () -> {};
    return context.register(
        () -> {
          try {
            statement.cancel();
          } catch (SQLException ignored) {
          }
        });
  }

  public static void check(QueryCancellation context) {
    if (context != null && context.cancelled())
      throw new java.util.concurrent.CancellationException("Search cancelled");
  }
}
