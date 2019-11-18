package leakcanary.internal.activity.screen

import android.app.AlertDialog
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import com.squareup.leakcanary.core.R
import leakcanary.LeakCanary
import leakcanary.internal.activity.LeakActivity
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.HeapAnalysisTable.Projection
import leakcanary.internal.activity.db.executeOnDb
import leakcanary.internal.activity.ui.SimpleListAdapter
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu

internal class HeapDumpsScreen : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_heap_dumps_screen).apply {
      onCreateOptionsMenu { menu ->
        menu.add(R.string.leak_canary_delete_all)
            .setOnMenuItemClickListener {
              AlertDialog.Builder(context)
                  .setIcon(android.R.drawable.ic_dialog_alert)
                  .setTitle(R.string.leak_canary_delete_all)
                  .setMessage(R.string.leak_canary_delete_all_leaks_title)
                  .setPositiveButton(android.R.string.ok) { _, _ ->
                    executeOnDb {
                      HeapAnalysisTable.deleteAll(db)
                      updateUi {
                        val listView = findViewById<ListView>(R.id.leak_canary_list)
                        listView.adapter =
                          SimpleListAdapter(
                              R.layout.leak_canary_simple_row, emptyList<Any>()
                          ) { _, _ -> }
                      }
                    }
                  }
                  .setNegativeButton(android.R.string.cancel, null)
                  .show()
              true
            }
      }

      findViewById<View>(R.id.leak_canary_import_heap_dump).setOnClickListener {
        activity<LeakActivity>().requestImportHprof()
      }

      findViewById<View>(R.id.leak_canary_dump_heap_now).setOnClickListener {
        LeakCanary.dumpHeap()
      }

      executeOnDb {
        val projections = HeapAnalysisTable.retrieveAll(db)
        updateUi { onAnalysesRetrieved(projections) }
      }

    }

  private fun View.onAnalysesRetrieved(projections: List<Projection>) {
    activity.title = resources.getString(
        R.string.leak_canary_heap_analysis_list_screen_title, projections.size
    )

    val listView = findViewById<ListView>(R.id.leak_canary_list)

    listView.setOnItemClickListener { _, _, position, _ ->
      val projection = projections[position]
      val analysisScreen = if (projection.exceptionSummary != null)
        HeapAnalysisFailureScreen(projection.id)
      else
        HeapDumpScreen(projection.id)
      goTo(analysisScreen)
    }

    listView.adapter =
      SimpleListAdapter(R.layout.leak_canary_leak_row, projections) { view, position ->
        val goneView = view.findViewById<TextView>(R.id.leak_canary_count_text)
        goneView.visibility = View.GONE
        val timeView = view.findViewById<TextView>(R.id.leak_canary_leak_text)
        val countView = view.findViewById<TextView>(R.id.leak_canary_time_text)

        val projection = getItem(position)

        timeView.text = DateUtils.formatDateTime(
            view.context, projection.createdAtTimeMillis,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
        )

        val count = projection.exceptionSummary ?: resources.getQuantityString(
            R.plurals.leak_canary_distinct_leaks,
            projection.leakGroupCount, projection.leakGroupCount
        )
        countView.text = count
      }
  }

}