package leakcanary

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import leakcanary.HeapAnalysisDecision.NoHeapAnalysis
import leakcanary.internal.InstrumentationHeapAnalyzer
import leakcanary.internal.RetryingHeapAnalyzer
import leakcanary.internal.friendly.checkNotMainThread
import leakcanary.internal.friendly.measureDurationMillis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.SharkLog

/**
 * Default [DetectLeaksAssert] implementation. Uses public helpers so you should be able to
 * create our own implementation if needed.
 *
 * Leak detection can be skipped by annotating tests with [SkipLeakDetection] which requires the
 * [TestDescriptionHolder] test rule be applied and evaluating when [assertNoLeaks]
 * is called.
 *
 * For improved leak detection, you should consider updating [LeakCanary.Config.leakingObjectFinder]
 * to `FilteringLeakingObjectFinder(AndroidObjectInspectors.appLeakingObjectFilters)` when running
 * in instrumentation tests. This changes leak detection from being incremental (based on
 * [AppWatcher] to also scanning for all objects of known types in the heap).
 */
class AndroidDetectLeaksAssert(
  private val detectLeaksInterceptor: DetectLeaksInterceptor = AndroidDetectLeaksInterceptor(),
  private val heapAnalysisReporter: HeapAnalysisReporter = NoLeakAssertionFailedError.throwOnApplicationLeaks()
) : DetectLeaksAssert {
  override fun assertNoLeaks(tag: String) {
    val assertionStartUptimeMillis = SystemClock.uptimeMillis()
    try {
      runLeakChecks(tag, assertionStartUptimeMillis)
    } finally {
      val totalDurationMillis = SystemClock.uptimeMillis() - assertionStartUptimeMillis
      totalVmDurationMillis += totalDurationMillis
      SharkLog.d { "Spent $totalDurationMillis ms detecting leaks on $tag, VM total so far: $totalVmDurationMillis ms" }
    }
  }

  private fun runLeakChecks(
    tag: String,
    assertionStartUptimeMillis: Long
  ) {
    if (TestDescriptionHolder.isEvaluating()) {
      val testDescription = TestDescriptionHolder.testDescription
      if (SkipLeakDetection.shouldSkipTest(testDescription, tag)) {
        return
      }
    }
    checkNotMainThread()

    val waitForRetainedDurationMillis = measureDurationMillis {
      val yesNo = detectLeaksInterceptor.waitUntilReadyForHeapAnalysis()
      if (yesNo is NoHeapAnalysis) {
        SharkLog.d { "Test can keep going: no heap dump performed (${yesNo.reason})" }
        return
      }
    }

    val heapDumpFileProvider = HeapDumpFileProvider.datetimeFormatted(
      directory = File(
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        "instrumentation_tests"
      ),
      prefix = "instrumentation_tests"
    )

    val heapDumpFile = heapDumpFileProvider.newHeapDumpFile()

    val config = LeakCanary.config

    KeyedWeakReference.heapDumpUptimeMillis = SystemClock.uptimeMillis()
    val heapDumpDurationMillis = measureDurationMillis {
      config.heapDumper.dumpHeap(heapDumpFile)
    }
    val heapDumpUptimeMillis = KeyedWeakReference.heapDumpUptimeMillis
    AppWatcher.objectWatcher.clearObjectsTrackedBefore(
      heapDumpUptimeMillis.milliseconds
    )

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
    val analysisResult = heapAnalyzer.analyze(heapDumpFile)
    val totalDurationMillis = SystemClock.uptimeMillis() - assertionStartUptimeMillis
    val heapAnalysisWithExtraDetails = analysisResult.let {
      when (it) {
        is HeapAnalysisSuccess -> it.copy(
          dumpDurationMillis = heapDumpDurationMillis,
          metadata = it.metadata + mapOf(
            ASSERTION_TAG to tag,
            WAIT_FOR_RETAINED to waitForRetainedDurationMillis.toString(),
            TOTAL_DURATION to totalDurationMillis.toString()
          ),
        )
        is HeapAnalysisFailure -> it.copy(dumpDurationMillis = heapDumpDurationMillis)
      }
    }
    heapAnalysisReporter.reportHeapAnalysis(heapAnalysisWithExtraDetails)
  }

  companion object {
    private const val ASSERTION_TAG = "assertionTag"
    private const val WAIT_FOR_RETAINED = "waitForRetainedDurationMillis"
    private const val TOTAL_DURATION = "totalDurationMillis"
    private var totalVmDurationMillis = 0L

    val HeapAnalysisSuccess.assertionTag: String?
      get() = metadata[ASSERTION_TAG]

    val HeapAnalysisSuccess.waitForRetainedDurationMillis: Int?
      get() = metadata[WAIT_FOR_RETAINED]?.toInt()

    val HeapAnalysisSuccess.totalDurationMillis: Int?
      get() = metadata[TOTAL_DURATION]?.toInt()
  }
}
