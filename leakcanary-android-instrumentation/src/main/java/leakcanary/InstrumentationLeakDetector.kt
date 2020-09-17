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
import shark.HeapAnalysis
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.SharkLog
import java.io.File

/**
 * [InstrumentationLeakDetector] can be used to detect memory leaks in instrumentation tests.
 *
 * To use it, you need to add an instrumentation test listener (e.g. [FailTestOnLeakRunListener])
 * that will invoke [detectLeaks].
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
 *  process dies before the analysis is finished.
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
  @Suppress("ReturnCount")
  fun detectLeaks(): Result {
    val leakDetectionTime = SystemClock.uptimeMillis()
    val watchDurationMillis = AppWatcher.config.watchDurationMillis
    val instrumentation = getInstrumentation()
    val context = instrumentation.targetContext
    val refWatcher = AppWatcher.objectWatcher

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

    val heapDumpDurationMillis: Long

    try {
      Debug.dumpHprofData(heapDumpFile.absolutePath)
      heapDumpDurationMillis = SystemClock.uptimeMillis() - heapDumpUptimeMillis
    } catch (exception: Exception) {
      SharkLog.d(exception) { "Could not dump heap" }
      return AnalysisPerformed(
          HeapAnalysisFailure(
              heapDumpFile = heapDumpFile,
              createdAtTimeMillis = System.currentTimeMillis(),
              dumpDurationMillis = SystemClock.uptimeMillis() - heapDumpUptimeMillis,
              analysisDurationMillis = 0,
              exception = HeapAnalysisException(exception)
          )
      )
    }

    refWatcher.clearObjectsWatchedBefore(heapDumpUptimeMillis)

    val listener = shark.OnAnalysisProgressListener.NO_OP

    // Giving an extra 2 seconds to flush the hprof to the file system. We've seen several cases
    // of corrupted hprof files and assume this could be a timing issue.
    SystemClock.sleep(2000)

    val heapAnalyzer = HeapAnalyzer(listener)
    val fullHeapAnalysis = when (val heapAnalysis = heapAnalyzer.analyze(
        heapDumpFile = heapDumpFile,
        leakingObjectFinder = config.leakingObjectFinder,
        referenceMatchers = config.referenceMatchers,
        computeRetainedHeapSize = config.computeRetainedHeapSize,
        objectInspectors = config.objectInspectors
    )) {
      is HeapAnalysisSuccess -> heapAnalysis.copy(dumpDurationMillis = heapDumpDurationMillis)
      is HeapAnalysisFailure -> heapAnalysis.copy(dumpDurationMillis = heapDumpDurationMillis)
    }
    return AnalysisPerformed(fullHeapAnalysis)
  }

  companion object {

    @Deprecated(
        "This is a no-op as LeakCanary automatically detects tests",
        replaceWith = ReplaceWith("")
    )
    fun updateConfig() = Unit
  }
}
