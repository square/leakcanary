package shark

import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel

class Hprof private constructor(
  private val channel: FileChannel,
  private val source: BufferedSource,
  val reader: HprofReader,
  val heapDumpTimestamp: Long
) : Closeable {

  private var lastReaderByteReadCount = reader.byteReadCount
  private var lastKnownPosition = reader.byteReadCount

  override fun close() {
    source.close()
  }

  fun moveReaderTo(newPosition: Long) {
    val currentPosition = lastKnownPosition + (reader.byteReadCount - lastReaderByteReadCount)

    if (currentPosition == newPosition) {
      return
    }
    source.buffer.clear()
    channel.position(newPosition)
    lastReaderByteReadCount = reader.byteReadCount
    lastKnownPosition = newPosition
  }

  enum class HprofVersion(val versionString: String) {
    JDK1_2_BETA3("JAVA PROFILE 1.0"),
    JDK1_2_BETA4("JAVA PROFILE 1.0.1"),
    JDK_6("JAVA PROFILE 1.0.2"),
    ANDROID("JAVA PROFILE 1.0.3")
  }

  companion object {
    private val supportedVersions = HprofVersion.values()
        .map { it.versionString }

    fun open(hprofFile: File): Hprof {
      if (hprofFile.length() == 0L) {
        throw IllegalArgumentException("Hprof file is 0 byte length")
      }
      val inputStream = hprofFile.inputStream()
      val channel = inputStream.channel
      val source = inputStream.source()
          .buffer()

      val endOfVersionString = source.indexOf(0)
      val version = source.readUtf8(endOfVersionString)
      require(version in supportedVersions) {
        "Unsupported Hprof version [$version] not in supported list $supportedVersions"
      }
      // Skip the 0 at the end of the version string.
      source.skip(1)
      val objectIdByteSize = source.readInt()

      // heap dump timestamp
      val heapDumpTimestamp = source.readLong()

      val byteReadCount = endOfVersionString + 1 + 4 + 8

      val reader = HprofReader(source, byteReadCount, objectIdByteSize)

      return Hprof(
          channel, source, reader, heapDumpTimestamp
      )
    }
  }

}