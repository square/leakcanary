package com.squareup.leakcanary;

public interface Clock {
  /** See Android SystemClock.uptimeMillis(). */
  long uptimeMillis();
}
