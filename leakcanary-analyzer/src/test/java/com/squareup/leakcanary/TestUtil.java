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
import java.net.URL;

final class TestUtil {

  enum HeapDumpFile {
    ASYNC_TASK("leak_asynctask.hprof", "dc983a12-d029-4003-8890-7dd644c664c5"),
    ASYNC_TASK_MPREVIEW2("leak_asynctask_mpreview2.hprof", "1114018e-e154-435f-9a3d-da63ae9b47fa"),
    ASYNC_TASK_M_POSTPREVIEW2("leak_asynctask_m_postpreview2.hprof",
        "25ae1778-7c1d-4ec7-ac50-5cce55424069"),

    SERVICE_BINDER("leak_service_binder.hprof", "b3abfae6-2c53-42e1-b8c1-96b0558dbeae"),
    SERVICE_BINDER_IGNORED("leak_service_binder_ignored.hprof",
        "6e524414-9581-4ce7-8690-e8ddf8b82454"),;

    private final String filename;
    private final String referenceKey;

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

  static AnalysisResult analyze(HeapDumpFile heapDumpFile, ExcludedRefs.BuilderWithParams excludedRefs) {
    File file = fileFromName(heapDumpFile.filename);
    String referenceKey = heapDumpFile.referenceKey;
    HeapAnalyzer heapAnalyzer = new HeapAnalyzer(excludedRefs.build());
    AnalysisResult result = heapAnalyzer.checkForLeak(file, referenceKey);
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
