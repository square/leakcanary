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
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static com.squareup.leakcanary.LeakTraceElement.Holder.THREAD;
import static com.squareup.leakcanary.LeakTraceElement.Type.STATIC_FIELD;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class) //
public class AsyncTaskLeakTest {

  static final String ASYNC_TASK_THREAD = "AsyncTask #1";
  static final String ASYNC_TASK_CLASS = "android.os.AsyncTask";
  static final String EXECUTOR_FIELD_1 = "SERIAL_EXECUTOR";
  static final String EXECUTOR_FIELD_2 = "sDefaultExecutor";

  @Parameterized.Parameters public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {
        { fileFromName("leak_asynctask.hprof"), "dc983a12-d029-4003-8890-7dd644c664c5" },
        { fileFromName("leak_asynctask_mpreview2.hprof"), "1114018e-e154-435f-9a3d-da63ae9b47fa" },
        { fileFromName("leak_asynctask_m_postpreview2.hprof"), "25ae1778-7c1d-4ec7-ac50-5cce55424069" }
    });
  }

  private static File fileFromName(String filename) {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    URL url = classLoader.getResource(filename);
    return new File(url.getPath());
  }

  final File heapDumpFile;
  final String referenceKey;

  ExcludedRefs.Builder excludedRefs;

  public AsyncTaskLeakTest(File heapDumpFile, String referenceKey) {
    this.heapDumpFile = heapDumpFile;
    this.referenceKey = referenceKey;
  }

  @Before public void setUp() {
    excludedRefs = new ExcludedRefs.Builder().clazz(WeakReference.class.getName(), true)
        .clazz("java.lang.ref.FinalizerReference", true);
  }

  @Test public void leakFound() {
    AnalysisResult result = analyze();
    assertTrue(result.leakFound);
    assertFalse(result.excludedLeak);
    LeakTraceElement gcRoot = result.leakTrace.elements.get(0);
    assertEquals(Thread.class.getName(), gcRoot.className);
    assertEquals(THREAD, gcRoot.holder);
    assertThat(gcRoot.extra, containsString(ASYNC_TASK_THREAD));
  }

  @Test public void excludeThread() {
    excludedRefs.thread(ASYNC_TASK_THREAD);
    AnalysisResult result = analyze();
    assertTrue(result.leakFound);
    assertFalse(result.excludedLeak);
    LeakTraceElement gcRoot = result.leakTrace.elements.get(0);
    assertEquals(ASYNC_TASK_CLASS, gcRoot.className);
    assertEquals(STATIC_FIELD, gcRoot.type);
    assertTrue(gcRoot.referenceName.equals(EXECUTOR_FIELD_1) || gcRoot.referenceName.equals(
        EXECUTOR_FIELD_2));
  }

  @Test public void excludeStatic() {
    excludedRefs.thread(ASYNC_TASK_THREAD);
    excludedRefs.staticField(ASYNC_TASK_CLASS, EXECUTOR_FIELD_1);
    excludedRefs.staticField(ASYNC_TASK_CLASS, EXECUTOR_FIELD_2);
    AnalysisResult result = analyze();
    assertTrue(result.leakFound);
    assertTrue(result.excludedLeak);
  }

  private AnalysisResult analyze() {
    HeapAnalyzer heapAnalyzer = new HeapAnalyzer(excludedRefs.build());
    AnalysisResult result = heapAnalyzer.checkForLeak(heapDumpFile, referenceKey);
    if (result.failure != null) {
      result.failure.printStackTrace();
    }
    if (result.leakTrace != null) {
      System.out.println(result.leakTrace);
    }
    return result;
  }


}
