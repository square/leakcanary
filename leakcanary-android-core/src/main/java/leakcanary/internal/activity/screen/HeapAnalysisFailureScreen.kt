package leakcanary.internal.activity.screen

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.squareup.leakcanary.core.BuildConfig
import com.squareup.leakcanary.core.R
import leakcanary.HeapAnalysisFailure
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.executeOnDb
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
      activity.title = resources.getString(R.string.leak_canary_loading_title)
      executeOnDb {
        val heapAnalysis = HeapAnalysisTable.retrieve<HeapAnalysisFailure>(db, analysisId)
        if (heapAnalysis == null) {
          updateUi {
            activity.title = resources.getString(R.string.leak_canary_analysis_deleted_title)
          }
        } else {
          val heapDumpFileExist = heapAnalysis.heapDumpFile.exists()
          updateUi { onFailureRetrieved(heapAnalysis, heapDumpFileExist) }
        }
      }
    }

  private fun View.onFailureRetrieved(
    heapAnalysis: HeapAnalysisFailure,
    heapDumpFileExist: Boolean
  ) {
    activity.title = resources.getString(R.string.leak_canary_analysis_failed)

    val failureTextView = findViewById<TextView>(R.id.leak_canary_failure)
    val path = heapAnalysis.heapDumpFile.absolutePath

    val failureText = """
          |${resources.getString(R.string.leak_canary_failure_report)}
          |LeakCanary ${BuildConfig.LIBRARY_VERSION} ${BuildConfig.GIT_SHA}
          |${heapAnalysis.exception}
          |${resources.getString(R.string.leak_canary_download_dump, path)}
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
            executeOnDb {
              HeapAnalysisTable.delete(db, analysisId, heapAnalysis.heapDumpFile)
              updateUi {
                goBack()
              }
            }
            true
          }
      if (heapDumpFileExist) {
        menu.add(R.string.leak_canary_share_heap_dump)
            .setOnMenuItemClickListener {
              shareHeapDump(heapAnalysis.heapDumpFile)
              true
            }
      }
    }
  }
}