package leakcanary.internal.activity.screen

import android.R.drawable
import android.R.string
import android.app.AlertDialog
import android.app.AlertDialog.Builder
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.squareup.leakcanary.core.R
import com.squareup.leakcanary.core.R.layout
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.LeakTable
import leakcanary.internal.activity.db.executeOnDb
import leakcanary.internal.activity.share
import leakcanary.internal.activity.shareHeapDump
import leakcanary.internal.activity.ui.TimeFormatter
import leakcanary.internal.activity.ui.UiUtils
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goBack
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu
import shark.HeapAnalysis
import shark.HeapAnalysisSuccess
import shark.LibraryLeak
import shark.SharkLog

internal class HeapDumpScreen(
  private val analysisId: Long
) : Screen() {

  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_list).apply {
      activity.title = resources.getString(R.string.leak_canary_loading_title)

      executeOnDb {
        val heapAnalysis = HeapAnalysisTable.retrieve<HeapAnalysisSuccess>(db, analysisId)
        if (heapAnalysis == null) {
          updateUi {
            activity.title = resources.getString(R.string.leak_canary_analysis_deleted_title)
          }
        } else {
          val signatures = heapAnalysis.allLeaks.map { it.signature }
              .toSet()
          val leakReadStatus = LeakTable.retrieveLeakReadStatuses(db, signatures)
          val heapDumpFileExist = heapAnalysis.heapDumpFile.exists()
          updateUi { onSuccessRetrieved(heapAnalysis, leakReadStatus, heapDumpFileExist) }
        }
      }
    }

  private fun View.onSuccessRetrieved(
    heapAnalysis: HeapAnalysisSuccess,
    leakReadStatus: Map<String, Boolean>,
    heapDumpFileExist: Boolean
  ) {

    activity.title = TimeFormatter.formatTimestamp(context, heapAnalysis.createdAtTimeMillis)

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
      if (heapDumpFileExist) {
        menu.add(R.string.leak_canary_options_menu_render_heap_dump)
            .setOnMenuItemClickListener {
              goTo(RenderHeapDumpScreen(heapAnalysis.heapDumpFile))
              true
            }
      }
    }

    val listView = findViewById<ListView>(R.id.leak_canary_list)

    val leaks = heapAnalysis.allLeaks.sortedByDescending { it.leakTraces.size }
        .toList()

    listView.adapter = object : BaseAdapter() {
      override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup
      ) = when (getItemViewType(position)) {
        METADATA -> {
          bindMetadataRow(convertView, parent, heapDumpFileExist, heapAnalysis)
        }
        LEAK_TITLE -> {
          val view = convertView ?: parent.inflate(R.layout.leak_canary_heap_dump_leak_title)
          val leaksTextView = view.findViewById<TextView>(R.id.leak_canary_heap_dump_leaks)
          leaksTextView.text = resources.getQuantityString(
              R.plurals.leak_canary_distinct_leaks,
              leaks.size, leaks.size
          )
          view
        }
        LEAK_ROW -> {
          val view = convertView ?: parent.inflate(R.layout.leak_canary_leak_row)
          val countView = view.findViewById<TextView>(R.id.leak_canary_count_text)
          val descriptionView = view.findViewById<TextView>(R.id.leak_canary_leak_text)
          val timeView = view.findViewById<TextView>(R.id.leak_canary_time_text)
          val newChipView = view.findViewById<TextView>(R.id.leak_canary_chip_new)
          val libraryLeakChipView = view.findViewById<TextView>(R.id.leak_canary_chip_library_leak)

          val leak = leaks[position - 2]

          val isNew = !leakReadStatus.getValue(leak.signature)

          countView.isEnabled = isNew
          countView.text = leak.leakTraces.size.toString()
          newChipView.visibility = if (isNew) VISIBLE else GONE
          libraryLeakChipView.visibility = if (leak is LibraryLeak) VISIBLE else GONE
          descriptionView.text = leak.shortDescription

          val formattedDate =
            TimeFormatter.formatTimestamp(view.context, heapAnalysis.createdAtTimeMillis)
          timeView.text = formattedDate
          view
        }
        else -> {
          throw IllegalStateException("Unexpected type ${getItemViewType(position)}")
        }
      }

      override fun getItem(position: Int) = this

      override fun getItemId(position: Int) = position.toLong()

      override fun getCount() = 2 + leaks.size

      override fun getItemViewType(position: Int) = when (position) {
        0 -> METADATA
        1 -> LEAK_TITLE
        else -> LEAK_ROW
      }

      override fun getViewTypeCount() = 3

      override fun isEnabled(position: Int) = getItemViewType(position) == LEAK_ROW
    }

    listView.setOnItemClickListener { _, _, position, _ ->
      if (position > LEAK_TITLE) {
        goTo(LeakScreen(leaks[position - 2].signature, analysisId))
      }
    }
  }

  private fun View.bindMetadataRow(
    convertView: View?,
    parent: ViewGroup,
    heapDumpFileExist: Boolean,
    heapAnalysis: HeapAnalysisSuccess
  ): View {
    val view = convertView ?: parent.inflate(layout.leak_canary_leak_header)
    val textView = view.findViewById<TextView>(R.id.leak_canary_header_text)
    textView.movementMethod = LinkMovementMethod.getInstance()

    val explore =
      if (heapDumpFileExist) """Explore <a href="explore_hprof">Heap Dump</a><br><br>""" else ""
    val shareAnalysis = """Share <a href="share">Heap Dump analysis</a><br><br>"""
    val printAnalysis = """Print analysis <a href="print">to Logcat</a> (tag: LeakCanary)<br><br>"""
    val shareFile =
      if (heapDumpFileExist) """Share <a href="share_hprof">Heap Dump file</a><br><br>""" else ""

    val seeMetadata = "See <a href=\"metadata\">Metadata</a>"

    val dumpDurationMillis =
      if (heapAnalysis.dumpDurationMillis != HeapAnalysis.DUMP_DURATION_UNKNOWN) {
        "${heapAnalysis.dumpDurationMillis} ms"
      } else {
        "Unknown"
      }
    val metadata = (heapAnalysis.metadata + mapOf(
        "Analysis duration" to "${heapAnalysis.analysisDurationMillis} ms",
        "Heap dump file path" to heapAnalysis.heapDumpFile.absolutePath,
        "Heap dump timestamp" to "${heapAnalysis.createdAtTimeMillis}",
        "Heap dump duration" to dumpDurationMillis
    ))
        .map { "<b>${it.key}:</b> ${it.value}" }
        .joinToString("<br>")
    val titleText = explore + shareAnalysis + printAnalysis + shareFile + seeMetadata
    val title = Html.fromHtml(titleText) as SpannableStringBuilder

    UiUtils.replaceUrlSpanWithAction(title) { urlSpan ->
      when (urlSpan) {
        "explore_hprof" -> {
          {
            goTo(HprofExplorerScreen(heapAnalysis.heapDumpFile))
          }
        }
        "share" -> {
          {
            share(LeakTraceWrapper.wrap(heapAnalysis.toString(), 80))
          }
        }
        "print" -> {
          {
            SharkLog.d { "\u200B\n" + LeakTraceWrapper.wrap(heapAnalysis.toString(), 120) }
          }
        }
        "share_hprof" -> {
          {
            shareHeapDump(heapAnalysis.heapDumpFile)
          }
        }
        "metadata" -> {
          {
            Builder(context)
                .setIcon(drawable.ic_dialog_info)
                .setTitle("Metadata")
                .setMessage(Html.fromHtml(metadata))
                .setPositiveButton(string.ok, null)
                .show()
          }
        }
        else -> null
      }
    }

    textView.text = title
    return view
  }

  companion object {
    const val METADATA = 0
    const val LEAK_TITLE = 1
    const val LEAK_ROW = 2
  }
}
