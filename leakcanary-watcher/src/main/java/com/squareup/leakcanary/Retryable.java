package com.squareup.leakcanary;

public interface Retryable {

  enum Result {
    DONE, RETRY
  }

  Result run();
}
