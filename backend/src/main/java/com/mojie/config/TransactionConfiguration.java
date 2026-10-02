package com.mojie.config;

import com.mojie.common.pagination.PageCursor;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.common.web.ActorProvider;
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
  ActorProvider actorProvider() {
    String owner = System.getenv().getOrDefault("MOJIE_OWNER_ID", "local-author");
    return () -> owner;
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
