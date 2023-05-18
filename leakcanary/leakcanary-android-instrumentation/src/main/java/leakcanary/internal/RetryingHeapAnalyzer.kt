package leakcanary.internal

import android.os.SystemClock
import android.util.Log
import java.io.File
import shark.HeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.SharkLog

/**
 * Wraps [InstrumentationHeapAnalyzer] and retries the analysis once if it fails.
 */
internal class RetryingHeapAnalyzer(
  private val heapAnalyzer: InstrumentationHeapAnalyzer
) {

  fun analyze(heapDumpFile: File): HeapAnalysis {
    // A copy that will be used in case of failure followed by success, to see if the file has changed.
    val heapDumpCopyFile = File(heapDumpFile.parent, "copy-${heapDumpFile.name}")
    heapDumpFile.copyTo(heapDumpCopyFile)
    // Giving an extra 2 seconds to flush the hprof to the file system. We've seen several cases
    // of corrupted hprof files and assume this could be a timing issue.
    SystemClock.sleep(2000)

    val heapAnalysis = heapAnalyzer.analyze(heapDumpFile)

    return if (heapAnalysis is HeapAnalysisFailure) {
      // Experience has shown that trying again often just works. Not sure why.
      SharkLog.d(heapAnalysis.exception) {
        "Heap Analysis failed, retrying in 10s in case the heap dump was not fully baked yet. " +
          "Copy of original heap dump available at ${heapDumpCopyFile.absolutePath}"
      }
      SystemClock.sleep(10000)
      heapAnalyzer.analyze(heapDumpFile).let {
        when (it) {
          is HeapAnalysisSuccess -> it.copy(
            metadata = it.metadata + mapOf(
              "previousFailureHeapDumpCopy" to heapDumpCopyFile.absolutePath,
              "previousFailureStacktrace" to Log.getStackTraceString(heapAnalysis.exception)
            )
          )
          is HeapAnalysisFailure -> it
        }
      }
    } else {
      // We don't need the copy after all.
      heapDumpCopyFile.delete()
      heapAnalysis
    }
  }
}
