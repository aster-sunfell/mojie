package com.mojie.common.model;

import java.util.*;

public interface QueryResult {
  Map<String, Object> fields();

  static Map<String, Object> fields(QueryResult row) {
    return row == null ? null : row.fields();
  }

  static List<Map<String, Object>> fields(List<? extends QueryResult> rows) {
    return rows.stream().map(row -> row.fields()).toList();
  }
}
