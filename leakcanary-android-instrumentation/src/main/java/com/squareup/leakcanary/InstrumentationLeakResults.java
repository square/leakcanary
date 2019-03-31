/*
 * Copyright (C) 2018 Square, Inc.
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

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Collections.unmodifiableList;

public final class InstrumentationLeakResults {

  @NonNull public static final InstrumentationLeakResults NONE =
      new InstrumentationLeakResults(Collections.<Result>emptyList(),
          Collections.<Result>emptyList(), Collections.<Result>emptyList());

  /** Proper leaks found during instrumentation tests. */
  @NonNull public final List<Result> detectedLeaks;

  /**
   * Excluded leaks found during instrumentation tests, based on {@link
   * RefWatcherBuilder#excludedRefs}
   */
  @NonNull public final List<Result> excludedLeaks;

  /**
   * Leak analysis failures that happened when we tried to detect leaks.
   */
  @NonNull public final List<Result> failures;

  public InstrumentationLeakResults(@NonNull List<Result> detectedLeaks,
      @NonNull List<Result> excludedLeaks, @NonNull List<Result> failures) {
    this.detectedLeaks = unmodifiableList(new ArrayList<>(detectedLeaks));
    this.excludedLeaks = unmodifiableList(new ArrayList<>(excludedLeaks));
    this.failures = unmodifiableList(new ArrayList<>(failures));
  }

  public static final class Result {
    @NonNull public final HeapDump heapDump;
    @NonNull public final AnalysisResult analysisResult;

    public Result(@NonNull HeapDump heapDump, @NonNull AnalysisResult analysisResult) {
      this.heapDump = heapDump;
      this.analysisResult = analysisResult;
    }
  }
}
