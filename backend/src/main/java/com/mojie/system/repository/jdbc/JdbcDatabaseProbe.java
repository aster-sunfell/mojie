package com.mojie.system.repository.jdbc;

import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.system.repository.DatabaseProbe;
import javax.sql.DataSource;

public final class JdbcDatabaseProbe implements DatabaseProbe {
  private final JdbcAccess db;

  public JdbcDatabaseProbe(DataSource source) {
    db = new JdbcAccess(source);
  }

  public boolean available() {
    try {
      return db.first("SELECT 1") != null;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }
}
