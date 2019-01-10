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

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import java.io.Serializable;

public final class AnalysisResult implements Serializable {

  public static final long RETAINED_HEAP_SKIPPED = -1;

  public static @NonNull AnalysisResult noLeak(String className, long analysisDurationMs) {
    return new AnalysisResult(false, false, className, null, null, 0, analysisDurationMs);
  }

  public static @NonNull AnalysisResult leakDetected(boolean excludedLeak,
      @NonNull String className,
      @NonNull LeakTrace leakTrace, long retainedHeapSize, long analysisDurationMs) {
    return new AnalysisResult(true, excludedLeak, className, leakTrace, null, retainedHeapSize,
        analysisDurationMs);
  }

  public static @NonNull AnalysisResult failure(@NonNull Throwable failure,
      long analysisDurationMs) {
    return new AnalysisResult(false, false, null, null, failure, 0, analysisDurationMs);
  }

  /** True if a leak was found in the heap dump. */
  public final boolean leakFound;

  /**
   * True if {@link #leakFound} is true and the only path to the leaking reference is
   * through excluded references. Usually, that means you can safely ignore this report.
   */
  public final boolean excludedLeak;

  /**
   * Class name of the object that leaked, null if {@link #failure} is not null.
   * The class name format is the same as what would be returned by {@link Class#getName()}.
   */
  @Nullable public final String className;

  /**
   * Shortest path to GC roots for the leaking object if {@link #leakFound} is true, null
   * otherwise. This can be used as a unique signature for the leak.
   */
  @Nullable public final LeakTrace leakTrace;

  /** Null unless the analysis failed. */
  @Nullable public final Throwable failure;

  /**
   * The number of bytes which would be freed if all references to the leaking object were
   * released. {@link #RETAINED_HEAP_SKIPPED} if the retained heap size was not computed. 0 if
   * {@link #leakFound} is false.
   */
  public final long retainedHeapSize;

  /** Total time spent analyzing the heap. */
  public final long analysisDurationMs;

  /**
   * <p>Creates a new {@link RuntimeException} with a fake stack trace that maps the leak trace.
   *
   * <p>Leak traces uniquely identify memory leaks, much like stack traces uniquely identify
   * exceptions.
   *
   * <p>This method enables you to upload leak traces as stack traces to your preferred
   * exception reporting tool and benefit from the grouping and counting these tools provide out
   * of the box. This also means you can track all leaks instead of relying on individuals
   * reporting them when they happen.
   *
   * <p>The following example leak trace:
   * <pre>
   * * com.foo.WibbleActivity has leaked:
   * * GC ROOT static com.foo.Bar.qux
   * * references com.foo.Quz.context
   * * leaks com.foo.WibbleActivity instance
   * </pre>
   *
   * <p>Will turn into an exception with the following stacktrace:
   * <pre>
   * java.lang.RuntimeException: com.foo.WibbleActivity leak from com.foo.Bar (holder=CLASS,
   * type=STATIC_FIELD)
   *         at com.foo.Bar.qux(Bar.java:42)
   *         at com.foo.Quz.context(Quz.java:42)
   *         at com.foo.WibbleActivity.leaking(WibbleActivity.java:42)
   * </pre>
   */
  public @NonNull RuntimeException leakTraceAsFakeException() {
    if (!leakFound) {
      throw new UnsupportedOperationException(
          "leakTraceAsFakeException() can only be called when leakFound is true");
    }
    LeakTraceElement firstElement = leakTrace.elements.get(0);
    String rootSimpleName = classSimpleName(firstElement.className);
    String leakSimpleName = classSimpleName(className);

    String exceptionMessage = leakSimpleName
        + " leak from "
        + rootSimpleName
        + " (holder="
        + firstElement.holder
        + ", type="
        + firstElement.type
        + ")";
    RuntimeException exception = new RuntimeException(exceptionMessage);

    StackTraceElement[] stackTrace = new StackTraceElement[leakTrace.elements.size()];
    int i = 0;
    for (LeakTraceElement element : leakTrace.elements) {
      String methodName = element.referenceName != null ? element.referenceName : "leaking";
      String file = classSimpleName(element.className) + ".java";
      stackTrace[i] = new StackTraceElement(element.className, methodName, file, 42);
      i++;
    }
    exception.setStackTrace(stackTrace);
    return exception;
  }

  private AnalysisResult(boolean leakFound, boolean excludedLeak, String className,
      LeakTrace leakTrace, Throwable failure, long retainedHeapSize, long analysisDurationMs) {
    this.leakFound = leakFound;
    this.excludedLeak = excludedLeak;
    this.className = className;
    this.leakTrace = leakTrace;
    this.failure = failure;
    this.retainedHeapSize = retainedHeapSize;
    this.analysisDurationMs = analysisDurationMs;
  }

  private String classSimpleName(String className) {
    int separator = className.lastIndexOf('.');
    return separator == -1 ? className : className.substring(separator + 1);
  }
}