package leakcanary.internal.activity.screen

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.squareup.leakcanary.core.R
import leakcanary.internal.activity.db
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.HeapAnalysisTable.Projection
import leakcanary.internal.activity.ui.SimpleListAdapter
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu

// TODO Fix
@SuppressLint("SetTextI18n")
internal class HeapAnalysisListScreen : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_list).apply {
      // TODO String res
      activity.title = "All analyses"

      val listView = findViewById<ListView>(R.id.leak_canary_list)

      onCreateOptionsMenu { menu ->
        menu.add(R.string.leak_canary_delete_all)
            .setOnMenuItemClickListener {
              AlertDialog.Builder(context)
                  .setIcon(android.R.drawable.ic_dialog_alert)
                  .setTitle(R.string.leak_canary_delete_all)
                  .setMessage(R.string.leak_canary_delete_all_leaks_title)
                  .setPositiveButton(android.R.string.ok) { _, _ ->
                    HeapAnalysisTable.deleteAll(db, context)
                    listView.adapter =
                      SimpleListAdapter(R.layout.leak_canary_leak_row, emptyList<Any>()) { _, _ -> }
                  }
                  .setNegativeButton(android.R.string.cancel, null)
                  .show()
              true
            }
      }

      val projections = HeapAnalysisTable.retrieveAll(db)

      listView.setOnItemClickListener { _, _, position, _ ->
        val projection = projections[position]
        val analysisScreen = if (projection.exceptionSummary != null)
          HeapAnalysisFailureScreen(projection.id)
        else
          HeapAnalysisSuccessScreen(projection.id)
        goTo(analysisScreen)
      }

      listView.adapter =
        SimpleListAdapter(R.layout.leak_canary_leak_row, projections) { view, position ->
          val titleView = view.findViewById<TextView>(R.id.leak_canary_row_text)
          val timeView = view.findViewById<TextView>(R.id.leak_canary_row_time)
          val index = count - position

          val projection = getItem(position)

          timeView.text = DateUtils.formatDateTime(
              view.context, projection.createdAtTimeMillis,
              DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
          )

          titleView.text = "$index. " + (projection.exceptionSummary
          // TODO Handle singular
              ?: "${projection.retainedInstanceCount} retained instances")
        }
    }

}