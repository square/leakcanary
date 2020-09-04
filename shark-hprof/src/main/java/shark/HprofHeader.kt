package shark

import okio.Okio
import java.io.File

/**
 * Represents the header metadata of a Hprof file.
 */
data class HprofHeader(
  /** Unix timestamp at which the heap was dumped. */
  val heapDumpTimestamp: Long,
  /** Hprof version, which is tied to the runtime where the heap was dumped. */
  val version: HprofVersion,
  /**
   * Size of Hprof identifiers. Identifiers are used to represent UTF8 strings, objects,
   * stack traces, etc. They can have the same size as host pointers or sizeof(void*), but are not
   * required to be.
   */
  val identifierByteSize: Int
) {
  /**
   * How many bytes from the beginning of the file can we find the hprof records at.
   * Version string, 0 delimiter (1 byte), identifier byte size int (4 bytes) ,timestamp long
   * (8 bytes)
   */
  val recordsPosition: Int = version.versionString.toByteArray(Charsets.UTF_8).size + 1 + 4 + 8

  companion object {
    private val supportedVersions = HprofVersion.values()
        .map { it.versionString to it }
        .toMap()

    /**
     * Reads the header of the provided [file] and returns it as a [HprofHeader]
     */
    fun parseHeaderOf(hprofFile: File): HprofHeader {
      val fileLength = hprofFile.length()
      if (fileLength == 0L) {
        throw IllegalArgumentException("Hprof file is 0 byte length")
      }
      val inputStream = hprofFile.inputStream()
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
      source.close()
      return HprofHeader(heapDumpTimestamp, version, identifierByteSize)
    }
  }
}