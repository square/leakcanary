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
import android.os.Debug;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.runner.notification.RunListener;

import static android.support.test.InstrumentationRegistry.getInstrumentation;

/**
 * <p>{@link InstrumentationLeakDetector} can be used to detect memory leaks in instrumentation
 * tests.
 *
 * <p>To use it, you need to:
 * <ul>
 * <li>Install a custom RefWatcher that will not trigger heapdumps while the tests run.</li>
 * <li>Add an instrumentation test listener (a {@link RunListener}) that will invoke
 * {@link #detectLeaks()}</li>
 * </ul>
 *
 * <h3>Installing the instrumentation RefWatcher</h3>
 *
 * <p>For {@link #detectLeaks()} to work correctly, the {@link RefWatcher} must keep track of
 * references but not trigger any heap dump until this {@link #detectLeaks()} runs, otherwise an
 * analysis in progress might prevent this listener from performing its own analysis.
 *
 * <p>Create and install the {@link RefWatcher} instance using
 * {@link #instrumentationRefWatcher(Application)} instead of
 * {@link LeakCanary#install(Application)} or {@link LeakCanary#refWatcher(Context)}.
 * <pre><code>
 * public class InstrumentationExampleApplication extends ExampleApplication {
 *   {@literal @}Override protected void setupLeakCanary() {
 *     InstrumentationLeakDetector.instrumentationRefWatcher(this)
 *         .buildAndInstall();
 *   }
 * }
 * </code></pre>
 *
 * <h3>Add an intrumentation test listener</h3>
 *
 * <p>LeakCanary provides {@link FailTestOnLeakRunListener}, but you should feel free to implement
 * your own {@link RunListener} and call {@link #detectLeaks()} directly if you need a more custom
 * behavior (for instance running it only once per test suite, or reporting to a backend).</p>
 *
 * <p>All you need to do is add the following to the defaultConfig of your build.gradle:
 *
 * <pre><code>testInstrumentationRunnerArgument "listener", "com.squareup.leakcanary.FailTestOnLeakRunListener"</code></pre>
 *
 * <p>Then you can run your instrumentation tests via Gradle as usually, and they will fail when
 * a memory leak is detected:
 *
 * <pre><code>./gradlew leakcanary-sample:connectedCheck</code></pre>
 *
 * <p>If instead you want to run UI tests via adb, add a <em>listener</em> execution argument to
 * your command line for running the UI tests:
 * <code>-e listener com.squareup.leakcanary.FailTestOnLeakRunListener</code>. The full command line
 * should look something like this:
 * <pre><code>adb shell am instrument \\
 * -w com.android.foo/android.support.test.runner.AndroidJUnitRunner \\
 * -e listener com.squareup.leakcanary.FailTestOnLeakRunListener
 * </code></pre>
 *
 * <h3>Rationale</h3>
 * Instead of using the {@link FailTestOnLeakRunListener}, one could simply enable LeakCanary in
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
 * <p>The approach taken here is to collect all references to watch as you run the test, but not
 * do any heap dump during the test. Then, at the end, if any of the watched objects is still in
 * memory we dump the heap and perform a blocking analysis. There is only one heap dump performed,
 * no matter the number of objects leaking, and then we iterate on the leaking references in the
 * heap dump and provide all result in a {@link InstrumentationLeakResults}.
 */
public final class InstrumentationLeakDetector {

  /**
   * Returns a new {@link} AndroidRefWatcherBuilder that will create a {@link RefWatcher} suitable
   * for instrumentation tests. This {@link RefWatcher} will never trigger a heap dump. This should
   * be installed from the test application class, and should be used in combination with a
   * {@link RunListener} that calls {@link #detectLeaks()}, for instance
   * {@link FailTestOnLeakRunListener}.
   */
  public static @NonNull AndroidRefWatcherBuilder instrumentationRefWatcher(
      @NonNull Application application) {
    return LeakCanary.refWatcher(application)
        .watchExecutor(new WatchExecutor() {
          // Storing weak refs to ensure they make it to the queue.
          final List<Retryable> trackedReferences = new CopyOnWriteArrayList<>();

          @Override public void execute(Retryable retryable) {
            trackedReferences.add(retryable);
          }
        });
  }

