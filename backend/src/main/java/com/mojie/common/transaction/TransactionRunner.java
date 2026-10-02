package com.mojie.common.transaction;

import java.util.function.Supplier;

public interface TransactionRunner {
  <T> T required(Supplier<T> work);

  <T> T requiresNew(Supplier<T> work);

  <T> T readSnapshot(Supplier<T> work);
}
