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

  /**
   * Key associated to the {@link KeyedWeakReference} used to detect the memory leak.
   * When analyzing a heap dump, search for all {@link KeyedWeakReference} instances, then open
   * the one that has its "key" field set to this value. Its "referent" field contains the
   * leaking object. Computing the shortest path to GC roots on that leaking object should enable
   * you to figure out the cause of the leak.
   */
  public final String referenceKey;

  /**
   * User defined name to help identify the leaking instance.
   */
  public final String referenceName;

  /** References that should be ignored when analyzing this heap dump. */
  public final ExcludedRefs excludedRefs;

  /** Time from the request to watch the reference until the GC was triggered. */
  public final long watchDurationMs;
  public final long gcDurationMs;
  public final long heapDumpDurationMs;
  public final boolean computeRetainedHeapSize;
  public final List<Class<? extends Reachability.Inspector>> reachabilityInspectorClasses;

  /**
   * Calls {@link #HeapDump(Builder)} with computeRetainedHeapSize set to true.
   *
   * @deprecated Use {@link #HeapDump(Builder)}  instead.
   */
  @Deprecated
  public HeapDump(File heapDumpFile, String referenceKey, String referenceName,
      ExcludedRefs excludedRefs, long watchDurationMs, long gcDurationMs, long heapDumpDurationMs) {
    this(new Builder().heapDumpFile(heapDumpFile)
        .referenceKey(referenceKey)
        .referenceName(referenceName)
        .excludedRefs(excludedRefs)
        .computeRetainedHeapSize(true)
        .watchDurationMs(watchDurationMs)
        .gcDurationMs(gcDurationMs)
        .heapDumpDurationMs(heapDumpDurationMs));
  }

  HeapDump(Builder builder) {
    this.heapDumpFile = builder.heapDumpFile;
    this.referenceKey = builder.referenceKey;
    this.referenceName = builder.referenceName;
    this.excludedRefs = builder.excludedRefs;
    this.computeRetainedHeapSize = builder.computeRetainedHeapSize;
    this.watchDurationMs = builder.watchDurationMs;
    this.gcDurationMs = builder.gcDurationMs;
    this.heapDumpDurationMs = builder.heapDumpDurationMs;
    this.reachabilityInspectorClasses = builder.reachabilityInspectorClasses;
  }

  public Builder buildUpon() {
    return new Builder(this);
  }

  public static final class Builder {
    File heapDumpFile;
    String referenceKey;
    String referenceName;
    ExcludedRefs excludedRefs;
    long watchDurationMs;
    long gcDurationMs;
    long heapDumpDurationMs;
    boolean computeRetainedHeapSize;
    List<Class<? extends Reachability.Inspector>> reachabilityInspectorClasses;

    Builder() {
      this.heapDumpFile = null;
      this.referenceKey = null;
      referenceName = "";
      excludedRefs = null;
      watchDurationMs = 0;
      gcDurationMs = 0;
      heapDumpDurationMs = 0;
      computeRetainedHeapSize = false;
      reachabilityInspectorClasses = null;
    }

    Builder(HeapDump heapDump) {
      this.heapDumpFile = heapDump.heapDumpFile;
      this.referenceKey = heapDump.referenceKey;
      this.referenceName = heapDump.referenceName;
      this.excludedRefs = heapDump.excludedRefs;
      this.computeRetainedHeapSize = heapDump.computeRetainedHeapSize;
      this.watchDurationMs = heapDump.watchDurationMs;
      this.gcDurationMs = heapDump.gcDurationMs;
      this.heapDumpDurationMs = heapDump.heapDumpDurationMs;
      this.reachabilityInspectorClasses = heapDump.reachabilityInspectorClasses;
    }

    public Builder heapDumpFile(File heapDumpFile) {
      this.heapDumpFile = checkNotNull(heapDumpFile, "heapDumpFile");
      return this;
    }

    public Builder referenceKey(String referenceKey) {
      this.referenceKey = checkNotNull(referenceKey, "referenceKey");
      return this;
    }

    public Builder referenceName(String referenceName) {
      this.referenceName = checkNotNull(referenceName, "referenceName");
      return this;
    }

    public Builder excludedRefs(ExcludedRefs excludedRefs) {
      this.excludedRefs = checkNotNull(excludedRefs, "excludedRefs");
      return this;
    }

    public Builder watchDurationMs(long watchDurationMs) {
      this.watchDurationMs = watchDurationMs;
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
      checkNotNull(referenceKey, "referenceKey");
      checkNotNull(reachabilityInspectorClasses, "reachabilityInspectorClasses");
      return new HeapDump(this);
    }
  }
}
