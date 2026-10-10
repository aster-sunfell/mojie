package com.mojie.config;

import com.mojie.common.pagination.PageCursor;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.infrastructure.transaction.SpringTransactionRunner;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class TransactionConfiguration {
  @Bean
  TransactionRunner transactions(PlatformTransactionManager manager) {
    return new SpringTransactionRunner(manager);
  }

  @Bean
  PageCursor pageCursor() {
    return PageCursor.configured();
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  UUID processId() {
    return UUID.randomUUID();
  }
}
