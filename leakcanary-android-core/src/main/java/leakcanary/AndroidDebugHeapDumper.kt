package leakcanary

import android.os.Debug
import leakcanary.HeapDumper.DumpLocation
import leakcanary.HeapDumper.DumpLocation.FileLocation
import leakcanary.HeapDumper.Result
import leakcanary.HeapDumper.Result.Failure
import leakcanary.HeapDumper.Result.HeapDump
import leakcanary.internal.friendly.measureDurationMillis

/**
 * Dumps the heap using [Debug.dumpHprofData]. [dumpHeap] is expected to be passed in a
 * [FileLocation] and will otherwise fail.
 *
 * Note: measures the duration of the call to [Debug.dumpHprofData] using
 * [android.os.SystemClock.uptimeMillis].
 */
object AndroidDebugHeapDumper : HeapDumper {
  override fun dumpHeap(dumpLocation: DumpLocation): Result {
    return if (dumpLocation is FileLocation) {
      val outputFile = dumpLocation.file
      try {
        val durationMillis = measureDurationMillis {
          Debug.dumpHprofData(outputFile.absolutePath)
        }
        if (outputFile.length() == 0L) {
          Failure(RuntimeException("Dumped heap file is 0 byte length"))
        } else {
          HeapDump(outputFile, durationMillis)
        }
      } catch (e: Throwable) {
        Failure(e)
      }
    } else {
      Failure(RuntimeException("HeapDumper.DumpLocation is Unspecified"))
    }
  }
}
