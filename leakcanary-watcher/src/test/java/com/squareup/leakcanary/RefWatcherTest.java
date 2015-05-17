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
import java.util.concurrent.Executor;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RefWatcherTest {

  static final ExcludedRefs NO_REF = new ExcludedRefs.Builder().build();

  static class TestDumper implements HeapDumper {
    boolean called;

    @Override public File dumpHeap() {
      called = true;
      return new File("");
    }
  }

  static class TestListener implements HeapDump.Listener {
    @Override public void analyze(HeapDump heapDump) {
    }
  }

  @SuppressWarnings("FieldCanBeLocal") Object ref;

  static class TestExecutor implements Executor {
    private Runnable command;

    @Override public void execute(Runnable command) {
      this.command = command;
    }
  }

  /**
   * In theory, this test doesn't have a 100% chance of success. In practice, {@link
   * GcTrigger#DEFAULT} is good enough.
   */
  @Test public void unreachableObject_noDump() {
    TestDumper dumper = new TestDumper();
    TestExecutor executor = new TestExecutor();
    RefWatcher refWatcher = defaultWatcher(dumper, executor);
    refWatcher.watch(new Object());
    executor.command.run();
    assertFalse(dumper.called);
  }

  @Test public void retainedObject_triggersDump() {
    TestDumper dumper = new TestDumper();
    TestExecutor executor = new TestExecutor();
    RefWatcher refWatcher = defaultWatcher(dumper, executor);
    ref = new Object();
    refWatcher.watch(ref);
    executor.command.run();
    assertTrue(dumper.called);
  }

  private RefWatcher defaultWatcher(TestDumper dumper, TestExecutor executor) {
    return new RefWatcher(executor, DebuggerControl.NONE, GcTrigger.DEFAULT, dumper,
        new TestListener(), NO_REF);
  }
}
