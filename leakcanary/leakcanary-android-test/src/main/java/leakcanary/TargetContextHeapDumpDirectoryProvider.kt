package leakcanary

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

class TargetContextHeapDumpDirectoryProvider(
  private val heapDumpDirectoryName: String
) : HeapDumpDirectoryProvider {

  private val heapDumpDirectory by lazy {
    File(
      InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
      heapDumpDirectoryName
    ).apply {
      mkdir()
      check(exists()) {
        "Expected heap dump directory $absolutePath to exist"
      }
    }
  }

  override fun heapDumpDirectory() = heapDumpDirectory
}
