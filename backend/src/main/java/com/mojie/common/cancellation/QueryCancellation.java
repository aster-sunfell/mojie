package com.mojie.common.cancellation;

public interface QueryCancellation {
  interface Registration extends AutoCloseable {
    @Override
    void close();
  }

  boolean cancelled();

  Registration register(Runnable callback);
}
