package leakcanary.internal.activity.screen

import android.text.Html
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import com.squareup.leakcanary.core.R
import leakcanary.internal.DisplayLeakAdapter
import leakcanary.internal.SquigglySpan
import leakcanary.internal.activity.db.LeakTable
import leakcanary.internal.activity.db.LeakTable.LeakDetails
import leakcanary.internal.activity.db.LeakTable.LeakProjection
import leakcanary.internal.activity.db.executeOnDb
import leakcanary.internal.activity.share
import leakcanary.internal.activity.shareHeapDump
import leakcanary.internal.activity.shareToStackOverflow
import leakcanary.internal.activity.ui.SimpleListAdapter
import leakcanary.internal.activity.ui.TimeFormatter
import leakcanary.internal.activity.ui.UiUtils.replaceUrlSpanWithAction
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate
import shark.HeapAnalysisSuccess
import shark.Leak
import shark.LibraryLeak

internal class LeakScreen(
  private val groupHash: String,
  private val selectedHeapAnalysisId: Long? = null
) : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_leak_screen).apply {
      activity.title = resources.getString(R.string.leak_canary_loading_title)
      executeOnDb {
        val leaks = LeakTable.retrieveLeaksByHash(db, groupHash)
        val selectedLeakIndex =
          if (selectedHeapAnalysisId == null) 0 else leaks.indexOfFirst { it.analysisId == selectedHeapAnalysisId }
        updateUi {
          if (leaks.isEmpty()) {
            activity.title = resources.getString(R.string.leak_canary_leak_not_found)
          } else {
            onLeaksRetrieved(leaks, selectedLeakIndex)
          }
        }
      }
    }

  private fun View.onLeaksRetrieved(
    leaks: List<LeakProjection>,
    selectedLeakIndex: Int
  ) {
    val isLibraryLeak = leaks.any { it.isLibraryLeak }
    val isNew = leaks.all { it.isNew }
    val newChipView = findViewById<TextView>(R.id.leak_canary_chip_new)
    val libraryLeakChipView = findViewById<TextView>(R.id.leak_canary_chip_library_leak)
    newChipView.visibility = if (isNew) View.VISIBLE else View.GONE
    libraryLeakChipView.visibility = if (isLibraryLeak) View.VISIBLE else View.GONE

    activity.title = String.format(
        resources.getQuantityText(
            R.plurals.leak_canary_group_screen_title, leaks.size
        ).toString(), leaks.size, leaks[0].groupDescription
    )

    val spinner = findViewById<Spinner>(R.id.leak_canary_spinner)

    spinner.adapter = SimpleListAdapter(R.layout.leak_canary_simple_row, leaks) { view, position ->
      val titleView = view.findViewById<TextView>(R.id.leak_canary_row_text)
      val timeView = view.findViewById<TextView>(R.id.leak_canary_row_small_text)
      titleView.text =
        view.resources.getString(
            R.string.leak_canary_class_has_leaked, leaks[position].classSimpleName
        )
      timeView.text =
        TimeFormatter.formatTimestamp(view.context, leaks[position].createdAtTimeMillis)
    }

    spinner.onItemSelectedListener = object : OnItemSelectedListener {
      override fun onNothingSelected(parent: AdapterView<*>?) {
      }

      override fun onItemSelected(
        parent: AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long
      ) {
        executeOnDb {
          val displayedLeak = leaks[position]
          LeakTable.retrieveLeakById(db, displayedLeak.id)
              ?.let { leak ->
                updateUi {
                  displayLeakTrace(leak)
                }
                if (leak.isNew) {
                  LeakTable.markAsRead(db, displayedLeak.id)
                }
              }
        }
      }
    }
    spinner.setSelection(selectedLeakIndex)
  }

  private fun View.displayLeakTrace(projection: LeakDetails) {
    val leak = projection.leak
    val analysis = projection.analysis

    val listView = findViewById<ListView>(R.id.leak_canary_list)
    listView.alpha = 0f
    listView.animate()
        .alpha(1f)

    val titleText = """
      Open <a href="open_analysis">Heap Dump</a><br><br>
      Share leak trace <a href="share">as text</a> or on <a href="share_stack_overflow">Stack Overflow</a><br><br>
      Share <a href="share_hprof">Heap Dump file</a><br><br>
      References <b><u>underlined</u></b> are the likely causes of the leak.
      Learn more at <a href="https://squ.re/leaks">https://squ.re/leaks</a>
    """.trimIndent() + if (leak is LibraryLeak) "<br><br>" +
        "A <font color='#FFCC32'>Library Leak</font> is a leak coming from the Android Framework or Google libraries.<br><br>" +
        "<b>Leak pattern</b>: ${leak.pattern}<br><br>" +
        "<b>Description</b>: ${leak.description}" else ""

    val title = Html.fromHtml(titleText) as SpannableStringBuilder
    SquigglySpan.replaceUnderlineSpans(title, context)

    replaceUrlSpanWithAction(title) { urlSpan ->
      when (urlSpan) {
        "share" -> {
          {
            share(leakToString(leak, analysis))
          }
        }
        "share_stack_overflow" -> {
          {
            shareToStackOverflow(leakToString(leak, analysis))
          }
        }
        "open_analysis" -> {
          {
            goTo(HeapDumpScreen(projection.analysisId))
          }
        }
        "share_hprof" -> {
          {
            shareHeapDump(analysis.heapDumpFile)
          }
        }
        else -> null
      }
    }

    val adapter = DisplayLeakAdapter(context, leak.leakTrace, title)
    listView.adapter = adapter
  }

  private fun leakToString(
    leak: Leak,
    analysis: HeapAnalysisSuccess
  ) = """$leak

METADATA

${if (analysis.metadata.isNotEmpty()) analysis.metadata.map { "${it.key}: ${it.value}" }.joinToString(
      "\n"
  ) else ""}
Analysis duration: ${analysis.analysisDurationMillis} ms"""
}