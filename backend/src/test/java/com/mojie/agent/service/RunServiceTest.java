package com.mojie.agent.service;

import static org.junit.jupiter.api.Assertions.*;

import com.mojie.agent.model.RunAdmission.Request;
import com.mojie.common.error.BusinessException;
import org.junit.jupiter.api.Test;

class RunServiceTest {
  @Test
  void unconfiguredModelPreservesItsErrorBeforeNullableVersionValidation() {
    var runs = new RunService(null, null, null, null, null, null, null, false);
    Request request = new Request("owner", "project", "chapter", (Long) null, "instruction", "key");
    assertEquals(
        "MODEL_ERROR", assertThrows(BusinessException.class, () -> runs.submit(request)).code());
  }

  @Test
  void configuredModelRequiresVersionBeforeRuntimeAccess() {
    var runs = new RunService(null, null, null, null, null, null, null, true);
    Request request = new Request("owner", "project", "chapter", (Long) null, "instruction", "key");
    assertEquals(
        "expectedVersion is required",
        assertThrows(IllegalArgumentException.class, () -> runs.submit(request)).getMessage());
  }
}
