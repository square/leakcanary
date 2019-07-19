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
 * [InstrumentationLeakDetector] can be used to detect memory leaks in instrumentation tests.
 *
 * To use it, you need to:
 *
 *  - Call [updateConfig] so that [LeakSentry] will watch objects and [LeakCanary] will not dump
 *  the heap on retained objects
 *  - Add an instrumentation test listener (e.g. [FailTestOnLeakRunListener]) that will invoke
 * [detectLeaks].
 *
 * ### Add an instrumentation test listener
 *
 * LeakCanary provides [FailTestOnLeakRunListener], but you can also implement
 * your own [RunListener] and call [detectLeaks] directly if you need a more custom
 * behavior (for instance running it only once per test suite).
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
 * Instead of using the [InstrumentationLeakDetector], one could simply enable LeakCanary in
 * instrumentation tests.
 *
 * This approach would have two disadvantages:
 *
 *  - Heap dumps freeze the VM, and the leak analysis is IO and CPU heavy. This can slow down
 * the test and introduce flakiness
 *  - The leak analysis is asynchronous by default. This means the tests could finish and the
 *  process die before the analysis is finished.
 *
 * The approach taken here is to collect all objects to watch as you run the test, but not
 * do any heap dump during the test. Then, at the end, if any of the watched objects is still in
 * memory we dump the heap and perform a blocking analysis. There is only one heap dump performed,
 * no matter the number of objects retained.
 */
class InstrumentationLeakDetector {

  /**
   * The result of calling [detectLeaks], which is either [NoAnalysis] or [AnalysisPerformed].
   */
  sealed class Result {
    object NoAnalysis : Result()
    class AnalysisPerformed(val heapAnalysis: HeapAnalysis) : Result()
  }

  /**
   * Looks for retained objects, triggers a heap dump if needed and performs an analysis.
   */
  fun detectLeaks(): Result {
    val leakDetectionTime = SystemClock.uptimeMillis()
    val watchDurationMillis = LeakSentry.config.watchDurationMillis
    val instrumentation = getInstrumentation()
    val context = instrumentation.targetContext
    val refWatcher = LeakSentry.objectWatcher

    if (!refWatcher.hasWatchedObjects) {
      return NoAnalysis
    }

    instrumentation.waitForIdleSync()
    if (!refWatcher.hasWatchedObjects) {
      return NoAnalysis
    }

    runGc()
    if (!refWatcher.hasWatchedObjects) {
      return NoAnalysis
    }

    // Waiting for any delayed UI post (e.g. scroll) to clear. This shouldn't be needed, but
    // Android simply has way too many delayed posts that aren't canceled when views are detached.
    SystemClock.sleep(2000)

    if (!refWatcher.hasWatchedObjects) {
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

    if (!refWatcher.hasRetainedObjects) {
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

    refWatcher.clearObjectsWatchedBefore(heapDumpUptimeMillis)

    val listener = AnalyzerProgressListener.NONE

    val heapAnalyzer = HeapAnalyzer(listener)
    val heapAnalysis = heapAnalyzer.checkForLeaks(
        heapDumpFile, config.referenceMatchers,
        config.computeRetainedHeapSize,
        config.objectInspectors,
        if (config.useExperimentalLeakFinders) config.objectInspectors else listOf(
            AndroidObjectInspectors.KEYED_WEAK_REFERENCE
        )
    )

    CanaryLog.d("Heap Analysis:\n%s", heapAnalysis)

    return AnalysisPerformed(heapAnalysis)
  }

  companion object {

    /**
     * Configures [LeakSentry] to watch objects and [LeakCanary] to not dump the heap on retained
     * objects so that instrumentation tests run smoothly, and we can look for leaks at the end of
     * a test. This is automatically called by [FailTestOnLeakRunListener] when the tests start
     * running.
     */
    fun updateConfig() {
      LeakSentry.config = LeakSentry.config.copy(enabled = true)
      LeakCanary.config = LeakCanary.config.copy(dumpHeap = false)
    }
  }
}
