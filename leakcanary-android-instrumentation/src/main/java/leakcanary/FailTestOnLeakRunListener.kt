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

import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import androidx.test.internal.runner.listener.InstrumentationResultPrinter
import androidx.test.internal.runner.listener.InstrumentationResultPrinter.REPORT_VALUE_RESULT_FAILURE
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import leakcanary.InstrumentationLeakDetector.Result.AnalysisPerformed
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import shark.HeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.SharkLog

/**
 *
 * A JUnit [RunListener] that uses [InstrumentationLeakDetector] to detect memory leaks in Android
 * instrumentation tests. It waits for the end of a test, and if the test succeeds then it will
 * look for retained objects, trigger a heap dump if needed and perform an analysis.
 *
 *  [FailTestOnLeakRunListener] can be subclassed to override [skipLeakDetectionReason] and
 *  [onAnalysisPerformed]
 *
 * @see InstrumentationLeakDetector
 */
open class FailTestOnLeakRunListener : RunListener() {
  private lateinit var bundle: Bundle
  private var skipLeakDetectionReason: String? = null

  override fun testStarted(description: Description) {
    skipLeakDetectionReason = skipLeakDetectionReason(description)
    if (skipLeakDetectionReason != null) {
      return
    }
    val testClass = description.className
    val testName = description.methodName

    bundle = Bundle()
    bundle.putString(
        Instrumentation.REPORT_KEY_IDENTIFIER, FailTestOnLeakRunListener::class.java.name
    )
    bundle.putString(InstrumentationResultPrinter.REPORT_KEY_NAME_CLASS, testClass)
    bundle.putString(InstrumentationResultPrinter.REPORT_KEY_NAME_TEST, testName)
  }

  /**
   * Can be overridden to skip leak detection based on the description provided when a test
   * is started. Return null to continue leak detection, or a string describing the reason for
   * skipping otherwise.
   */
  protected open fun skipLeakDetectionReason(description: Description): String? {
    return null
  }

  override fun testFailure(failure: Failure) {
    skipLeakDetectionReason = "failed"
  }

  override fun testIgnored(description: Description) {
    skipLeakDetectionReason = "was ignored"
  }

  override fun testAssumptionFailure(failure: Failure) {
    skipLeakDetectionReason = "had an assumption failure"
  }

  override fun testFinished(description: Description) {
    detectLeaks()
    AppWatcher.objectWatcher.clearWatchedObjects()
  }

  override fun testRunStarted(description: Description) {
    InstrumentationLeakDetector.updateConfig()
  }

  override fun testRunFinished(result: Result) {}

  private fun detectLeaks() {
    if (skipLeakDetectionReason != null) {
      SharkLog.d { "Skipping leak detection because the test $skipLeakDetectionReason" }
      skipLeakDetectionReason = null
      return
    }

    val leakDetector = InstrumentationLeakDetector()
    val result = leakDetector.detectLeaks()

    if (result is AnalysisPerformed) {
      onAnalysisPerformed(heapAnalysis = result.heapAnalysis)
    }
  }

  /**
   * Called when a heap analysis has been performed and a result is available.
   *
   * The default implementation call [failTest] if the [heapAnalysis] failed or if
   * [HeapAnalysisSuccess.applicationLeaks] is not empty.
   */
  protected open fun onAnalysisPerformed(heapAnalysis: HeapAnalysis) {
    when (heapAnalysis) {
      is HeapAnalysisFailure -> {
        failTest(Log.getStackTraceString(heapAnalysis.exception))
      }
      is HeapAnalysisSuccess -> {
        val applicationLeaks = heapAnalysis.applicationLeaks
        if (applicationLeaks.isNotEmpty()) {
          failTest("Test failed because application memory leaks were detected:\n$heapAnalysis")
        }
      }
    }
  }

  /**
   * Reports that the test has failed, with the provided [message].
   */
  protected fun failTest(message: String) {
    bundle.putString(InstrumentationResultPrinter.REPORT_KEY_STACK, message)
    getInstrumentation().sendStatus(REPORT_VALUE_RESULT_FAILURE, bundle)
  }
}
