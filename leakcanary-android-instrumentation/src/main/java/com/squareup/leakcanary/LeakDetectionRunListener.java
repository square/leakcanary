/*
 * Copyright (C) 2018 Square, Inc.
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

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import com.squareup.leakcanary.internal.LeakCanaryInternals;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

import static android.support.test.InstrumentationRegistry.getInstrumentation;
import static com.squareup.leakcanary.HeapDumper.RETRY_LATER;

/**
 * <p>A JUnit {@link RunListener} for detecting memory leaks in Android instrumentation tests. It
 * waits for the end of a test suite, and if all tests are green then it will look for leaking
 * references, trigger a heap dump if needed and perform an analysis.
 *
 * <p>To use it, you need to:
 * <ul>
 * <li>Install a custom RefWatcher that will not trigger heapdumps while the tests run.</li>
 * <li>Add this {@link RunListener} as an instrumentation test listener.</li>
 * </ul>
 *
 * <h3>Installing the instrumentation RefWatcher</h3>
 *
 * <p>For this test listener to work correctly, the {@link RefWatcher} must keep track of
 * references but not trigger any heap dump until this test listener runs, otherwise an analysis in
 * progress might prevent this listener from performing its own analysis.
 *
 * <p>Create and install the {@link RefWatcher} instance using {@link
 * #instrumentationRefWatcher(Application)} instead of {@link LeakCanary#install(Application)} or
 * {@link LeakCanary#refWatcher(Context)}.
 * <pre><code>
 * public class TestExampleApplication extends DebugExampleApplication {
 *   {@literal @}Override protected RefWatcher installLeakCanary() {
 *     RefWatcher refWatcher = LeakDetectionRunListener.instrumentationRefWatcher(this)
 *     .buildAndInstall();
 *     return refWatcher;
 *   }
 * }
 * </code></pre>
 *
 * <h3>Add LeakDetectionRunListener as an intrumentation test listener</h3>
 *
 * <p>Add a <em>listener</em> execution argument to your command line for running the UI tests:
 * <code>-e listener com.squareup.leakcanary.LeakDetectionRunListener</code>. The full command line
 * should look something like this:
 * <pre><code>adb shell am instrument \\
 * -w com.android.foo/android.support.test.runner.AndroidJUnitRunner \\
 * -e listener com.squareup.leakcanary.LeakDetectionRunListener
 * </code></pre>
 *
 * <h3>Custom behavior</h3>
 *
 * <p>By default the listener will throw an exception that will fail the entire test suite if there
 * is any detected memory leak that is not part of the exclusion list.
 *
 * <p>You can customize this default behavior by subclassing {@link LeakDetectionRunListener} and
 * overriding {@link #testRunFinished(Result, List)}.
 *
 * <h3>Rationale</h3>
 * Instead of using the {@link LeakDetectionRunListener}, one could simply enable LeakCanary in
 * instrumentation tests.
 *
 * <p>This approach would have two disadvantages:
 * <ul>
 * <li>Heap dumps freeze the VM, and the leak analysis is IO and CPU heavy. This can slow down
 * the test and introduce flakiness</li>
 * <li>The leak analysis is asynchronous by default, and happens in a separate process. This means
 * the tests could finish and the process die before the analysis is finished.</li>
 * </ul>
 *
 * <p>The approach taken here is to collect all references to watch as you run the tests, but not
 * do any heap dump during the tests. Then, at the end, if any of the watched objects is still in
 * memory we dump the heap and perform a blocking analysis. There is only one heap dump performed,
 * no matter the number of objects leaking, and then we iterate on the leaking references in the
 * heap dump and provide a list of {@link LeakResult}.
 */
public class LeakDetectionRunListener extends RunListener {

  /**
   * @return a {@link} AndroidRefWatcherBuilder that will create a {@link RefWatcher} suitable for
   * instrumentation tests. This {@link RefWatcher} will never trigger a heap dump.
   */
  public static AndroidRefWatcherBuilder instrumentationRefWatcher(Application application) {
    return LeakCanary.refWatcher(application) //
        .watchExecutor(WatchExecutor.NONE);
  }

