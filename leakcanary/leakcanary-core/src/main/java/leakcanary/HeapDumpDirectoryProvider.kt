package leakcanary

import java.io.File

fun interface HeapDumpDirectoryProvider {
  fun heapDumpDirectory(): File
}
