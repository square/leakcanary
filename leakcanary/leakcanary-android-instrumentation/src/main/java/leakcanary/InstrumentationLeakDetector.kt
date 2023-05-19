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

import android.os.SystemClock
import leakcanary.InstrumentationLeakDetector.Result.AnalysisPerformed
import leakcanary.InstrumentationLeakDetector.Result.NoAnalysis
import leakcanary.HeapAnalysisDecision.NoHeapAnalysis
import leakcanary.internal.InstrumentationHeapAnalyzer
import leakcanary.internal.InstrumentationHeapDumpFileProvider
import leakcanary.internal.RetryingHeapAnalyzer
import leakcanary.internal.friendly.measureDurationMillis
import org.junit.runner.notification.RunListener
import shark.HeapAnalysis
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.SharkLog

/**
 * Deprecated: Use LeakAssertions instead
 *
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
 * ```shell
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
@Deprecated("Use LeakAssertions instead")
class InstrumentationLeakDetector {

  /**
   * The result of calling [detectLeaks], which is either [NoAnalysis] or [AnalysisPerformed].
   */
  sealed class Result {
    class NoAnalysis(val reason: String) : Result()
    class AnalysisPerformed(val heapAnalysis: HeapAnalysis) : Result()
  }

  /**
   * Looks for retained objects, triggers a heap dump if needed and performs an analysis.
   */
  @Suppress("ReturnCount")
  fun detectLeaks(): Result {
    val retainedObjectsChecker = AndroidDetectLeaksInterceptor()
    val yesNo = retainedObjectsChecker.waitUntilReadyForHeapAnalysis()
    if (yesNo is NoHeapAnalysis) {
      return NoAnalysis(yesNo.reason)
    }

    val heapDumpFile = InstrumentationHeapDumpFileProvider().newHeapDumpFile()

    val config = LeakCanary.config

    KeyedWeakReference.heapDumpUptimeMillis = SystemClock.uptimeMillis()
    val heapDumpDurationMillis = try {
      measureDurationMillis {
        config.heapDumper.dumpHeap(heapDumpFile)
      }
    } catch (exception: Exception) {
      SharkLog.d(exception) { "Could not dump heap" }
      return AnalysisPerformed(
        HeapAnalysisFailure(
          heapDumpFile = heapDumpFile,
          createdAtTimeMillis = System.currentTimeMillis(),
          dumpDurationMillis = 0,
          analysisDurationMillis = 0,
          exception = HeapAnalysisException(exception)
        )
      )
    } finally {
      val heapDumpUptimeMillis = KeyedWeakReference.heapDumpUptimeMillis
      AppWatcher.objectWatcher.clearObjectsWatchedBefore(heapDumpUptimeMillis)
    }

    val heapAnalyzer = RetryingHeapAnalyzer(
      InstrumentationHeapAnalyzer(
        leakingObjectFinder = config.leakingObjectFinder,
        referenceMatchers = config.referenceMatchers,
        computeRetainedHeapSize = config.computeRetainedHeapSize,
        metadataExtractor = config.metadataExtractor,
        objectInspectors = config.objectInspectors,
        proguardMapping = null
      )
    )

    val heapAnalysis = heapAnalyzer.analyze(heapDumpFile).let {
      when (it) {
        is HeapAnalysisSuccess -> it.copy(dumpDurationMillis = heapDumpDurationMillis)
        is HeapAnalysisFailure -> it.copy(dumpDurationMillis = heapDumpDurationMillis)
      }
    }

    return AnalysisPerformed(heapAnalysis)
  }

  companion object {

    @Deprecated(
      "This is a no-op as LeakCanary automatically detects tests",
      replaceWith = ReplaceWith("")
    )
    fun updateConfig() = Unit
  }
}
