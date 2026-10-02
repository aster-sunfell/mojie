package com.mojie.infrastructure.transaction;

import com.mojie.common.error.StorageException;
import com.mojie.common.transaction.TransactionRunner;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

public final class SpringTransactionRunner implements TransactionRunner {
  private final TransactionTemplate required, requiresNew, snapshot;

  public SpringTransactionRunner(PlatformTransactionManager manager) {
    required = new TransactionTemplate(manager);
    requiresNew = new TransactionTemplate(manager);
    requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    snapshot = new TransactionTemplate(manager);
    snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setReadOnly(true);
  }

  public <T> T required(Supplier<T> work) {
    return run(required, work);
  }

  public <T> T requiresNew(Supplier<T> work) {
    return run(requiresNew, work);
  }

  public <T> T readSnapshot(Supplier<T> work) {
    return run(snapshot, work);
  }

  private <T> T run(TransactionTemplate template, Supplier<T> work) {
    try {
      return template.execute(status -> work.get());
    } catch (TransactionException | DataAccessException e) {
      throw new StorageException("Database transaction failed", e);
    }
  }
}
