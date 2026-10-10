package com.mojie.common.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

public final class JsonSupport {
  public static final ObjectMapper JSON = JsonMapper.builder().build();

  private JsonSupport() {}

  public static String encode(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public static JsonNode tree(String value) {
    try {
      return JSON.readTree(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public static String hash(String value) {
    return hash(value.getBytes(StandardCharsets.UTF_8));
  }

  public static String hash(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
