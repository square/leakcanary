package shark

import okio.Okio
import shark.Hprof.Companion.open
import java.io.File

/**
 * Represents a Hprof file and its header metadata, can be used to read the heap dump content
 * with either [streamingReader] or [randomAccessReader].
 */
class HprofFile private constructor(
  val file: File,
  /** Unix timestamp at which the heap was dumped. */
  val heapDumpTimestamp: Long,
  /** Hprof version, which is tied to the runtime where the heap was dumped. */
  val version: HprofVersion,
  /**
   * Size of Hprof identifiers. Identifiers are used to represent UTF8 strings, objects,
   * stack traces, etc. They can have the same size as host pointers or sizeof(void*), but are not
   * required to be.
   */
  val identifierByteSize: Int,
  /**
   * How many bytes from the beginning of the file can we find the hprof records at.
   */
  val recordsPosition: Long
) {

  /**
   * Length of the hprof, in bytes.
   */
  val length = file.length().apply {
    require(this != 0L) {
      "Hprof file is 0 byte length"
    }
  }

  fun streamingReader() = HprofStreamingReader(this)

  fun randomAccessReader() = HprofRandomAccessReader(this)

  companion object {
    private val supportedVersions = HprofVersion.values()
        .map { it.versionString to it }
        .toMap()

    /**
     * Reads the headers of the provided [file] and returns a [HprofFile] that can be used
     * to read the hprof.
     */
    fun hprofFile(file: File): HprofFile {
      val fileLength = file.length()
      if (fileLength == 0L) {
        throw IllegalArgumentException("Hprof file is 0 byte length")
      }
      val inputStream = file.inputStream()
      val source = Okio.buffer(Okio.source(inputStream))
      val endOfVersionString = source.indexOf(0)
      val versionName = source.readUtf8(endOfVersionString)

      val version = supportedVersions[versionName]
      checkNotNull(version) {
        "Unsupported Hprof version [$versionName] not in supported list ${supportedVersions.keys}"
      }
      // Skip the 0 at the end of the version string.
      source.skip(1)
      val identifierByteSize = source.readInt()
      val heapDumpTimestamp = source.readLong()

      val byteReadCount = endOfVersionString + 1 + 4 + 8
      source.close()
      return HprofFile(
          file = file,
          heapDumpTimestamp = heapDumpTimestamp,
          version = version,
          identifierByteSize = identifierByteSize,
          recordsPosition = byteReadCount
      )
    }
  }
}
