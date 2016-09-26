package com.squareup.leakcanary;

public interface WatchExecutor {
  void execute(Retryable retryable);
}
