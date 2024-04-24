package leakcanary

import java.io.File
import java.util.Date

fun interface HeapDumpFileProvider {

  /**
   * Returns a [File] that can be passed to a [HeapDumper] to dump the heap.
   */
  fun newHeapDumpFile(): File

  /**
   * This allows external modules to add factory methods for implementations of this interface as
   * extension functions of this companion object.
   */
  companion object
}
