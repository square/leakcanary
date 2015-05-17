/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary;

import java.io.Serializable;

public final class AnalysisResult implements Serializable {

  public static AnalysisResult noLeak(long analysisDurationMs) {
    return new AnalysisResult(false, false, null, null, null, analysisDurationMs);
  }

  public static AnalysisResult leakDetected(boolean excludedLeak, String className,
      LeakTrace leakTrace, long analysisDurationMs) {
    return new AnalysisResult(true, excludedLeak, className, leakTrace, null, analysisDurationMs);
  }

  public static AnalysisResult failure(Exception exception, long analysisDurationMs) {
    return new AnalysisResult(false, false, null, null, exception, analysisDurationMs);
  }

  /** True if a leak was found in the heap dump. */
  public final boolean leakFound;

  /**
   * True if {@link #leakFound} is true and the only path to the leaking reference is
   * through excluded references. Usually, that means you can safely ignore this report.
   */
  public final boolean excludedLeak;

  /**
   * Class name of the object that leaked if {@link #leakFound} is true, null otherwise.
   * The class name format is the same as what would be returned by {@link Class#getName()}.
   */
  public final String className;

  /**
   * Shortest path to GC roots for the leaking object if {@link #leakFound} is true, null
   * otherwise. This can be used as a unique signature for the leak.
   */
  public final LeakTrace leakTrace;

  /** Null unless the analysis failed. */
  public final Exception failure;

  /** Total time spent analyzing the heap. */
  public final long analysisDurationMs;

  private AnalysisResult(boolean leakFound, boolean excludedLeak, String className,
      LeakTrace leakTrace, Exception failure, long analysisDurationMs) {
    this.leakFound = leakFound;
    this.excludedLeak = excludedLeak;
    this.className = className;
    this.leakTrace = leakTrace;
    this.failure = failure;
    this.analysisDurationMs = analysisDurationMs;
  }
}