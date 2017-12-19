package com.squareup.leakcanary;

/**
 * A {@link WatchExecutor} is in charge of executing a {@link Retryable} in the future, and retry
 * later if needed.
 */
public interface WatchExecutor {
  WatchExecutor NONE = new WatchExecutor() {
    @Override public void execute(Retryable retryable) {
    }
  };

  void execute(Retryable retryable);
}
