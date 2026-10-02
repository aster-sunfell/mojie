package com.mojie.content.model;

import com.mojie.common.error.BusinessException;
import com.mojie.common.pagination.*;
import java.util.*;
import java.util.function.Function;

public final class ContentRules {
  private ContentRules() {}

  public static BusinessException error(String code, String message) {
    return new BusinessException(code, message);
  }

  public static String text(String value, String field, int min, int max) {
    if (value == null) throw error("VALIDATION_ERROR", "Invalid " + field);
    String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
    int length = normalized.codePointCount(0, normalized.length());
    if (length < min || length > max || (min > 0 && normalized.isBlank()))
      throw error("VALIDATION_ERROR", "Invalid " + field);
    return normalized;
  }

  public static String uuid(String value) {
    try {
      return UUID.fromString(value).toString();
    } catch (RuntimeException e) {
      throw error("VALIDATION_ERROR", "Invalid ID");
    }
  }

  public static String type(String value) {
    if (value == null || !Set.of("CHARACTER", "WORLD", "RULE").contains(value))
      throw error("VALIDATION_ERROR", "Invalid setting type");
    return value;
  }

  public static int limit(Integer value) {
    if (value == null) return 20;
    if (value < 1 || value > 50) throw error("VALIDATION_ERROR", "Invalid limit");
    return value;
  }

  public static String[] pair(String marker) {
    if (marker == null) return new String[] {null, null};
    String[] parts = marker.split("\\|", -1);
    if (parts.length != 2) throw new IllegalArgumentException("Invalid cursor");
    return parts;
  }

  public static Page<Map<String, Object>> page(
      List<Map<String, Object>> rows,
      int size,
      String scope,
      Function<Map<String, Object>, String> marker,
      PageCursor cursors) {
    if (rows.size() <= size) return new Page<>(rows, null);
    List<Map<String, Object>> items = rows.subList(0, size);
    return new Page<>(items, cursors.encode(scope, marker.apply(items.getLast())));
  }
}