  @SuppressWarnings("ReferenceEquality") // Explicitly checking for named null.
  @Override public final void testRunFinished(Result result) throws Exception {
    if (result.getFailureCount() > 0) {
      CanaryLog.d("%d test failures, skipping leak detection.", result.getFailureCount());
      testRunFinished(result, Collections.<LeakResult>emptyList());
    }
    Instrumentation instrumentation = getInstrumentation();
    Context context = instrumentation.getTargetContext();
    RefWatcher refWatcher = LeakCanaryInternals.installedRefWatcher;
    LeakDirectoryProvider leakDirectoryProvider =
        LeakCanaryInternals.getLeakDirectoryProvider(context);
    if (refWatcher == null || leakDirectoryProvider == null) {
      throw new IllegalStateException("AndroidRefWatcherBuilder.buildAndInstall() was not called");
    }
    HeapDumper heapDumper = new AndroidHeapDumper(context, leakDirectoryProvider);
    GcTrigger gcTrigger = GcTrigger.DEFAULT;

    instrumentation.waitForIdleSync();
    // Waiting for any delayed UI post (e.g. scroll) to clear. This shouldn't be needed, but
    // Android simply has way too many delayed posts that aren't canceled when views are detached.
    SystemClock.sleep(2000);

    if (refWatcher.isEmpty()) {
      CanaryLog.d("No tracked references");
      testRunFinished(result, Collections.<LeakResult>emptyList());
      return;
    }

    gcTrigger.runGc();
    if (refWatcher.isEmpty()) {
      CanaryLog.d("No tracked references after GC");
      testRunFinished(result, Collections.<LeakResult>emptyList());
      return;
    }

    File heapDumpFile = heapDumper.dumpHeap();
    if (heapDumpFile == RETRY_LATER) {
      CanaryLog.d("Could not dump heap");
      testRunFinished(result, Collections.<LeakResult>emptyList());
      return;
    }

    ExcludedRefs excludedRefs = refWatcher.getExcludedRefs();
    HeapAnalyzer heapAnalyzer = new HeapAnalyzer(excludedRefs);

    List<TrackedReference> trackedReferencesInHeapDump =
        heapAnalyzer.findTrackedReferences(heapDumpFile);

    List<LeakResult> leakResults = new ArrayList<>();
    for (TrackedReference trackedReference : trackedReferencesInHeapDump) {
      HeapDump heapDump =
          new HeapDump(heapDumpFile, trackedReference.key, trackedReference.name, excludedRefs, 0,
              0, 0);
      AnalysisResult analysisResult = heapAnalyzer.checkForLeak(heapDumpFile, trackedReference.key);
      leakResults.add(new LeakResult(heapDump, analysisResult));
    }

    try {
      testRunFinished(result, leakResults);
    } finally {
      //noinspection ResultOfMethodCallIgnored
      heapDumpFile.delete();
    }
  }

  /**
   * Called when all tests have finished, on the instrumentation thread.
   *
   * Override this method to implement custom behavior.
   *
   * @param testsResult the summary of the test run, including all the tests that failed.
   * @param leakResults the result of the leak analysis. Empty if any test has failed.
   */
  protected void testRunFinished(Result testsResult, List<LeakResult> leakResults) {
    Instrumentation instrumentation = getInstrumentation();
    Context context = instrumentation.getTargetContext();
    boolean leakFound = false;
    for (LeakResult leakResult : leakResults) {
      CanaryLog.d(
          LeakCanary.leakInfo(context, leakResult.heapDump, leakResult.analysisResult, true));
      if (leakResult.analysisResult.leakFound && !leakResult.analysisResult.excludedLeak) {
        leakFound = true;
      }
    }
    if (leakFound) {
      throw new AssertionError("Test suite failed due to leaks, check Logcat for details.");
    }
  }

  public static final class LeakResult {
    public final HeapDump heapDump;
    public final AnalysisResult analysisResult;

    LeakResult(HeapDump heapDump, AnalysisResult analysisResult) {
      this.heapDump = heapDump;
      this.analysisResult = analysisResult;
    }
  }
}
