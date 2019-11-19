package leakcanary.internal.activity.screen

import android.text.Html
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.squareup.leakcanary.core.R
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.executeOnDb
import leakcanary.internal.activity.shareHeapDump
import leakcanary.internal.activity.shareToGitHubIssue
import leakcanary.internal.activity.ui.UiUtils
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goBack
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu
import shark.HeapAnalysisFailure

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

    val failureText = """
      Please <a href="file_issue">click here</a> to file a bug report.
      The stacktrace details will be copied into the clipboard and you just need to paste into the
      GitHub issue description.""" + (if (heapDumpFileExist) {
      """
        <br><br>To help reproduce the issue, please share the 
        <a href="share_hprof">Heap Dump file</a> and upload it to the GitHub issue.
      """
    } else "")

    val failure = Html.fromHtml(failureText) as SpannableStringBuilder

    UiUtils.replaceUrlSpanWithAction(failure) { urlSpan ->
      when (urlSpan) {
        "file_issue" -> {
          {
            shareToGitHubIssue(heapAnalysis)
          }
        }
        "share_hprof" -> {
          {
            shareHeapDump(heapAnalysis.heapDumpFile)
          }
        }
        else -> null
      }
    }
    findViewById<TextView>(R.id.leak_canary_header_text).apply {
      movementMethod = LinkMovementMethod.getInstance()
      text = failure
    }

    findViewById<TextView>(R.id.leak_canary_stacktrace).text = heapAnalysis.exception.toString()

    onCreateOptionsMenu { menu ->
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
    }
  }
}