package com.mojie.skill.model;

public final class RecoveryModels {
  private RecoveryModels() {}

  public record FailedUpload(String id) {}

  public record Retained(String id) {}

  public record ExecutionHandle(String id) {}
}
