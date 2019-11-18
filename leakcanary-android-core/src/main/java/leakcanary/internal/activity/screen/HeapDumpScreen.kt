package leakcanary.internal.activity.screen

import android.text.Html
import android.text.SpannableStringBuilder
import android.text.format.DateUtils
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.squareup.leakcanary.core.R
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.LeakTable
import leakcanary.internal.activity.db.LeakTable.HeapAnalysisGroupProjection
import leakcanary.internal.activity.db.executeOnDb
import leakcanary.internal.activity.share
import leakcanary.internal.activity.shareHeapDump
import leakcanary.internal.activity.ui.UiUtils
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goBack
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu
import shark.HeapAnalysisSuccess

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
          val leakGroupByHash =
            LeakTable.retrieveHeapDumpLeaks(db, analysisId)
          val heapDumpFileExist = heapAnalysis.heapDumpFile.exists()
          updateUi { onSuccessRetrieved(heapAnalysis, leakGroupByHash, heapDumpFileExist) }
        }
      }
    }

  private fun View.onSuccessRetrieved(
    heapAnalysis: HeapAnalysisSuccess,
    leakGroupByHash: Map<String, HeapAnalysisGroupProjection>,
    heapDumpFileExist: Boolean
  ) {

    val timeString = DateUtils.formatDateTime(
        context, heapAnalysis.createdAtTimeMillis,
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
    )

    activity.title = timeString

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

    val retainedInstances = heapAnalysis.allLeaks

    retainedInstances.forEach { retainedInstance ->
      if (leakGroupByHash[retainedInstance.groupHash] == null) {
        throw IllegalStateException(
            "Removing groups is not supported, this should never happen."
        )
      }
    }

    val leakGroups = leakGroupByHash.values.toList()

    listView.adapter = object : BaseAdapter() {
      override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup
      ) = when (getItemViewType(position)) {
        METADATA -> {
          val view = convertView ?: parent.inflate(R.layout.leak_canary_leak_header)
          val textView = view.findViewById<TextView>(R.id.leak_canary_header_text)
          textView.movementMethod = LinkMovementMethod.getInstance()
          val titleText = """
      Explore <a href="explore_hprof">Heap Dump</a><br><br>
      Share <a href="share">Heap Dump analysis</a><br><br>
      Share <a href="share_hprof">Heap Dump file</a><br><br>
    """.trimIndent() +
              (heapAnalysis.metadata + mapOf(
                  "Analysis duration" to "${heapAnalysis.analysisDurationMillis} ms"
                  , "Heap dump file path" to heapAnalysis.heapDumpFile.absolutePath
                  , "Heap dump timestamp" to "${heapAnalysis.createdAtTimeMillis}"
              ))
                  .map { "<b>${it.key}:</b> ${it.value}" }
                  .joinToString("<br>")
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
                  share(heapAnalysis.toString())
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

          textView.text = title


          view
        }
        LEAK_TITLE -> {
          val view = convertView ?: parent.inflate(R.layout.leak_canary_heap_dump_leak_title)
          val leaksTextView = view.findViewById<TextView>(R.id.leak_canary_heap_dump_leaks)
          leaksTextView.text = resources.getQuantityString(
              R.plurals.leak_canary_distinct_leaks,
              leakGroups.size, leakGroups.size
          )
          view
        }
        LEAK_ROW -> {
          val view = convertView ?: parent.inflate(R.layout.leak_canary_leak_row)
          val countView = view.findViewById<TextView>(R.id.leak_canary_count_text)
          val descriptionView = view.findViewById<TextView>(R.id.leak_canary_leak_text)
          val timeView = view.findViewById<TextView>(R.id.leak_canary_time_text)

          val projection = leakGroups[position - 2]

          val isNew = projection.isNew && !projection.isLibraryLeak

          countView.text = projection.leakCount.toString()
          descriptionView.text =
            (if (isNew) "[NEW] " else "") + projection.description

          val formattedDate = DateUtils.formatDateTime(
              view.context, projection.createdAtTimeMillis,
              DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
          )
          timeView.text =
            resources.getString(R.string.leak_canary_group_list_time_label, formattedDate)
          view
        }
        else -> {
          throw IllegalStateException("Unexpected type ${getItemViewType(position)}")
        }
      }

      override fun getItem(position: Int) = this

      override fun getItemId(position: Int) = position.toLong()

      override fun getCount() = 2 + leakGroups.size

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
        goTo(LeakScreen(leakGroups[position - 2].hash, analysisId))
      }
    }
  }

  companion object {
    const val METADATA = 0
    const val LEAK_TITLE = 1
    const val LEAK_ROW = 2
  }
}
