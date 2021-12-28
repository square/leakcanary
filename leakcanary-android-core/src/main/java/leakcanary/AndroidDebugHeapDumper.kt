package leakcanary

import android.os.Debug
import java.io.File

/**
 * Dumps the Android heap using [Debug.dumpHprofData].
 *
 * Note: despite being part of the Debug class, [Debug.dumpHprofData] can be called from non
 * debuggable non profileable builds.
 */
object AndroidDebugHeapDumper : HeapDumper {
  override fun dumpHeap(heapDumpFile: File) {
    Debug.dumpHprofData(heapDumpFile.absolutePath)
  }
}
