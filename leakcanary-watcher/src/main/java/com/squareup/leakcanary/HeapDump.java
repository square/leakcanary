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

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static com.squareup.leakcanary.Preconditions.checkNotNull;
import static java.util.Collections.unmodifiableList;

/** Data structure holding information about a heap dump. */
public final class HeapDump implements Serializable {

  public static Builder builder() {
    return new Builder();
  }

  /** Receives a heap dump to analyze. */
  public interface Listener {
    Listener NONE = new Listener() {
      @Override public void analyze(HeapDump heapDump) {
      }
    };

    void analyze(HeapDump heapDump);
  }

  /** The heap dump file, which you might want to upload somewhere. */
  public final File heapDumpFile;

  /** References that should be ignored when analyzing this heap dump. */
  public final ExcludedRefs excludedRefs;

  public final long gcDurationMs;
  public final long heapDumpDurationMs;
  public final boolean computeRetainedHeapSize;
  public final List<Class<? extends Reachability.Inspector>> reachabilityInspectorClasses;

  HeapDump(Builder builder) {
    this.heapDumpFile = builder.heapDumpFile;
    this.excludedRefs = builder.excludedRefs;
    this.computeRetainedHeapSize = builder.computeRetainedHeapSize;
    this.gcDurationMs = builder.gcDurationMs;
    this.heapDumpDurationMs = builder.heapDumpDurationMs;
    this.reachabilityInspectorClasses = builder.reachabilityInspectorClasses;
  }

  public Builder buildUpon() {
    return new Builder(this);
  }

  public static final class Builder {
    File heapDumpFile;
    ExcludedRefs excludedRefs;
    long gcDurationMs;
    long heapDumpDurationMs;
    boolean computeRetainedHeapSize;
    List<Class<? extends Reachability.Inspector>> reachabilityInspectorClasses;

    Builder() {
      this.heapDumpFile = null;
      excludedRefs = null;
      gcDurationMs = 0;
      heapDumpDurationMs = 0;
      computeRetainedHeapSize = false;
      reachabilityInspectorClasses = null;
    }

    Builder(HeapDump heapDump) {
      this.heapDumpFile = heapDump.heapDumpFile;
      this.excludedRefs = heapDump.excludedRefs;
      this.computeRetainedHeapSize = heapDump.computeRetainedHeapSize;
      this.gcDurationMs = heapDump.gcDurationMs;
      this.heapDumpDurationMs = heapDump.heapDumpDurationMs;
      this.reachabilityInspectorClasses = heapDump.reachabilityInspectorClasses;
    }

    public Builder heapDumpFile(File heapDumpFile) {
      this.heapDumpFile = checkNotNull(heapDumpFile, "heapDumpFile");
      return this;
    }

    public Builder excludedRefs(ExcludedRefs excludedRefs) {
      this.excludedRefs = checkNotNull(excludedRefs, "excludedRefs");
      return this;
    }

    public Builder gcDurationMs(long gcDurationMs) {
      this.gcDurationMs = gcDurationMs;
      return this;
    }

    public Builder heapDumpDurationMs(long heapDumpDurationMs) {
      this.heapDumpDurationMs = heapDumpDurationMs;
      return this;
    }

    public Builder computeRetainedHeapSize(boolean computeRetainedHeapSize) {
      this.computeRetainedHeapSize = computeRetainedHeapSize;
      return this;
    }

    public Builder reachabilityInspectorClasses(
        List<Class<? extends Reachability.Inspector>> reachabilityInspectorClasses) {
      checkNotNull(reachabilityInspectorClasses, "reachabilityInspectorClasses");
      this.reachabilityInspectorClasses =
          unmodifiableList(new ArrayList<>(reachabilityInspectorClasses));
      return this;
    }

    public HeapDump build() {
      checkNotNull(excludedRefs, "excludedRefs");
      checkNotNull(heapDumpFile, "heapDumpFile");
      checkNotNull(reachabilityInspectorClasses, "reachabilityInspectorClasses");
      return new HeapDump(this);
    }
  }
}
