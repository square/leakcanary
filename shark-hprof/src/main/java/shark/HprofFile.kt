package shark

import okio.Buffer
import okio.Okio
import shark.HprofHeader.Companion
import java.io.File

/**
 * Represents a Hprof file and its header metadata, can be used to read the heap dump content
 * with either [streamingReader] or [randomAccessReader].
 *
 *  Binary Dump Format reference: http://hg.openjdk.java.net/jdk6/jdk6/jdk/raw-file/tip/src/share/demo/jvmti/hprof/manual.html#mozTocId848088
 *
 * The Android Hprof format differs in some ways from that reference. This parser implementation
 * is largely adapted from https://android.googlesource.com/platform/tools/base/+/studio-master-dev/perflib/src/main/java/com/android/tools/perflib
 */
class HprofFile private constructor(
  val file: File,
  val header: HprofHeader
) {

  fun streamingReader(): HprofStreamingReader {
    return HprofStreamingReader.createStreamingReaderFor(file, header)
  }

  fun randomAccessReader(): HprofRandomAccessReader {
    return HprofRandomAccessReader.openRandomAccessReaderFor(file, header)
  }

  companion object {
    /**
     * Reads the headers of the provided [file] and returns a [HprofFile] that can be used
     * to read the hprof.
     */
    fun hprofFile(file: File): HprofFile {
      return HprofFile(
          file = file,
          header = HprofHeader.parseHeaderOf(file)
      )
    }
  }
}
