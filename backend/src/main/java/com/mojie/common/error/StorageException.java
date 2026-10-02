package com.mojie.common.error;

public final class StorageException extends RuntimeException {
  private final String sqlState;

  public StorageException(String message, Throwable cause) {
    super(message, cause);
    String state = null;
    for (Throwable current = cause; current != null; current = current.getCause())
      if (current instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
        state = sql.getSQLState();
        break;
      }
    sqlState = state;
  }

  public boolean hasSqlState(String state) {
    return state.equals(sqlState);
  }
}
