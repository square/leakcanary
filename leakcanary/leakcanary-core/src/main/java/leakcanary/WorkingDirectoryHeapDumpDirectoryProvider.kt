package leakcanary

import java.io.File

class WorkingDirectoryHeapDumpDirectoryProvider(
  private val heapDumpDirectoryName: String
) : HeapDumpDirectoryProvider {

  private val heapDumpDirectory by lazy {
    File("./", heapDumpDirectoryName).apply {
      mkdir()
      check(exists()) {
        "Expected heap dump directory $absolutePath to exist"
      }
    }
  }

  override fun heapDumpDirectory() = heapDumpDirectory
}
