package shark.benchmark

import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import shark.FilteringLeakingObjectFinder
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.ObjectInspectors
import shark.OnAnalysisProgressListener
import shark.SharkLog

/**
 * Runs the same analysis `shark-cli analyze` runs, with the JDK inspectors instead of the Android
 * ones, so that the cost of the traversal that follows indexing can be measured.
 *
 * Analysis scaffolding for issue #2777.
 */
fun main(args: Array<String>) {
  val file = File(args[0])
  SharkLog.logger = object : SharkLog.Logger {
    override fun d(message: String) = println("  $message")
    override fun d(throwable: Throwable, message: String) = println("  $message: $throwable")
  }

  val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
  heapPools.forEach { it.resetPeakUsage() }

  val start = System.nanoTime()
  val analysis = HeapAnalyzer(OnAnalysisProgressListener { step ->
    println("[${(System.nanoTime() - start) / 1_000_000} ms] ${step.name}")
  }).analyze(
    heapDumpFile = file,
    leakingObjectFinder = FilteringLeakingObjectFinder(ObjectInspectors.jdkLeakingObjectFilters),
    computeRetainedHeapSize = true,
    objectInspectors = ObjectInspectors.jdkDefaults
  )

  when (analysis) {
    is HeapAnalysisSuccess -> println(
      "SUCCESS in ${analysis.analysisDurationMillis} ms, " +
        "${analysis.applicationLeaks.size} application leaks, " +
        "${analysis.libraryLeaks.size} library leaks"
    )
    is HeapAnalysisFailure -> {
      println("FAILURE after ${analysis.analysisDurationMillis} ms")
      analysis.exception.printStackTrace()
    }
  }
  val peakMb = heapPools.sumOf { it.peakUsage.used } / 1024 / 1024
  println("peak heap used = $peakMb MB")
}
