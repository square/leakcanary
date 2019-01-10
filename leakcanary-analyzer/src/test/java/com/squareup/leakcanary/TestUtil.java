/*
 * Copyright (C) 2016 Square, Inc.
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
import java.lang.ref.PhantomReference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Collections;
import java.util.List;

final class TestUtil {

  public static final ExcludedRefs NO_EXCLUDED_REFS = ExcludedRefs.builder().build();

  enum HeapDumpFile {
    ASYNC_TASK_PRE_M("leak_asynctask_pre_m.hprof", "dc983a12-d029-4003-8890-7dd644c664c5"), //
    ASYNC_TASK_M("leak_asynctask_m.hprof", "25ae1778-7c1d-4ec7-ac50-5cce55424069"), //
    ASYNC_TASK_O("leak_asynctask_o.hprof", "0e8d40d7-8302-4493-93d5-962a4c176089"),
    GC_ROOT_IN_NON_PRIMARY_HEAP("gc_root_in_non_primary_heap.hprof",
        "10a5bc66-e9cb-430c-930a-fc1dc4fc0f85");

    public final String filename;
    public final String referenceKey;

    HeapDumpFile(String filename, String referenceKey) {
      this.filename = filename;
      this.referenceKey = referenceKey;
    }

  }

  static File fileFromName(String filename) {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    URL url = classLoader.getResource(filename);
    return new File(url.getPath());
  }

  static List<TrackedReference> findTrackedReferences(HeapDumpFile heapDumpFile) {
    File file = fileFromName(heapDumpFile.filename);
    HeapAnalyzer heapAnalyzer = new HeapAnalyzer(NO_EXCLUDED_REFS, AnalyzerProgressListener.NONE,
        Collections.<Class<? extends Reachability.Inspector>>emptyList());
    return heapAnalyzer.findTrackedReferences(file);
  }

  static AnalysisResult analyze(HeapDumpFile heapDumpFile) {
    ExcludedRefs.BuilderWithParams excludedRefs = new ExcludedRefs.BuilderWithParams()
        .clazz(WeakReference.class.getName()).alwaysExclude()
        .clazz(SoftReference.class.getName()).alwaysExclude()
        .clazz(PhantomReference.class.getName()).alwaysExclude()
        .clazz("java.lang.ref.Finalizer").alwaysExclude()
        .clazz("java.lang.ref.FinalizerReference").alwaysExclude()
        .thread("FinalizerWatchdogDaemon").alwaysExclude()
        .thread("main").alwaysExclude();
    return analyze(heapDumpFile, excludedRefs);
  }

  static AnalysisResult analyze(HeapDumpFile heapDumpFile,
      ExcludedRefs.BuilderWithParams excludedRefs) {
    File file = fileFromName(heapDumpFile.filename);
    String referenceKey = heapDumpFile.referenceKey;
    HeapAnalyzer heapAnalyzer =
        new HeapAnalyzer(excludedRefs.build(), AnalyzerProgressListener.NONE,
            Collections.<Class<? extends Reachability.Inspector>>emptyList());
    AnalysisResult result =
        heapAnalyzer.checkForLeak(file, referenceKey, true);
    if (result.failure != null) {
      result.failure.printStackTrace();
    }
    if (result.leakTrace != null) {
      System.out.println(result.leakTrace);
    }
    return result;
  }

  private TestUtil() {
    throw new AssertionError();
  }
}
