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

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static com.squareup.leakcanary.LeakTraceElement.Holder.THREAD;
import static com.squareup.leakcanary.LeakTraceElement.Type.STATIC_FIELD;
import static com.squareup.leakcanary.TestUtil.HeapDumpFile.ASYNC_TASK;
import static com.squareup.leakcanary.TestUtil.HeapDumpFile.ASYNC_TASK_MPREVIEW2;
import static com.squareup.leakcanary.TestUtil.HeapDumpFile.ASYNC_TASK_M_POSTPREVIEW2;
import static com.squareup.leakcanary.TestUtil.analyze;
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
        { ASYNC_TASK }, //
        { ASYNC_TASK_MPREVIEW2 }, //
        { ASYNC_TASK_M_POSTPREVIEW2 } //
    });
  }

  private final TestUtil.HeapDumpFile heapDumpFile;
  ExcludedRefs.Builder excludedRefs;

  public AsyncTaskLeakTest(TestUtil.HeapDumpFile heapDumpFile) {
    this.heapDumpFile = heapDumpFile;
  }

  @Before public void setUp() {
    excludedRefs = new ExcludedRefs.Builder().clazz(WeakReference.class.getName(), true)
        .clazz("java.lang.ref.FinalizerReference", true);
  }

  @Test public void leakFound() {
    AnalysisResult result = analyze(heapDumpFile, excludedRefs);
    assertTrue(result.leakFound);
    assertFalse(result.excludedLeak);
    LeakTraceElement gcRoot = result.leakTrace.elements.get(0);
    assertEquals(Thread.class.getName(), gcRoot.className);
    assertEquals(THREAD, gcRoot.holder);
    assertThat(gcRoot.extra, containsString(ASYNC_TASK_THREAD));
  }

  @Test public void excludeThread() {
    excludedRefs.thread(ASYNC_TASK_THREAD);
    AnalysisResult result = analyze(heapDumpFile, excludedRefs);
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
    AnalysisResult result = analyze(heapDumpFile, excludedRefs);
    assertTrue(result.leakFound);
    assertTrue(result.excludedLeak);
  }
}
