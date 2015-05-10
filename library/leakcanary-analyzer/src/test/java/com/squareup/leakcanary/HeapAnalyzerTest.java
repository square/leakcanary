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
import org.junit.Test;

import static com.squareup.leakcanary.LeakTraceElement.Holder.THREAD;
import static com.squareup.leakcanary.LeakTraceElement.Type.STATIC_FIELD;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class HeapAnalyzerTest {

  static final ExcludedRefs NONE = new ExcludedRefs.Builder().build();

  static final String ASYNC_TASK_THREAD = "AsyncTask #1";
  static final String ASYNC_TASK_CLASS = "android.os.AsyncTask";
  static final String EXECUTOR_FIELD = "SERIAL_EXECUTOR";

  @Test public void leakFound() {
    AnalysisResult result = analyze(new HeapAnalyzer(NONE));
    LeakTraceElement gcRoot = result.leakTrace.elements.get(0);
    assertTrue(result.leakFound);
    assertFalse(result.excludedLeak);
    assertEquals(Thread.class.getName(), gcRoot.className);
    assertEquals(THREAD, gcRoot.holder);
    assertThat(gcRoot.extra, containsString(ASYNC_TASK_THREAD));
  }

  @Test public void excludeThread() {
    ExcludedRefs.Builder excludedRefs = new ExcludedRefs.Builder();
    excludedRefs.thread(ASYNC_TASK_THREAD);
    AnalysisResult result = analyze(new HeapAnalyzer(excludedRefs.build()));
    assertTrue(result.leakFound);
    assertFalse(result.excludedLeak);
    LeakTraceElement gcRoot = result.leakTrace.elements.get(0);
    assertEquals(ASYNC_TASK_CLASS, gcRoot.className);
    assertEquals(STATIC_FIELD, gcRoot.type);
    assertEquals(EXECUTOR_FIELD, gcRoot.referenceName);
  }

  @Test public void excludeStatic() {
    ExcludedRefs.Builder excludedRefs = new ExcludedRefs.Builder();
    excludedRefs.thread(ASYNC_TASK_THREAD);
    excludedRefs.staticField(ASYNC_TASK_CLASS, EXECUTOR_FIELD);
    AnalysisResult result = analyze(new HeapAnalyzer(excludedRefs.build()));
    assertTrue(result.leakFound);
    assertTrue(result.excludedLeak);
  }

  @Test public void excludeStaticForBase() {
    ExcludedRefs.Builder excludedRefs = new ExcludedRefs.Builder();
    excludedRefs.thread(ASYNC_TASK_THREAD);
    excludedRefs.staticField(ASYNC_TASK_CLASS, EXECUTOR_FIELD);
    AnalysisResult result = analyze(new HeapAnalyzer(excludedRefs.build(), excludedRefs.build()));
    assertFalse(result.leakFound);
  }

  private AnalysisResult analyze(HeapAnalyzer heapAnalyzer) {
    File heapDumpFile = new File(Thread.currentThread()
        .getContextClassLoader()
        .getResource("leak_asynctask.hprof")
        .getPath());
    return heapAnalyzer.checkForLeak(heapDumpFile, "dc983a12-d029-4003-8890-7dd644c664c5");
  }
}
