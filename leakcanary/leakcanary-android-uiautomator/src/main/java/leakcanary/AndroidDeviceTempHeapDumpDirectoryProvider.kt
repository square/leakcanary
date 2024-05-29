package leakcanary

import java.io.File

class AndroidDeviceTempHeapDumpDirectoryProvider(
  private val heapDumpDirectoryName: String
) : HeapDumpDirectoryProvider {

  private val heapDumpDirectory by lazy {
    File("/data/local/tmp/", heapDumpDirectoryName).apply {
      mkdir()
      check(exists()) {
        "Expected heap dump directory $absolutePath to exist"
      }
    }
  }

  override fun heapDumpDirectory() = heapDumpDirectory
}
