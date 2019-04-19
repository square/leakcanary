package leakcanary.internal.activity.screen

import android.view.ViewGroup
import android.widget.TextView
import com.squareup.leakcanary.BuildConfig
import com.squareup.leakcanary.R
import com.squareup.leakcanary.R.string
import leakcanary.HeapAnalysisFailure
import leakcanary.internal.activity.db
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.share
import leakcanary.internal.activity.shareHeapDump
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goBack
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu

internal class HeapAnalysisFailureScreen(
  private val analysisId: Long
) : Screen() {

  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_heap_analysis_failure_screen).apply {
      val pair = HeapAnalysisTable.retrieve<HeapAnalysisFailure>(db, analysisId)

      if (pair == null) {
        activity.title = "Analysis deleted"
        return this
      }

      val (heapAnalysis, _) = pair

      activity.title = resources.getString(R.string.leak_canary_analysis_failed)

      val failureTextView = findViewById<TextView>(R.id.leak_canary_failure)
      val path = heapAnalysis.heapDump.heapDumpFile.absolutePath

      val failureText = """
          |${resources.getString(string.leak_canary_failure_report)}
          |LeakCanary ${BuildConfig.LIBRARY_VERSION} ${BuildConfig.GIT_SHA}
          |${heapAnalysis.exception}
          |${resources.getString(string.leak_canary_download_dump, path)}
          """.trimMargin()
      failureTextView.text = failureText

      onCreateOptionsMenu { menu ->
        menu.add(R.string.leak_canary_share_leak)
            .setOnMenuItemClickListener {
              // TODO Add version information
              share(heapAnalysis.exception.toString())
              true
            }
        menu.add(R.string.leak_canary_delete)
            .setOnMenuItemClickListener {
              HeapAnalysisTable.delete(db, analysisId, heapAnalysis.heapDump)
              goBack()
              true
            }
        if (heapAnalysis.heapDump.heapDumpFile.exists()) {
          menu.add(R.string.leak_canary_share_heap_dump)
              .setOnMenuItemClickListener {
                shareHeapDump(heapAnalysis.heapDump)
                true
              }
        }

      }
    }

}