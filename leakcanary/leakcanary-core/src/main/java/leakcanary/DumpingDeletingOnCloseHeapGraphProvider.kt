package leakcanary

import shark.CloseableHeapGraph
import shark.HeapGraphProvider
import shark.HprofHeapGraph.Companion.openHeapGraph

class DumpingDeletingOnCloseHeapGraphProvider(
  private val heapDumpFileProvider: HeapDumpFileProvider,
  private val heapDumper: HeapDumper
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
        realGraph.close()
        heapDumpFile.delete()
      }
    }
  }
}
