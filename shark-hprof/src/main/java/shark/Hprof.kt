package shark

import java.io.Closeable
import java.io.File

/**
 * Hprof is deprecated, and we offer partial backward compatibility. Any code that was
 * previously using HprofReader directly now has to call [StreamingHprofReader.readerFor] or
 * [HprofRandomAcccessReader.readerFor]
 */
@Deprecated("Replaced by HprofStreamingReader.readerFor or HprofRandomAccessReader.openReaderFor")
class Hprof private constructor(
  val file: File,
  val header: HprofHeader
) : Closeable {

  val reader: HprofReader = HprofReader(this)

  val heapDumpTimestamp: Long
    get() = header.heapDumpTimestamp

  val hprofVersion: HprofVersion
    get() = HprofVersion.valueOf(header.version.name)

  val fileLength: Long
    get() = file.length()

  private val closeables = mutableListOf<Closeable>()

  /**
   * Maintains backward compatibility because [Hprof.open] returns a closeable. This allows
   * consuming libraries to attach a closeable that will be closed whe [Hprof] is closed.
   */
  fun attachClosable(closeable: Closeable) {
    closeables += closeable
  }

  override fun close() {
    closeables.forEach { it.close() }
  }

  @Deprecated(message = "Moved to top level class", replaceWith = ReplaceWith("shark.HprofVersion"))
  enum class HprofVersion {
    JDK1_2_BETA3,
    JDK1_2_BETA4,
    JDK_6,
    ANDROID;

    val versionString: String
      get() = shark.HprofVersion.valueOf(name).versionString
  }

  companion object {
    @Deprecated(message = "Replaced by HprofStreamingReader.readerFor or HprofRandomAccessReader.openReaderFor")
    fun open(hprofFile: File): Hprof = Hprof(hprofFile, HprofHeader.parseHeaderOf(hprofFile))
  }
}