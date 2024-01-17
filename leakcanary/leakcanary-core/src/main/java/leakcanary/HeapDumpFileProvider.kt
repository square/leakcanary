package leakcanary

import java.io.File
import java.util.Date

fun interface HeapDumpFileProvider {

  /**
   * Returns a [File] that can be passed to a [HeapDumper] to dump the heap.
   */
  fun newHeapDumpFile(): File

  companion object {
    fun dateFormatted(
      directory: File,
      prefix: String = "",
      suffix: String = "",
      dateProvider: () -> Date = { Date() },
    ): HeapDumpFileProvider {
      return DateFormatHeapDumpFileProvider(
        heapDumpDirectoryProvider = {
          directory.apply {
            mkdirs()
            check(exists()) {
              "Expected heap dump folder to exist: $absolutePath"
            }
          }
        },
        dateProvider = dateProvider,
        prefix = prefix,
        suffix = suffix
      )
    }
  }
}
