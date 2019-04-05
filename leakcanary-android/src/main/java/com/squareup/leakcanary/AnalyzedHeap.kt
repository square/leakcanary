package com.squareup.leakcanary

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * Wraps a [HeapDump] and corresponding [AnalysisResult].
 */
class AnalyzedHeap(
  val heapDump: HeapDump,
  val result: AnalysisResult,
  val selfFile: File
) {
  val heapDumpFileExists: Boolean = heapDump.heapDumpFile.exists()
  val selfLastModified: Long = selfFile.lastModified()

  companion object {

    fun save(
      heapDump: HeapDump,
      result: AnalysisResult
    ): File? {
      val analyzedHeapfile = File(
          heapDump.heapDumpFile.parentFile,
          heapDump.heapDumpFile.name + ".result"
      )
      var fos: FileOutputStream? = null
      try {
        fos = FileOutputStream(analyzedHeapfile)
        val oos = ObjectOutputStream(fos)
        oos.writeObject(heapDump)
        oos.writeObject(result)
        return analyzedHeapfile
      } catch (e: IOException) {
        CanaryLog.d(e, "Could not save leak analysis result to disk.")
      } finally {
        if (fos != null) {
          try {
            fos.close()
          } catch (ignored: IOException) {
          }

        }
      }
      return null
    }

    fun load(resultFile: File): AnalyzedHeap? {
      var fis: FileInputStream? = null
      try {
        fis = FileInputStream(resultFile)
        val ois = ObjectInputStream(fis)
        val heapDump = ois.readObject() as HeapDump
        val result = ois.readObject() as AnalysisResult
        return AnalyzedHeap(heapDump, result, resultFile)
      } catch (e: IOException) {
        // Likely a change in the serializable result class.
        // Let's remove the files, we can't read them anymore.
        val deleted = resultFile.delete()
        if (deleted) {
          CanaryLog.d(e, "Could not read result file %s, deleted it.", resultFile)
        } else {
          CanaryLog.d(
              e, "Could not read result file %s, could not delete it either.",
              resultFile
          )
        }
      } catch (e: ClassNotFoundException) {
        val deleted = resultFile.delete()
        if (deleted) {
          CanaryLog.d(e, "Could not read result file %s, deleted it.", resultFile)
        } else {
          CanaryLog.d(e, "Could not read result file %s, could not delete it either.", resultFile)
        }
      } finally {
        if (fis != null) {
          try {
            fis.close()
          } catch (ignored: IOException) {
          }

        }
      }
      return null
    }
  }
}
