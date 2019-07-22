package leakcanary.internal.activity.screen

import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import com.squareup.leakcanary.core.R
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.LeakingInstanceTable
import leakcanary.internal.activity.db.LeakingInstanceTable.HeapAnalysisGroupProjection
import leakcanary.internal.activity.db.executeOnDb
import leakcanary.internal.activity.shareHeapDump
import leakcanary.internal.activity.ui.SimpleListAdapter
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goBack
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu
import shark.HeapAnalysisSuccess

internal class HeapAnalysisSuccessScreen(
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
            LeakingInstanceTable.retrieveAllByHeapAnalysisId(db, analysisId)
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
    activity.title = resources.getString(
        R.string.leak_canary_heap_analysis_success_screen_title,
        heapAnalysis.allLeaks.size
    )

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
        menu.add(R.string.leak_canary_share_heap_dump)
            .setOnMenuItemClickListener {
              shareHeapDump(heapAnalysis.heapDumpFile)
              true
            }
        menu.add(R.string.leak_canary_options_menu_render_heap_dump)
            .setOnMenuItemClickListener {
              goTo(RenderHeapDumpScreen(heapAnalysis.heapDumpFile))
              true
            }
        menu.add(R.string.leak_canary_options_menu_explore_heap_dump)
            .setOnMenuItemClickListener {
              goTo(HprofExplorerScreen(heapAnalysis.heapDumpFile))
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

    val rowList = mutableListOf<Pair<String, String>>()

    val leakGroups = leakGroupByHash.values.toList()

    rowList.addAll(leakGroups.map { projection ->
      val description = projection.description

      val titleText = if (projection.isNew && !projection.isLibraryLeak) {
        resources.getString(
            R.string.leak_canary_heap_analysis_success_screen_row_title_new, projection.leakCount,
            description
        )
      } else {
        resources.getString(
            R.string.leak_canary_heap_analysis_success_screen_row_title, projection.leakCount,
            projection.totalLeakCount, description
        )
      }
      val timeText = resources.getString(
          R.string.leak_canary_heap_analysis_success_screen_row_time_format,
          DateUtils.formatDateTime(
              context, projection.createdAtTimeMillis,
              DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
          )
      )
      titleText to timeText
    })

    listView.adapter =
      SimpleListAdapter(R.layout.leak_canary_leak_row, rowList) { view, position ->
        val titleView = view.findViewById<TextView>(R.id.leak_canary_row_text)
        val timeView = view.findViewById<TextView>(R.id.leak_canary_row_time)

        val (titleText, timeText) = getItem(position)

        titleView.text = titleText
        timeView.text = timeText
      }

    listView.setOnItemClickListener { _, _, position, _ ->
      if (position < leakGroupByHash.size) {
        goTo(GroupScreen(leakGroups[position].hash))
      }
    }
  }
}