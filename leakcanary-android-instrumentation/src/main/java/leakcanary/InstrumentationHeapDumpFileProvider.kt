package leakcanary

import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Provides unique file names for each heap dump in instrumentation tests.
 */
class InstrumentationHeapDumpFileProvider(
  private val heapDumpDirectory: File = getInstrumentation().targetContext.filesDir
) {

  /**
   * Returns a file for where the heap should be dumped.
   */
  fun newHeapDumpFile(): File {
    // File name is unique as analysis may run several times per test
    val fileName =
      SimpleDateFormat("'instrumentation_tests_'yyyy-MM-dd_HH-mm-ss_SSS'.hprof'", Locale.US)
        .format(Date())
    return File(heapDumpDirectory, fileName)
  }
}
