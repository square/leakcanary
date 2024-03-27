package leakcanary

import java.io.File

fun interface HeapDumper {

  /**
   * Dumps the heap. The implementation is expected to be blocking until the heap is dumped
   * or heap dumping failed.
   *
   * Implementations can throw a runtime exception if heap dumping failed.
   */
  fun dumpHeap(heapDumpFile: File)
}
