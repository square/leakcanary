package leakcanary

import java.io.File

class AndroidDeviceTempHeapDumpDirectoryProvider(
  private val heapDumpDirectoryName: String
) : HeapDumpDirectoryProvider {
  override fun heapDumpDirectory() = File("/data/local/tmp/", heapDumpDirectoryName)
}
