package leakcanary.internal.activity.screen

import android.app.ActivityManager
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.squareup.leakcanary.core.R
import java.util.UUID
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.executeOnDb
import leakcanary.internal.activity.shareHeapDump
import leakcanary.internal.activity.shareToGitHubIssue
import leakcanary.internal.activity.ui.UiUtils
import leakcanary.internal.isOutOfMemory
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

    val failureText = if (heapAnalysis.isOutOfMemory) {
      outOfMemoryText(heapDumpFileExist)
    } else {
      bugReportText(heapDumpFileExist)
    }

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
        "try_again" -> {
          {
            InternalLeakCanary.sendEvent(
              HeapDump(
                uniqueId = UUID.randomUUID().toString(),
                file = heapAnalysis.heapDumpFile,
                durationMillis = heapAnalysis.dumpDurationMillis,
                reason = "Retrying heap analysis after failure."
              )
            )
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
      if (!ActivityManager.isUserAMonkey()) {
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

  /**
   * Running out of memory isn't a bug to report, it's a heap dump that needs more memory than this
   * process has, so this points at the ways out of it instead of at the issue tracker. The failure
   * message shown right below this text spells all of them out, see `withOutOfMemoryGuidance`.
   */
  private fun outOfMemoryText(heapDumpFileExist: Boolean): String {
    return if (heapDumpFileExist) {
      """
        The analysis ran out of memory. Kill the app then
        <a href="try_again">run the analysis again</a>, or share the
        <a href="share_hprof">Heap Dump file</a> to analyze it on your computer instead.
        The details below list every option.
      """
    } else {
      """
        The analysis ran out of memory, and the heap dump file is gone so it can't be analyzed
        again. The details below list how to give the next analysis more memory.
      """
    }
  }

  private fun bugReportText(heapDumpFileExist: Boolean): String {
    return if (heapDumpFileExist) {
      "You can <a href=\"try_again\">run the analysis again</a>.<br><br>"
    } else {
      ""
    } + """
      Please <a href="file_issue">click here</a> to file a bug report.
      The stacktrace details will be copied into the clipboard and you just need to paste into the
      GitHub issue description.""" + (if (heapDumpFileExist) {
      """
        <br><br>To help reproduce the issue, please share the
        <a href="share_hprof">Heap Dump file</a> and upload it to the GitHub issue.
      """
    } else "")
  }
}
