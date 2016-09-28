package com.squareup.leakcanary;

/** A unit of work that can be retried later. */
public interface Retryable {

  enum Result {
    DONE, RETRY
  }

  Result run();
}
