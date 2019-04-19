package leakcanary.internal

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import leakcanary.HeapDump

object HeapAnalyzers {

  const val HEAPDUMP_EXTRA = "HEAPDUMP_EXTRA"
  private const val ANALYSIS_ACTION = "leakcanary.ANALYSIS_ACTION"

  fun runAnalysis(
    context: Context,
    heapDump: HeapDump
  ) {
    val intent = Intent(ANALYSIS_ACTION)
    intent.setPackage(context.packageName)
    intent.putExtra(HEAPDUMP_EXTRA, heapDump)
    ContextCompat.startForegroundService(context, intent)
  }
}