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

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import leakcanary.InstrumentationLeakDetector.Result.AnalysisPerformed
import leakcanary.internal.TestResultPublisher
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import shark.HeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.SharkLog
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

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
  private var _currentTestDescription: Description? = null
  private val currentTestDescription: Description
    get() = _currentTestDescription!!

  private var skipLeakDetectionReason: String? = null

  private lateinit var testResultPublisher: TestResultPublisher

  @Volatile
  private var allActivitiesDestroyedLatch: CountDownLatch? = null

  override fun testRunStarted(description: Description) {
    testResultPublisher = TestResultPublisher.install()
    trackActivities()
  }

  private fun trackActivities() {
    val instrumentation = getInstrumentation()!!
    val application = instrumentation.targetContext.applicationContext as Application
    application.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks by noOpDelegate() {

      var activitiesWaitingForDestroyed = 0

      override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?
      ) {
        if (activitiesWaitingForDestroyed == 0) {
          allActivitiesDestroyedLatch = CountDownLatch(1)
        }
        activitiesWaitingForDestroyed++
      }

      override fun onActivityDestroyed(activity: Activity) {
        activitiesWaitingForDestroyed--
        if (activitiesWaitingForDestroyed == 0) {
          allActivitiesDestroyedLatch!!.countDown()
        }
      }
    })
  }

  override fun testRunFinished(result: Result) {
  }

  override fun testStarted(description: Description) {
    _currentTestDescription = description
    skipLeakDetectionReason = skipLeakDetectionReason(description)
    if (skipLeakDetectionReason != null) {
      return
    }
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
    if (skipLeakDetectionReason == null) {
      detectLeaks()
    } else {
      SharkLog.d { "Skipping leak detection because the test $skipLeakDetectionReason" }
      skipLeakDetectionReason = null
    }
    AppWatcher.objectWatcher.clearWatchedObjects()
    _currentTestDescription = null
    testResultPublisher.publishTestFinished()
  }

  private fun detectLeaks() {
    val allActivitiesDestroyed = allActivitiesDestroyedLatch?.await(2, SECONDS) ?: true
    if (!allActivitiesDestroyed) {
      SharkLog.d { "Leak detection proceeding with some activities still not in destroyed state" }
    }

    val leakDetector = InstrumentationLeakDetector()
    val result = leakDetector.detectLeaks()

    if (result is AnalysisPerformed) {
      onAnalysisPerformed(heapAnalysis = result.heapAnalysis)
    } else {
      SharkLog.d { "No heap analysis performed" }
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
        failTest(
            "$currentTestDescription failed because heap analysis failed:\n" + Log.getStackTraceString(
                heapAnalysis.exception
            )
        )
      }
      is HeapAnalysisSuccess -> {
        val applicationLeaks = heapAnalysis.applicationLeaks
        if (applicationLeaks.isNotEmpty()) {
          failTest("$currentTestDescription failed because application memory leaks were detected:\n$heapAnalysis")
        } else {
          SharkLog.d { "Heap analysis found 0 application leaks:\n$heapAnalysis" }
        }
      }
    }
  }

  /**
   * Reports that the test has failed, with the provided [trace].
   */
  protected fun failTest(trace: String) {
    SharkLog.d { trace }
    testResultPublisher.publishTestFailure(currentTestDescription, trace)
  }

  private inline fun <reified T : Any> noOpDelegate(): T {
    val javaClass = T::class.java
    val noOpHandler = InvocationHandler { _, _, _ ->
      // no op
    }
    return Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(javaClass), noOpHandler
    ) as T
  }
}
