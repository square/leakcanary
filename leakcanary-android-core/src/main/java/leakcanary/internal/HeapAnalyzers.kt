package leakcanary.internal

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.io.File

object HeapAnalyzers {

  const val HEAPDUMP_FILE_EXTRA = "HEAPDUMP_FILE_EXTRA"
  private const val ANALYSIS_ACTION = "leakcanary.ANALYSIS_ACTION"

  fun runAnalysis(
    context: Context,
    heapDumpFile: File
  ) {
    val intent = Intent(ANALYSIS_ACTION)
    intent.setPackage(context.packageName)
    intent.putExtra(HEAPDUMP_FILE_EXTRA, heapDumpFile)
    ContextCompat.startForegroundService(context, intent)
  }
}