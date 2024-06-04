package leakcanary

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import shark.HeapDiff
import shark.SharkLog

interface HeapDumpStorageStrategy {

  /**
   * Deletes heap dumps as soon as we're done traversing them. This is the most disk space
   * efficient strategy.
   */
  class DeleteOnHeapDumpClose(
    private val deleteFile: (File) -> Unit = { it.delete() }
  ) : HeapDumpStorageStrategy {
    override fun onHeapDumpClosed(heapDumpFile: File) {
      SharkLog.d { "DeleteOnHeapDumpClose: deleting closed heap dump ${heapDumpFile.absolutePath}" }
      deleteFile(heapDumpFile)
    }
  }

  /**
   * No deletion of heap dump files. This is useful if you intend to open up the heap dumps
   * directly or re run the analysis no matter the outcome.
   */
  object KeepHeapDumps : HeapDumpStorageStrategy

  /**
   * Keeps the heap dumps until we're done diffing, then delete them only if there are no growing
   * objects. This is useful if you intend to open up the heap dumps directly or re run
   * the analysis on failure.
   */
  class KeepHeapDumpsOnObjectsGrowing(
    private val deleteFile: (File) -> Unit = { it.delete() }
  ) : HeapDumpStorageStrategy {
    // This assumes the detector instance is always used from the same thread, which seems like a
    // safe enough assumption for tests.
    private val heapDumpFiles = mutableListOf<File>()

    override fun onHeapDumped(heapDumpFile: File) {
      heapDumpFiles += heapDumpFile
    }

    override fun onHeapDiffResult(result: Result<HeapDiff>) {
      if (result.isSuccess && !result.getOrThrow().isGrowing) {
        SharkLog.d {
          "KeepHeapDumpsOnObjectsGrowing: not growing, deleting heap dumps:" +
            heapDumpFiles.joinToString(
              prefix = "\n",
              separator = "\n"
            ) { it.absolutePath }
        }
        heapDumpFiles.forEach {
          deleteFile(it)
        }
      } else {
        SharkLog.d {
          "KeepHeapDumpsOnObjectsGrowing: failure or growing, keeping heap dumps:" +
            heapDumpFiles.joinToString(
              prefix = "\n",
              separator = "\n"
            ) { it.absolutePath }
        }
      }
      heapDumpFiles.clear()
    }
  }

  /**
   * Keeps the heap dumps until we're done diffing, then on completion creates a zip for each heap
   * dump if there are growing object, and delete all the source heap dumps.
   * This is useful if you intend to upload the heap dumps on failure in CI and you
   * want to keep disk space, network usage and cloud storage low. Zipped heap dumps are typically
   * 4x smaller so this is worth it, although the trade off is that zipping can add a few seconds
   * per heap dump to the runtime duration of a test.
   */
  class KeepZippedHeapDumpsOnObjectsGrowing(
    private val deleteFile: (File) -> Unit = { it.delete() }
  ) : HeapDumpStorageStrategy {
    // This assumes the detector instance is always used from the same thread, which seems like a
    // safe enough assumption for tests.
    private val heapDumpFiles = mutableListOf<File>()

    override fun onHeapDumped(heapDumpFile: File) {
      heapDumpFiles += heapDumpFile
    }

    override fun onHeapDiffResult(result: Result<HeapDiff>) {
      if (result.isFailure || result.getOrThrow().isGrowing) {
        SharkLog.d {
          "KeepZippedHeapDumpsOnObjectsGrowing: failure or growing, zipping heap dumps:" +
            heapDumpFiles.joinToString(
              prefix = "\n",
              separator = "\n"
            ) { it.absolutePath }
        }
        heapDumpFiles.forEach {
          it.zipFile()
        }
      } else {
        SharkLog.d {
          "KeepZippedHeapDumpsOnObjectsGrowing: not growing, deleting heap dumps:" +
            heapDumpFiles.joinToString(
              prefix = "\n",
              separator = "\n"
            ) { it.absolutePath }
        }
      }
      heapDumpFiles.forEach {
        deleteFile(it)
      }
      heapDumpFiles.clear()
    }

    private fun File.zipFile(destination: File = File(parent, "$nameWithoutExtension.zip")): File {
      ZipOutputStream(destination.outputStream()).use { zipOutputStream ->
        zipOutputStream.putNextEntry(ZipEntry(name))
        inputStream().use {
          it.copyTo(
            out = zipOutputStream,
            // 200 KB, an optimal buffer size from experimenting with different buffer sizes for
            // a 41 MB heap dump on a Pixel 7.
            // https://publicobject.com/2020/09/14/many-correct-answers/
            bufferSize = 200_000
          )
        }
      }
      return destination
    }
  }

  fun onHeapDumpClosed(heapDumpFile: File) = Unit

  fun onHeapDumped(heapDumpFile: File) = Unit

  fun onHeapDiffResult(result: Result<HeapDiff>) = Unit
}
