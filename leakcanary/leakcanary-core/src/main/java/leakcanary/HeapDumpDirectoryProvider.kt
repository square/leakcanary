package leakcanary

import java.io.File

fun interface HeapDumpDirectoryProvider {
  /**
   * Expected to be called only once per [HeapDumpFileProvider] implementation instance.
   */
  fun heapDumpDirectory(): File
}
