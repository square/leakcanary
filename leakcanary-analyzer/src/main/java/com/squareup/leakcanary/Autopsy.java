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
import java.util.Collections;
import java.util.List;

import static com.squareup.leakcanary.HahaHelper.getStackTraceString;

public final class Autopsy implements Serializable {

  public static Autopsy result(List<LeakTrace> leakTraces, long analysisDurationMs) {
    return new Autopsy(leakTraces, null, analysisDurationMs);
  }

  public static Autopsy failure(Throwable failure, long analysisDurationMs) {
    return new Autopsy(Collections.<LeakTrace>emptyList(), failure, analysisDurationMs);
  }

  public final List<LeakTrace> leakTraces;

  /** Null unless the analysis failed. */
  public final Throwable failure;

  /** Total time spent analyzing the heap. */
  public final long analysisDurationMs;

  private Autopsy(List<LeakTrace> leakTraces, Throwable failure, long analysisDurationMs) {
    this.leakTraces = leakTraces;
    this.failure = failure;
    this.analysisDurationMs = analysisDurationMs;
  }

  @Override public String toString() {
    if (failure != null) {
      return getStackTraceString(failure);
    }
    StringBuilder sb = new StringBuilder();
    for (LeakTrace leakTrace : leakTraces) {
      sb.append(leakTrace).append("\n");
    }
    return sb.toString();
  }
}