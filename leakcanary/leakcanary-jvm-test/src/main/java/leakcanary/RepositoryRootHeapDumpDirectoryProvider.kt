package leakcanary

import java.io.File

class RepositoryRootHeapDumpDirectoryProvider(
  private val heapDumpDirectoryName: String
) : HeapDumpDirectoryProvider {

  private val heapDumpDirectory by lazy {
    File(projectRootDirectory(), heapDumpDirectoryName).apply {
      mkdir()
      check(exists()) {
        "Expected heap dump directory $absolutePath to exist"
      }
    }
  }

  private fun projectRootDirectory(): File {
    var currentDirectory = File("")
    // Going through absolute path string otherwise parentFile returns null.
    currentDirectory = File(currentDirectory.absolutePath)
    while (".git" !in currentDirectory) {
      currentDirectory = currentDirectory.parentFile!!
    }
    return currentDirectory
  }

  private operator fun File.contains(filename: String): Boolean {
    return listFiles()?.any { it.name == filename } ?: false
  }

  override fun heapDumpDirectory() = heapDumpDirectory
}
