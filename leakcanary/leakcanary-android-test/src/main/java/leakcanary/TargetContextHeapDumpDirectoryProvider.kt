package leakcanary

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

class TargetContextHeapDumpDirectoryProvider(
  private val heapDumpDirectoryName: String
) : HeapDumpDirectoryProvider {
  override fun heapDumpDirectory() = File(
    InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
    heapDumpDirectoryName
  )
}