  public @NonNull InstrumentationLeakResults detectLeaks() {
    Instrumentation instrumentation = getInstrumentation();
    Context context = instrumentation.getTargetContext();
    RefWatcher refWatcher = LeakCanary.installedRefWatcher();
    Set<String> retainedKeys = refWatcher.getRetainedKeys();

    if (refWatcher.isEmpty()) {
      return InstrumentationLeakResults.NONE;
    }

    instrumentation.waitForIdleSync();
    if (refWatcher.isEmpty()) {
      return InstrumentationLeakResults.NONE;
    }

    GcTrigger.DEFAULT.runGc();
    if (refWatcher.isEmpty()) {
      return InstrumentationLeakResults.NONE;
    }

    // Waiting for any delayed UI post (e.g. scroll) to clear. This shouldn't be needed, but
    // Android simply has way too many delayed posts that aren't canceled when views are detached.
    SystemClock.sleep(2000);

    if (refWatcher.isEmpty()) {
      return InstrumentationLeakResults.NONE;
    }

    // Aaand we wait some more.
    // 4 seconds (2+2) is greater than the 3 seconds delay for
    // FINISH_TOKEN in android.widget.Filter
    SystemClock.sleep(2000);
    GcTrigger.DEFAULT.runGc();

    if (refWatcher.isEmpty()) {
      return InstrumentationLeakResults.NONE;
    }

    // We're always reusing the same file since we only execute this once at a time.
    File heapDumpFile = new File(context.getFilesDir(), "instrumentation_tests_heapdump.hprof");
    try {
      Debug.dumpHprofData(heapDumpFile.getAbsolutePath());
    } catch (Exception e) {
      CanaryLog.d(e, "Could not dump heap");
      return InstrumentationLeakResults.NONE;
    }

    HeapDump.Builder heapDumpBuilder = refWatcher.getHeapDumpBuilder();
    HeapAnalyzer heapAnalyzer =
        new HeapAnalyzer(heapDumpBuilder.excludedRefs, AnalyzerProgressListener.NONE,
            heapDumpBuilder.reachabilityInspectorClasses);

    List<TrackedReference> trackedReferences = heapAnalyzer.findTrackedReferences(heapDumpFile);

    List<InstrumentationLeakResults.Result> detectedLeaks = new ArrayList<>();
    List<InstrumentationLeakResults.Result> excludedLeaks = new ArrayList<>();
    List<InstrumentationLeakResults.Result> failures = new ArrayList<>();

    for (TrackedReference trackedReference : trackedReferences) {
      // Ignore any Weak Reference that this test does not care about.
      if (!retainedKeys.contains(trackedReference.key)) {
        continue;
      }

      HeapDump heapDump = HeapDump.builder()
          .heapDumpFile(heapDumpFile)
          .referenceKey(trackedReference.key)
          .referenceName(trackedReference.name)
          .excludedRefs(heapDumpBuilder.excludedRefs)
          .reachabilityInspectorClasses(heapDumpBuilder.reachabilityInspectorClasses)
          .build();

      AnalysisResult analysisResult =
          heapAnalyzer.checkForLeak(heapDumpFile, trackedReference.key, false);

      InstrumentationLeakResults.Result leakResult =
          new InstrumentationLeakResults.Result(heapDump, analysisResult);

      if (analysisResult.leakFound) {
        if (!analysisResult.excludedLeak) {
          detectedLeaks.add(leakResult);
        } else {
          excludedLeaks.add(leakResult);
        }
      } else if (analysisResult.failure != null) {
        failures.add(leakResult);
      }
    }

    CanaryLog.d("Found %d proper leaks, %d excluded leaks and %d leak analysis failures",
        detectedLeaks.size(),
        excludedLeaks.size(),
        failures.size());

    return new InstrumentationLeakResults(detectedLeaks, excludedLeaks, failures);
  }
}
