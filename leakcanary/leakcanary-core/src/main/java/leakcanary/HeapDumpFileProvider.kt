package leakcanary

import java.io.File
import java.util.Date

fun interface HeapDumpFileProvider {

  /**
   * Returns a [File] that can be passed to a [HeapDumper] to dump the heap.
   */
  fun newHeapDumpFile(): File
}
