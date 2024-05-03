package leakcanary

import java.io.File
import shark.CloseableHeapGraph
import shark.HeapGraph
import shark.HeapGraphProvider
import shark.HprofHeapGraph.Companion.openHeapGraph

class DumpingAndDeletingHeapGraphProvider(
  private val heapDumpFileProvider: HeapDumpFileProvider,
  private val heapDumper: HeapDumper,
  private val fileDeleter: FileDeleter
) : HeapGraphProvider {
  override fun openHeapGraph(): CloseableHeapGraph {
    val heapDumpFile = heapDumpFileProvider.newHeapDumpFile()
    heapDumper.dumpHeap(heapDumpFile)
    check(heapDumpFile.exists()) {
      "Expected file to exist after heap dump: ${heapDumpFile.absolutePath}"
    }
    val realGraph = heapDumpFile.openHeapGraph()
    return object : CloseableHeapGraph by realGraph {
      override fun close() {
        realGraph.close()
        fileDeleter.delete(heapDumpFile)
      }
    }
  }
}

fun interface FileDeleter {
  fun delete(file: File)
}

fun HeapGraphProvider.Companion.dumpingAndDeleting(
  heapDumper: HeapDumper,
  heapDumpFileProvider: HeapDumpFileProvider = TempHeapDumpFileProvider,
  fileDeleter: FileDeleter = FileDeleter { it.delete() }
) = DumpingAndDeletingHeapGraphProvider(heapDumpFileProvider, heapDumper, fileDeleter)
