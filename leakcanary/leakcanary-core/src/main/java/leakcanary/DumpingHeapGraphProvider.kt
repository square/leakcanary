package leakcanary

import java.io.File
import leakcanary.DumpingHeapGraphProvider.HeapDumpClosedListener
import shark.CloseableHeapGraph
import shark.HeapGraphProvider
import shark.HprofHeapGraph.Companion.openHeapGraph

class DumpingHeapGraphProvider(
  private val heapDumpFileProvider: HeapDumpFileProvider,
  private val heapDumper: HeapDumper,
  private val heapDumpClosedListener: HeapDumpClosedListener = HeapDumpClosedListener {}
) : HeapGraphProvider {
  override fun openHeapGraph(): CloseableHeapGraph {
    val heapDumpFile = heapDumpFileProvider.newHeapDumpFile()
    heapDumper.dumpHeap(heapDumpFile)
    check(heapDumpFile.exists()) {
      "Expected file to exist after heap dump: ${heapDumpFile.absolutePath}"
    }
    val realGraph = heapDumpFile.openHeapGraph()
    return object : CloseableHeapGraph by realGraph {
      override fun close() {
        try {
          realGraph.close()
        } finally {
          heapDumpClosedListener.onHeapDumpClosed(heapDumpFile)
        }
      }
    }
  }

  fun interface HeapDumpClosedListener {
    fun onHeapDumpClosed(heapDumpFile: File)
  }
}
