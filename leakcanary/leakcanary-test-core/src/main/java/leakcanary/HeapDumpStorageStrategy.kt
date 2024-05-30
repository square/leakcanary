package leakcanary

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import shark.HeapDiff
import shark.RepeatingHeapGraphObjectGrowthDetector

interface HeapDumpStorageStrategy : DumpingHeapGraphProvider.HeapDumpClosedListener,
  RepeatingHeapGraphObjectGrowthDetector.CompletionListener {

  /**
   * Deletes heap dumps as soon as we're done traversing them. This is the most disk space
   * efficient strategy.
   */
  class DeleteOnHeapDumpClose(
    private val deleteFile: (File) -> Unit = { it.delete() }
  ) : HeapDumpStorageStrategy {
    override fun onHeapDumpClosed(heapDumpFile: File) {
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
    private val closedHeapDumpFiles = mutableListOf<File>()

    override fun onHeapDumpClosed(heapDumpFile: File) {
      closedHeapDumpFiles += heapDumpFile
    }

    override fun onObjectGrowthDetectionComplete(result: HeapDiff) {
      if (!result.isGrowing) {
        closedHeapDumpFiles.forEach {
          deleteFile(it)
        }
      }
      closedHeapDumpFiles.clear()
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
    private val closedHeapDumpFiles = mutableListOf<File>()

    override fun onHeapDumpClosed(heapDumpFile: File) {
      closedHeapDumpFiles += heapDumpFile
    }

    override fun onObjectGrowthDetectionComplete(result: HeapDiff) {
      if (result.isGrowing) {
        closedHeapDumpFiles.forEach {
          it.zipFile()
        }
      }
      closedHeapDumpFiles.forEach {
        deleteFile(it)
      }
      closedHeapDumpFiles.clear()
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

  override fun onHeapDumpClosed(heapDumpFile: File) = Unit

  override fun onObjectGrowthDetectionComplete(result: HeapDiff) = Unit
}
