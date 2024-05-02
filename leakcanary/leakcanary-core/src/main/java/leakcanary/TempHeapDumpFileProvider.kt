package leakcanary

import java.io.File

object TempHeapDumpFileProvider : HeapDumpFileProvider {
  override fun newHeapDumpFile(): File {
    val heapDumpFile = File.createTempFile("heap-growth", ".hprof", null)
    check(heapDumpFile.delete()) {
      "Could not delete $heapDumpFile, needs to not exist for heap dump"
    }
    return heapDumpFile
  }
}

fun HeapDumpFileProvider.Companion.tempFile(): HeapDumpFileProvider =
  TempHeapDumpFileProvider
