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

import static com.squareup.leakcanary.Preconditions.checkNotNull;

public final class HeapDump implements Serializable {

  public interface Listener {
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

  public HeapDump(File heapDumpFile, String referenceKey, String referenceName,
      ExcludedRefs excludedRefs, long watchDurationMs, long gcDurationMs, long heapDumpDurationMs) {
    this.heapDumpFile = checkNotNull(heapDumpFile, "heapDumpFile");
    this.referenceKey = checkNotNull(referenceKey, "referenceKey");
    this.referenceName = checkNotNull(referenceName, "referenceName");
    this.excludedRefs = checkNotNull(excludedRefs, "excludedRefs");
    this.watchDurationMs = watchDurationMs;
    this.gcDurationMs = gcDurationMs;
    this.heapDumpDurationMs = heapDumpDurationMs;
  }

  /** Renames the heap dump file and creates a new {@link HeapDump} pointing to it. */
  public HeapDump renameFile(File newFile) {
    heapDumpFile.renameTo(newFile);
    return new HeapDump(newFile, referenceKey, referenceName, excludedRefs, watchDurationMs,
        gcDurationMs, heapDumpDurationMs);
  }
}
