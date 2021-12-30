package leakcanary

import leakcanary.internal.RetainedObjectsInstrumentationChecker.YesNo.No
import leakcanary.internal.InstrumentationHeapAnalyzer
import leakcanary.internal.InstrumentationHeapDumpFileProvider
import leakcanary.internal.RetainedObjectsInstrumentationChecker
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
  private val heapAnalysisReporter: HeapAnalysisReporter = NoLeakAssertionFailedError.throwOnApplicationLeaks()
) : DetectLeaksAssert {
  override fun assertNoLeaks(tag: String) {
    if (TestDescriptionHolder.isEvaluating()) {
      val testDescription = TestDescriptionHolder.testDescription
      if (SkipLeakDetection.shouldSkipTest(testDescription, tag)) {
        return
      }
    }
    checkNotMainThread()
    val retainedObjectsChecker = RetainedObjectsInstrumentationChecker()
    val yesNo = retainedObjectsChecker.shouldDumpHeapWaitingForRetainedObjects()
    if (yesNo is No) {
      SharkLog.d { "Test can keep going: no heap dump performed (${yesNo.reason})" }
      return
    }
    val heapDumpFile = InstrumentationHeapDumpFileProvider().newHeapDumpFile()

    val config = LeakCanary.config

    val heapDumpDurationMillis = measureDurationMillis {
      config.heapDumper.dumpHeap(heapDumpFile)
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
    retainedObjectsChecker.clearObjectsWatchedBeforeHeapDump()
    heapAnalysisReporter.reportHeapAnalysis(heapAnalysis)
  }
}
