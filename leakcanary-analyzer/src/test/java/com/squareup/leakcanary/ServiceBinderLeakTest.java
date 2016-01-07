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

import java.lang.ref.WeakReference;
import org.junit.Before;
import org.junit.Test;

import static com.squareup.leakcanary.LeakTraceElement.Holder.CLASS;
import static com.squareup.leakcanary.LeakTraceElement.Holder.OBJECT;
import static com.squareup.leakcanary.LeakTraceElement.Type.INSTANCE_FIELD;
import static com.squareup.leakcanary.LeakTraceElement.Type.STATIC_FIELD;
import static com.squareup.leakcanary.TestUtil.HeapDumpFile.SERVICE_BINDER;
import static com.squareup.leakcanary.TestUtil.HeapDumpFile.SERVICE_BINDER_IGNORED;
import static com.squareup.leakcanary.TestUtil.analyze;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * leak_service_binder_ignored.hprof contains a "normal" leak when binding to a service, where
 * leak_service_binder.hprof contains a leak where a binder is leaked by a static field.
 */
public class ServiceBinderLeakTest {

  ExcludedRefs.BuilderWithParams excludedRefs;

  @Before public void setUp() {
    excludedRefs = new ExcludedRefs.BuilderWithParams().clazz(WeakReference.class.getName())
        .alwaysExclude()
        .clazz("java.lang.ref.FinalizerReference")
        .alwaysExclude();
  }

  @Test public void realBinderLeak() {
    excludedRefs.rootClass("android.os.Binder").alwaysExclude();

    AnalysisResult result = analyze(SERVICE_BINDER, excludedRefs);

    assertTrue(result.leakFound);
    assertFalse(result.excludedLeak);
    LeakTraceElement gcRoot = result.leakTrace.elements.get(0);
    assertEquals(STATIC_FIELD, gcRoot.type);
    assertEquals("com.example.leakcanary.LeakyService", gcRoot.className);
    assertEquals(CLASS, gcRoot.holder);
  }

  @Test public void ignorableBinderLeak() {
    excludedRefs.rootClass("android.os.Binder");

    AnalysisResult result = analyze(SERVICE_BINDER_IGNORED, excludedRefs);

    assertTrue(result.leakFound);
    assertTrue(result.excludedLeak);
    LeakTraceElement gcRoot = result.leakTrace.elements.get(0);
    assertEquals(INSTANCE_FIELD, gcRoot.type);
    assertEquals("com.example.leakcanary.LeakyService$MyBinder", gcRoot.className);
    assertEquals(OBJECT, gcRoot.holder);
  }

  @Test public void alwaysIgnorableBinderLeak() {
    excludedRefs.rootClass("android.os.Binder").alwaysExclude();

    AnalysisResult result = analyze(SERVICE_BINDER_IGNORED, excludedRefs);

    assertFalse(result.leakFound);
  }
}
