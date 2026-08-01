package leakcanary

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Provides a heap dump directory in the no backup directory of the app under test, which needs no
 * permission on any Android version and is excluded from Android Auto Backup.
 */
class TargetContextHeapDumpDirectoryProvider(
  private val heapDumpDirectoryName: String
) : HeapDumpDirectoryProvider {
  override fun heapDumpDirectory() = File(
    InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
    heapDumpDirectoryName
  )
}
