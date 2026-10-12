package com.mojie.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import com.mojie.agent.service.port.ModelGateway.TextDelta;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ModelTextBufferTest {
  @Test void bufferedTextIsFlushedWhileTheProviderRemainsOpenAndTheTailIsFlushedOnClose() throws Exception {
    List<String> visible = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch periodic = new CountDownLatch(1);
    try (var buffer = new ModelTextBuffer(delta -> {
      visible.add(delta.text());
      if (visible.size() == 2) periodic.countDown();
    })) {
      buffer.accept(new TextDelta("第一段"));
      assertEquals(List.of("第一段"), visible);
      buffer.accept(new TextDelta("中间"));
      buffer.accept(new TextDelta("内容"));
      assertTrue(periodic.await(2, TimeUnit.SECONDS));
      assertEquals("第一段中间内容", String.join("", visible));
      buffer.accept(new TextDelta("结尾"));
    }
    assertEquals("第一段中间内容结尾", String.join("", visible));
  }
}
