package com.mojie.common.pagination;

public final class ListPaging {
  private ListPaging() {}

  public record Position(String time, String id) {}

  public static int limit(int value) {
    if (value < 1 || value > 50) throw new IllegalArgumentException("limit must be 1..50");
    return value;
  }

  public static Position position(PageCursor cursor, String scope, String token) {
    String value = cursor.decode(scope, token);
    if (value == null) return null;
    String[] parts = value.split("\\|", -1);
    if (parts.length != 2) throw new IllegalArgumentException("Invalid cursor");
    try {
      java.time.Instant.parse(parts[0]);
      java.util.UUID.fromString(parts[1]);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid cursor");
    }
    return new Position(parts[0], parts[1]);
  }

  public static String encode(PageCursor cursor, String scope, String time, String id) {
    return cursor.encode(scope, time + "|" + id);
  }
}
