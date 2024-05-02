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

  /**
   * This allows external modules to add factory methods for implementations of this interface as
   * extension functions of this companion object.
   */
  companion object
}

fun HeapDumper.withGc(gcTrigger: GcTrigger = GcTrigger.inProcess()): HeapDumper {
  val delegate = this
  return HeapDumper { file ->
    gcTrigger.runGc()
    delegate.dumpHeap(file)
  }
}
