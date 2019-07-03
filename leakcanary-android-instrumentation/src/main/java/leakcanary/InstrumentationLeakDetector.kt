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
package leakcanary

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import leakcanary.GcTrigger.Default.runGc
import leakcanary.InstrumentationLeakDetector.Result.AnalysisPerformed
import leakcanary.InstrumentationLeakDetector.Result.NoAnalysis
import org.junit.runner.notification.RunListener
import java.io.File

/**
 * TODO Update this doc to match LeakCanary 2.0
 *
 * [InstrumentationLeakDetector] can be used to detect memory leaks in instrumentation tests.
 *
 * To use it, you need to:
 *
 *  * Install a custom RefWatcher that will not trigger heapdumps while the tests run.
 *  * Add an instrumentation test listener (a [RunListener]) that will invoke
 * [detectLeaks]
 *
 * ### Installing the instrumentation RefWatcher
 *
 * For [detectLeaks] to work correctly, the [RefWatcher] must keep track of
 * references but not trigger any heap dump until this [detectLeaks] runs, otherwise an
 * analysis in progress might prevent this listener from performing its own analysis.
 *
 * Create and install the [RefWatcher] instance using
 * [instrumentationRefWatcher] instead of
 * [LeakCanary.install] or [LeakCanary.refWatcher].
 *
 * ```
 * public class InstrumentationExampleApplication extends ExampleApplication {
 *  @Override protected void setupLeakCanary() {
 *    InstrumentationLeakDetector.instrumentationRefWatcher(this)
 *      .buildAndInstall();
 *  }
 * }
 * ```
 *
 * ### Add an instrumentation test listener
 *
 * LeakCanary provides [FailTestOnLeakRunListener], but you should feel free to implement
 * your own [RunListener] and call [.detectLeaks] directly if you need a more custom
 * behavior (for instance running it only once per test suite, or reporting to a backend).
 *
 * All you need to do is add the following to the defaultConfig of your build.gradle:
 *
 * `testInstrumentationRunnerArgument "listener", "leakcanary.FailTestOnLeakRunListener"`
 *
 * Then you can run your instrumentation tests via Gradle as usually, and they will fail when
 * a memory leak is detected:
 *
 * `./gradlew leakcanary-sample:connectedCheck`
 *
 * If instead you want to run UI tests via adb, add a *listener* execution argument to
 * your command line for running the UI tests:
 * `-e listener leakcanary.FailTestOnLeakRunListener`. The full command line
 * should look something like this:
 * ```
 * adb shell am instrument \\
 * -w com.android.foo/android.support.test.runner.AndroidJUnitRunner \\
 * -e listener leakcanary.FailTestOnLeakRunListener
 * ```
 *
 * ### Rationale
 * Instead of using the [FailTestOnLeakRunListener], one could simply enable LeakCanary in
 * instrumentation tests.
 *
 * This approach would have two disadvantages:
 *
 *  * Heap dumps freeze the VM, and the leak analysis is IO and CPU heavy. This can slow down
 * the test and introduce flakiness
 *  * The leak analysis is asynchronous by default, and happens in a separate process. This means
 * the tests could finish and the process die before the analysis is finished.
 *
 * The approach taken here is to collect all references to watch as you run the test, but not
 * do any heap dump during the test. Then, at the end, if any of the watched objects is still in
 * memory we dump the heap and perform a blocking analysis. There is only one heap dump performed,
 * no matter the number of objects leaking.
 */
class InstrumentationLeakDetector {

  sealed class Result {
    object NoAnalysis : Result()
    class AnalysisPerformed(val heapAnalysis: HeapAnalysis) : Result()
  }

  fun detectLeaks(): Result {
    val leakDetectionTime = SystemClock.uptimeMillis()
    val watchDurationMillis = LeakSentry.config.watchDurationMillis
    val instrumentation = getInstrumentation()
    val context = instrumentation.targetContext
    val refWatcher = LeakSentry.refWatcher

    if (!refWatcher.hasWatchedInstances) {
      return NoAnalysis
    }

    instrumentation.waitForIdleSync()
    if (!refWatcher.hasWatchedInstances) {
      return NoAnalysis
    }

    runGc()
    if (!refWatcher.hasWatchedInstances) {
      return NoAnalysis
    }

    // Waiting for any delayed UI post (e.g. scroll) to clear. This shouldn't be needed, but
    // Android simply has way too many delayed posts that aren't canceled when views are detached.
    SystemClock.sleep(2000)

    if (!refWatcher.hasWatchedInstances) {
      return NoAnalysis
    }

    // Aaand we wait some more.
    // 4 seconds (2+2) is greater than the 3 seconds delay for
    // FINISH_TOKEN in android.widget.Filter
    SystemClock.sleep(2000)

    val endOfWatchDelay = watchDurationMillis - (SystemClock.uptimeMillis() - leakDetectionTime)
    if (endOfWatchDelay > 0) {
      SystemClock.sleep(endOfWatchDelay)
    }

    runGc()

    if (!refWatcher.hasRetainedInstances) {
      return NoAnalysis
    }

    // We're always reusing the same file since we only execute this once at a time.
    val heapDumpFile = File(context.filesDir, "instrumentation_tests_heapdump.hprof")

    val config = LeakCanary.config

    val heapDumpUptimeMillis = SystemClock.uptimeMillis()
    KeyedWeakReference.heapDumpUptimeMillis = heapDumpUptimeMillis

    try {
      Debug.dumpHprofData(heapDumpFile.absolutePath)
    } catch (exception: Exception) {
      CanaryLog.d(exception, "Could not dump heap")
      return AnalysisPerformed(
          HeapAnalysisFailure(
              heapDumpFile, analysisDurationMillis = 0,
              createdAtTimeMillis = System.currentTimeMillis(),
              exception = HeapAnalysisException(exception)
          )
      )
    }

    refWatcher.removeInstancesRetainedBeforeHeapDump(heapDumpUptimeMillis)

    val listener = AnalyzerProgressListener.NONE

    val heapAnalyzer = HeapAnalyzer(listener)
    val heapAnalysis = heapAnalyzer.checkForLeaks(
        heapDumpFile, AndroidKnownReference.mapToExclusions(config.knownReferences), config.computeRetainedHeapSize,
        config.leakTraceInspectors
    )

    CanaryLog.d("Heap Analysis:\n%s", heapAnalysis)

    return AnalysisPerformed(heapAnalysis)
  }

  companion object {

    /**
     * Configures LeakCanary to not dump the heap so that instrumentation tests run smoothly,
     * and we can look for leaks at the end of a test. This is automatically called by
     * [FailTestOnLeakRunListener] when the tests start running.
     */
    fun updateConfig() {
      LeakSentry.config = LeakSentry.config.copy(enabled = true)
    }
  }
}
