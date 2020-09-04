package shark

import java.io.Closeable
import java.io.File

/**
 * Hprof is deprecated, and we offer partial backward compatibility. Any code that was
 * previously using HprofReader directly now has to call [HprofFile.streamingReader] or
 * [HprofFile.randomAccessReader]
 */
@Deprecated(
    "Replaced by HprofFile",
    replaceWith = ReplaceWith("shark.HprofFile")
)
class Hprof private constructor(
  val file: HprofFile
) : Closeable {

  val reader = HprofReader(file)

  val heapDumpTimestamp: Long
    get() = file.heapDumpTimestamp

  val hprofVersion: HprofVersion
    get() = HprofVersion.valueOf(file.version.name)

  val fileLength: Long
    get() = file.length

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
    @Deprecated(
        message = "Replaced by non closable HprofFile",
        replaceWith = ReplaceWith("shark.HprofFile.hprofFile(hprofFile)")
    )
    fun open(hprofFile: File) = Hprof(HprofFile.hprofFile(hprofFile))
  }
}